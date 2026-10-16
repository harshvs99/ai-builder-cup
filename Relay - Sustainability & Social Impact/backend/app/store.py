"""SQLite persistence for packets, tickets and queued ACKs.

Every ticket write bumps a global revision counter. The dashboard asks for
"tickets changed since revision N", which keeps live updates simple: no
cross-thread signalling, just a cheap indexed query.
"""

from __future__ import annotations

import sqlite3
import threading
import time
from dataclasses import dataclass

from .models import Ticket

_SCHEMA = """
CREATE TABLE IF NOT EXISTS packets (
    msg_id TEXT PRIMARY KEY,
    origin_node TEXT NOT NULL,
    raw BLOB NOT NULL,
    gateway_node TEXT NOT NULL,
    hops INTEGER,
    received_at REAL NOT NULL,
    ticket_id INTEGER
);
CREATE TABLE IF NOT EXISTS tickets (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    status TEXT NOT NULL,
    category TEXT NOT NULL,
    first_reported_at REAL NOT NULL,
    rev INTEGER NOT NULL,
    data TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS tickets_rev ON tickets(rev);
CREATE INDEX IF NOT EXISTS tickets_match ON tickets(category, status, first_reported_at);
CREATE TABLE IF NOT EXISTS acks (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    ref_msg_id TEXT NOT NULL,
    target_node TEXT NOT NULL,
    ticket_id INTEGER NOT NULL,
    status INTEGER NOT NULL,
    raw BLOB NOT NULL,
    created_at REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS acks_created ON acks(created_at);
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value INTEGER NOT NULL);
INSERT OR IGNORE INTO meta(key, value) VALUES ('rev', 0);
"""


@dataclass(frozen=True)
class QueuedAck:
    seq: int
    raw: bytes
    target_node: str
    ticket_id: int
    status: int


class Store:
    def __init__(self, path: str = ":memory:"):
        self._lock = threading.RLock()
        self._db = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self._db.execute("PRAGMA journal_mode=WAL" if path != ":memory:" else "PRAGMA journal_mode=MEMORY")
        self._db.executescript(_SCHEMA)

    def close(self) -> None:
        with self._lock:
            self._db.close()

    # --- packets -----------------------------------------------------------

    def add_packet(
        self, msg_id: str, origin_node: str, raw: bytes, gateway_node: str, hops: int | None, received_at: float
    ) -> bool:
        """Store a packet. Returns False if this msg_id was already seen."""
        with self._lock:
            cur = self._db.execute(
                "INSERT OR IGNORE INTO packets(msg_id, origin_node, raw, gateway_node, hops, received_at)"
                " VALUES (?, ?, ?, ?, ?, ?)",
                (msg_id, origin_node, raw, gateway_node, hops, received_at),
            )
            return cur.rowcount == 1

    def link_packet(self, msg_id: str, ticket_id: int) -> None:
        with self._lock:
            self._db.execute("UPDATE packets SET ticket_id = ? WHERE msg_id = ?", (ticket_id, msg_id))

    def packet_count(self) -> int:
        with self._lock:
            return self._db.execute("SELECT COUNT(*) FROM packets").fetchone()[0]

    # --- tickets -----------------------------------------------------------

    def _next_rev(self) -> int:
        self._db.execute("UPDATE meta SET value = value + 1 WHERE key = 'rev'")
        return self._db.execute("SELECT value FROM meta WHERE key = 'rev'").fetchone()[0]

    def current_rev(self) -> int:
        with self._lock:
            return self._db.execute("SELECT value FROM meta WHERE key = 'rev'").fetchone()[0]

    def create_ticket(self, ticket: Ticket) -> Ticket:
        with self._lock:
            self._db.execute("BEGIN")
            try:
                now = time.time()
                rev = self._next_rev()
                cur = self._db.execute(
                    "INSERT INTO tickets(status, category, first_reported_at, rev, data) VALUES (?, ?, ?, ?, '{}')",
                    (ticket.status, ticket.category, ticket.first_reported_at, rev),
                )
                saved = ticket.model_copy(update={"id": cur.lastrowid, "rev": rev, "created_at": now, "updated_at": now})
                self._db.execute("UPDATE tickets SET data = ? WHERE id = ?", (saved.model_dump_json(), saved.id))
                self._db.execute("COMMIT")
            except BaseException:
                self._db.execute("ROLLBACK")
                raise
            return saved

    def save_ticket(self, ticket: Ticket) -> Ticket:
        with self._lock:
            self._db.execute("BEGIN")
            try:
                rev = self._next_rev()
                saved = ticket.model_copy(update={"rev": rev, "updated_at": time.time()})
                cur = self._db.execute(
                    "UPDATE tickets SET status = ?, category = ?, first_reported_at = ?, rev = ?, data = ? WHERE id = ?",
                    (saved.status, saved.category, saved.first_reported_at, rev, saved.model_dump_json(), saved.id),
                )
                if cur.rowcount != 1:
                    raise KeyError(f"ticket {ticket.id} not found")
                self._db.execute("COMMIT")
            except BaseException:
                self._db.execute("ROLLBACK")
                raise
            return saved

    def get_ticket(self, ticket_id: int) -> Ticket | None:
        with self._lock:
            row = self._db.execute("SELECT data FROM tickets WHERE id = ?", (ticket_id,)).fetchone()
        return Ticket.model_validate_json(row[0]) if row else None

    def list_tickets(self, statuses: tuple[str, ...] | None = None) -> list[Ticket]:
        with self._lock:
            if statuses:
                marks = ",".join("?" * len(statuses))
                rows = self._db.execute(f"SELECT data FROM tickets WHERE status IN ({marks})", statuses).fetchall()
            else:
                rows = self._db.execute("SELECT data FROM tickets").fetchall()
        return [Ticket.model_validate_json(r[0]) for r in rows]

    def match_candidates(self, category: str, since: float) -> list[Ticket]:
        """Unresolved tickets of a category first reported after ``since``."""
        with self._lock:
            rows = self._db.execute(
                "SELECT data FROM tickets WHERE category = ? AND status != 'RESOLVED' AND first_reported_at >= ?",
                (category, since),
            ).fetchall()
        return [Ticket.model_validate_json(r[0]) for r in rows]

    def tickets_changed_since(self, rev: int) -> list[Ticket]:
        with self._lock:
            rows = self._db.execute("SELECT data FROM tickets WHERE rev > ? ORDER BY rev", (rev,)).fetchall()
        return [Ticket.model_validate_json(r[0]) for r in rows]

    # --- acks --------------------------------------------------------------

    def queue_ack(self, raw: bytes, ref_msg_id: str, target_node: str, ticket_id: int, status: int) -> int:
        with self._lock:
            cur = self._db.execute(
                "INSERT INTO acks(ref_msg_id, target_node, ticket_id, status, raw, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                (ref_msg_id, target_node, ticket_id, status, raw, time.time()),
            )
            return cur.lastrowid

    def acks_after(self, cursor: int | None, not_before: float, limit: int = 200) -> list[QueuedAck]:
        """ACKs with seq > cursor. A gateway without a cursor gets everything since ``not_before``."""
        with self._lock:
            if cursor is None:
                rows = self._db.execute(
                    "SELECT seq, raw, target_node, ticket_id, status FROM acks WHERE created_at >= ? ORDER BY seq LIMIT ?",
                    (not_before, limit),
                ).fetchall()
            else:
                rows = self._db.execute(
                    "SELECT seq, raw, target_node, ticket_id, status FROM acks WHERE seq > ? ORDER BY seq LIMIT ?",
                    (cursor, limit),
                ).fetchall()
        return [QueuedAck(*row) for row in rows]

    def last_ack_seq(self) -> int:
        with self._lock:
            return self._db.execute("SELECT COALESCE(MAX(seq), 0) FROM acks").fetchone()[0]
