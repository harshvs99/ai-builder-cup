"""SQLite schema and repository.

One connection guarded by an RLock (FastAPI runs sync endpoints in a thread pool). The
connection is opened lazily so importing the app never touches the filesystem. Use
``with store.tx():`` for atomic multi-statement work; transactions nest (only the outermost
one issues BEGIN/COMMIT).
"""

from __future__ import annotations

import json
import os
import sqlite3
import tempfile
import threading
from contextlib import contextmanager
from typing import Any, Callable, Iterator

from ..codec import CATEGORY_NAMES, Need
from .base import JSON_COLUMNS, TICKET_COLUMNS, AckSpec, IngestOutcome, Lookup, PacketIn, Plan, RecordStatus

SCHEMA = """
CREATE TABLE IF NOT EXISTS tickets (
    ticket_no          INTEGER PRIMARY KEY AUTOINCREMENT,
    category           TEXT    NOT NULL,
    item               TEXT    NOT NULL DEFAULT '',
    landmark           TEXT    NOT NULL DEFAULT '',
    summary            TEXT    NOT NULL DEFAULT '',
    lat_e5             INTEGER NOT NULL,
    lon_e5             INTEGER NOT NULL,
    urgency            INTEGER NOT NULL,
    people             INTEGER NOT NULL,
    vuln_flags         INTEGER NOT NULL,
    lang               TEXT    NOT NULL,
    report_count       INTEGER NOT NULL DEFAULT 1,
    priority_score     REAL    NOT NULL DEFAULT 0,
    priority_tier      TEXT    NOT NULL DEFAULT 'P4',
    tier_adjust        INTEGER NOT NULL DEFAULT 0,
    priority_reason    TEXT    NOT NULL DEFAULT '',
    status             TEXT    NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','DISPATCHED')),
    dispatch_draft_en    TEXT  NOT NULL DEFAULT '',
    dispatch_draft_local TEXT  NOT NULL DEFAULT '',
    first_reported_at  INTEGER NOT NULL,
    max_hops           INTEGER NOT NULL DEFAULT 0,
    created_at         INTEGER NOT NULL,
    updated_at         INTEGER NOT NULL,
    geo                TEXT,    -- JSON {lat, lon, source, query}: estimate from the landmark, no GPS fix
    reclass            TEXT,    -- JSON {from, to, reason}: category corrected at triage
    answers            TEXT     -- JSON {question key: answer key}: intake answers (first report wins)
);
CREATE INDEX IF NOT EXISTS idx_tickets_open ON tickets(status, category);

CREATE TABLE IF NOT EXISTS packets (
    msg_id       TEXT PRIMARY KEY,           -- 16 hex chars
    frame        BLOB    NOT NULL,           -- full outer frame as uploaded
    origin_node  TEXT    NOT NULL,
    created_at   INTEGER NOT NULL,           -- raw NEED.created_at
    reported_at  INTEGER NOT NULL,           -- created_at after clock sanity clamp
    category     INTEGER NOT NULL,
    urgency      INTEGER NOT NULL,
    people       INTEGER NOT NULL,
    vuln_flags   INTEGER NOT NULL,
    lat_e5       INTEGER NOT NULL,
    lon_e5       INTEGER NOT NULL,
    loc_acc      INTEGER NOT NULL,
    lang         INTEGER NOT NULL,
    item         TEXT    NOT NULL,
    landmark     TEXT    NOT NULL,
    summary      TEXT    NOT NULL,
    gateway_node TEXT    NOT NULL,
    received_at  INTEGER NOT NULL,
    hops         INTEGER NOT NULL            -- 7 - ttl, floored at 0
);

CREATE TABLE IF NOT EXISTS ticket_packets (
    ticket_no INTEGER NOT NULL REFERENCES tickets(ticket_no),
    msg_id    TEXT    NOT NULL REFERENCES packets(msg_id),
    PRIMARY KEY (ticket_no, msg_id)
);
CREATE INDEX IF NOT EXISTS idx_ticket_packets_msg ON ticket_packets(msg_id);

CREATE TABLE IF NOT EXISTS acks (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,   -- the sync cursor
    ack_id      BLOB    NOT NULL UNIQUE,
    payload     BLOB    NOT NULL,
    target_node TEXT    NOT NULL,
    ref_msg_id  TEXT    NOT NULL,
    ticket_no   INTEGER NOT NULL,
    status      INTEGER NOT NULL,
    created_at  INTEGER NOT NULL
);
-- at most one ACK per (NEED, status): makes approve and re-ingest idempotent at the DB level
CREATE UNIQUE INDEX IF NOT EXISTS idx_acks_ref_status ON acks(ref_msg_id, status);

CREATE TABLE IF NOT EXISTS gateways (
    node      TEXT PRIMARY KEY,
    last_seen REAL NOT NULL
);
"""

# ticket numbers start at 101: seed sqlite_sequence once, before any ticket exists
_SEED_SEQUENCE = (
    "INSERT INTO sqlite_sequence (name, seq) "
    "SELECT 'tickets', 100 WHERE NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name = 'tickets')"
)

def default_db_path() -> str:
    """``RELAY_DB``, else ``relay.db`` in the working directory. If that directory is not
    writable (the production image runs as an unprivileged user in a root-owned /app), use the
    temp directory instead so a dev-mode container still starts."""
    env = os.environ.get("RELAY_DB")
    if env:
        return env
    if os.access(".", os.W_OK):
        return "relay.db"
    return os.path.join(tempfile.gettempdir(), "relay.db")


class SqliteStore:
    """SQLite implementation of the ``Store`` interface (local dev, tests, single-instance demo)."""

    def __init__(self, path: str | None = None):
        self.path = path if path is not None else default_db_path()
        self._lock = threading.RLock()
        self._conn: sqlite3.Connection | None = None
        self._depth = 0

    # ------------------------------------------------------------------ plumbing
    def _connect(self) -> sqlite3.Connection:
        if self._conn is None:
            conn = sqlite3.connect(self.path, check_same_thread=False, isolation_level=None)
            conn.row_factory = sqlite3.Row
            conn.execute("PRAGMA foreign_keys = ON")
            conn.execute("PRAGMA busy_timeout = 5000")
            if self.path != ":memory:":
                conn.execute("PRAGMA journal_mode = WAL")
            conn.executescript(SCHEMA)
            have = {r["name"] for r in conn.execute("PRAGMA table_info(tickets)")}
            for col in JSON_COLUMNS:  # databases created before these columns existed
                if col not in have:
                    conn.execute(f"ALTER TABLE tickets ADD COLUMN {col} TEXT")
            conn.execute(_SEED_SEQUENCE)
            self._conn = conn
        return self._conn

    @contextmanager
    def tx(self) -> Iterator[sqlite3.Connection]:
        with self._lock:
            conn = self._connect()
            outer = self._depth == 0
            if outer:
                conn.execute("BEGIN IMMEDIATE")
            self._depth += 1
            try:
                yield conn
            except BaseException:
                self._depth -= 1
                if outer:
                    conn.execute("ROLLBACK")
                raise
            else:
                self._depth -= 1
                if outer:
                    conn.execute("COMMIT")

    def close(self) -> None:
        with self._lock:
            if self._conn is not None:
                self._conn.close()
                self._conn = None

    @staticmethod
    def _row(r: sqlite3.Row) -> dict[str, Any]:
        d = dict(r)
        for col in JSON_COLUMNS:
            if col in d:
                d[col] = json.loads(d[col]) if d[col] else None
        return d

    @staticmethod
    def _enc(fields: dict[str, Any]) -> list[Any]:
        return [json.dumps(v, ensure_ascii=False) if k in JSON_COLUMNS and v is not None else v for k, v in fields.items()]

    def _q(self, sql: str, args: tuple | list = ()) -> list[sqlite3.Row]:
        with self._lock:
            return self._connect().execute(sql, args).fetchall()

    # ------------------------------------------------------------------ gateways
    def touch_gateway(self, node: str, now: float) -> None:
        with self.tx() as c:
            c.execute(
                "INSERT INTO gateways(node, last_seen) VALUES(?, ?) "
                "ON CONFLICT(node) DO UPDATE SET last_seen = excluded.last_seen",
                (node, now),
            )

    def gateways_online(self, now: float, window_s: float = 60.0) -> int:
        return self._q("SELECT COUNT(*) AS n FROM gateways WHERE last_seen >= ?", (now - window_s,))[0]["n"]

    # ------------------------------------------------------------------ packets
    def record_packets(self, packets: list[PacketIn], gateway_node: str, received_at: int) -> list[RecordStatus]:
        out: list[RecordStatus] = []
        with self.tx():
            for p in packets:
                if self.insert_packet(p.frame, p.need, gateway_node, received_at, p.hops, p.reported_at):
                    out.append("new")
                else:
                    out.append("done" if self._linked_ticket(p.need.msg_id.hex()) is not None else "pending")
        return out

    def _linked_ticket(self, msg_id: str) -> int | None:
        rows = self._q("SELECT ticket_no FROM ticket_packets WHERE msg_id = ? LIMIT 1", (msg_id,))
        return int(rows[0]["ticket_no"]) if rows else None

    def ingest(
        self, *, packet: PacketIn, gateway_node: str, received_at: int, lookup: Lookup | None,
        plan: Callable[[list[dict[str, Any]]], Plan],
    ) -> IngestOutcome:
        need = packet.need
        msg_hex = need.msg_id.hex()
        with self.tx():
            if self.insert_packet(packet.frame, need, gateway_node, received_at, packet.hops, packet.reported_at):
                status = "new"
            elif self._linked_ticket(msg_hex) is not None:
                return IngestOutcome("duplicate")
            else:
                status = "resumed"
            candidates = self.open_candidates(*lookup) if lookup else []
            decision = plan(candidates)
            created = decision.ticket_no is None
            if created:
                ticket_no = self.create_ticket(decision.fields)
            else:
                ticket_no = decision.ticket_no
                self.update_ticket(ticket_no, **decision.fields)
            self.link_packet(ticket_no, msg_hex)
            self.queue_ack_spec(decision.make_ack(ticket_no), received_at)
            return IngestOutcome(status, ticket_no, created)

    def insert_packet(
        self, frame: bytes, need: Need, gateway_node: str, received_at: int, hops: int, reported_at: int
    ) -> bool:
        """Insert a NEED packet. Returns False (and writes nothing) if msg_id already exists."""
        with self.tx() as c:
            cur = c.execute(
                "INSERT OR IGNORE INTO packets(msg_id, frame, origin_node, created_at, reported_at, "
                "category, urgency, people, vuln_flags, lat_e5, lon_e5, loc_acc, lang, item, landmark, "
                "summary, gateway_node, received_at, hops) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (
                    need.msg_id.hex(), frame, need.origin_node.hex(), need.created_at, reported_at,
                    need.category, need.urgency, need.people, need.vuln_flags, need.lat_e5, need.lon_e5,
                    need.loc_acc, need.lang, need.item, need.landmark, need.summary, gateway_node,
                    received_at, hops,
                ),
            )
            return cur.rowcount == 1

    def link_packet(self, ticket_no: int, msg_id: str) -> None:
        with self.tx() as c:
            c.execute("INSERT OR IGNORE INTO ticket_packets(ticket_no, msg_id) VALUES (?, ?)", (ticket_no, msg_id))

    def ticket_packets(self, ticket_no: int) -> list[dict[str, Any]]:
        rows = self._q(
            "SELECT p.* FROM packets p JOIN ticket_packets tp ON tp.msg_id = p.msg_id "
            "WHERE tp.ticket_no = ? ORDER BY p.rowid",
            (ticket_no,),
        )
        return [dict(r) for r in rows]

    def links_for(self, ticket_nos: list[int]) -> dict[int, list[dict[str, Any]]]:
        """Packets (msg_id, gateway_node, loc_acc) per ticket, insertion order, in one query."""
        out: dict[int, list[dict[str, Any]]] = {n: [] for n in ticket_nos}
        if not ticket_nos:
            return out
        marks = ",".join("?" * len(ticket_nos))
        rows = self._q(
            f"SELECT tp.ticket_no, p.msg_id, p.gateway_node, p.loc_acc FROM ticket_packets tp "
            f"JOIN packets p ON p.msg_id = tp.msg_id WHERE tp.ticket_no IN ({marks}) ORDER BY p.rowid",
            ticket_nos,
        )
        for r in rows:
            out[r["ticket_no"]].append(
                {"msg_id": r["msg_id"], "gateway_node": r["gateway_node"], "loc_acc": r["loc_acc"]}
            )
        return out

    def origins_for_ticket(self, ticket_no: int) -> list[dict[str, Any]]:
        """One packet per distinct origin_node (the origin's earliest packet), oldest first."""
        seen: dict[str, dict[str, Any]] = {}
        for p in self.ticket_packets(ticket_no):
            seen.setdefault(p["origin_node"], p)
        return list(seen.values())

    # ------------------------------------------------------------------ tickets
    def create_ticket(self, fields: dict[str, Any]) -> int:
        assert set(fields) <= TICKET_COLUMNS, set(fields) - TICKET_COLUMNS
        cols = ",".join(fields)
        marks = ",".join("?" * len(fields))
        with self.tx() as c:
            cur = c.execute(f"INSERT INTO tickets({cols}) VALUES ({marks})", self._enc(fields))
            return int(cur.lastrowid)

    def update_ticket(self, ticket_no: int, **fields: Any) -> None:
        assert set(fields) <= TICKET_COLUMNS, set(fields) - TICKET_COLUMNS
        if not fields:
            return
        sets = ",".join(f"{k} = ?" for k in fields)
        with self.tx() as c:
            c.execute(f"UPDATE tickets SET {sets} WHERE ticket_no = ?", [*self._enc(fields), ticket_no])

    def get_ticket(self, ticket_no: int) -> dict[str, Any] | None:
        rows = self._q("SELECT * FROM tickets WHERE ticket_no = ?", (ticket_no,))
        return self._row(rows[0]) if rows else None

    def list_tickets(self) -> list[dict[str, Any]]:
        rows = self._q("SELECT * FROM tickets ORDER BY priority_score DESC, first_reported_at ASC, ticket_no ASC")
        return [self._row(r) for r in rows]

    def open_candidates(self, category: str, reported_at: int, window_s: int) -> list[dict[str, Any]]:
        rows = self._q(
            "SELECT * FROM tickets WHERE status = 'OPEN' AND category = ? "
            "AND ABS(first_reported_at - ?) <= ? ORDER BY ticket_no",
            (category, reported_at, window_s),
        )
        return [self._row(r) for r in rows]

    def update_ticket_if_unchanged(self, ticket_no: int, report_count: int, fields: dict[str, Any]) -> bool:
        with self.tx():
            cur = self.get_ticket(ticket_no)
            if cur is None or cur["status"] != "OPEN" or cur["report_count"] != report_count:
                return False
            self.update_ticket(ticket_no, **fields)
            return True

    def approve_ticket(self, ticket_no: int, now: int, make_ack: Callable[[dict[str, Any]], AckSpec]) -> bool | None:
        with self.tx():
            if self.get_ticket(ticket_no) is None:
                return None
            if not self.mark_dispatched(ticket_no, now):
                return False
            for p in self.origins_for_ticket(ticket_no):
                self.queue_ack_spec(make_ack(p), now)
            return True

    def list_tickets_with_links(self) -> list[tuple[dict[str, Any], list[dict[str, Any]]]]:
        rows = self.list_tickets()
        links = self.links_for([r["ticket_no"] for r in rows])
        return [(r, links[r["ticket_no"]]) for r in rows]

    def mark_dispatched(self, ticket_no: int, now: int) -> bool:
        """OPEN -> DISPATCHED. Returns True only for the call that performed the transition."""
        with self.tx() as c:
            cur = c.execute(
                "UPDATE tickets SET status = 'DISPATCHED', updated_at = ? "
                "WHERE ticket_no = ? AND status = 'OPEN'",
                (now, ticket_no),
            )
            return cur.rowcount == 1

    def stats(self) -> dict[str, Any]:
        open_by_cat = {name: 0 for name in CATEGORY_NAMES}
        for r in self._q("SELECT category, COUNT(*) AS n FROM tickets WHERE status = 'OPEN' GROUP BY category"):
            open_by_cat[r["category"]] = r["n"]
        total = self._q("SELECT COUNT(*) AS n FROM tickets")[0]["n"]
        return {"open_by_category": open_by_cat, "tickets": total}

    # ------------------------------------------------------------------ acks
    def queue_ack(
        self, ack_id: bytes, payload: bytes, target_node: str, ref_msg_id: str, ticket_no: int,
        status: int, now: int,
    ) -> int | None:
        """Queue an ACK. Returns its cursor id, or None if (ref_msg_id, status) already has one."""
        with self.tx() as c:
            cur = c.execute(
                "INSERT OR IGNORE INTO acks(ack_id, payload, target_node, ref_msg_id, ticket_no, status, created_at) "
                "VALUES (?,?,?,?,?,?,?)",
                (ack_id, payload, target_node, ref_msg_id, ticket_no, status, now),
            )
            return int(cur.lastrowid) if cur.rowcount == 1 else None

    def queue_ack_spec(self, a: AckSpec, now: int) -> int | None:
        return self.queue_ack(a.ack_id, a.payload, a.target_node, a.ref_msg_id, a.ticket_no, a.status, now)

    def clamp_ack_cursor(self, cursor: int) -> int:
        newest = self._q("SELECT COALESCE(MAX(id), 0) AS n FROM acks")[0]["n"]
        return 0 if cursor > newest else cursor

    def acks_after(self, cursor: int, limit: int = 50) -> list[dict[str, Any]]:
        rows = self._q("SELECT id, payload FROM acks WHERE id > ? ORDER BY id ASC LIMIT ?", (cursor, limit))
        return [dict(r) for r in rows]

    def ack_count(self) -> int:
        return self._q("SELECT COUNT(*) AS n FROM acks")[0]["n"]
