"""Store interface shared by the SQLite and Firestore repositories.

The pipeline never opens a transaction itself. Everything that must be atomic is one store
method, so each backend can use its own mechanism (a SQLite transaction, a Firestore
transaction) and the pipeline code is identical for both:

  * ``ingest``          dedupe + cluster + ticket upsert + link + RECEIVED ack, for one packet
  * ``approve_ticket``  OPEN -> DISPATCHED plus one DISPATCHED ack per distinct origin
  * ``update_ticket_if_unchanged``  compare-and-set used by the Gemini enrichment phase

``ingest`` calls back into the pipeline (``plan``) with the candidate tickets the store read
inside its transaction. ``plan`` must be a pure function of its argument: Firestore may run
it more than once when a transaction is retried.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable, Literal, Protocol

from ..codec import Need

# Defaults for a ticket document that does not set the field (SQLite gets the same from DDL).
TICKET_DEFAULTS: dict[str, Any] = {
    "item": "", "landmark": "", "summary": "", "report_count": 1, "priority_score": 0.0,
    "priority_tier": "P4", "tier_adjust": 0, "priority_reason": "", "status": "OPEN",
    "dispatch_draft_en": "", "dispatch_draft_local": "", "max_hops": 0,
}

TICKET_COLUMNS = {
    "category", "item", "landmark", "summary", "lat_e5", "lon_e5", "urgency", "people",
    "vuln_flags", "lang", "report_count", "priority_score", "priority_tier", "tier_adjust",
    "priority_reason", "status", "dispatch_draft_en", "dispatch_draft_local",
    "first_reported_at", "max_hops", "created_at", "updated_at",
    # JSON objects, null when unset: estimated location from a landmark; category correction
    "geo", "reclass",
}
# Columns added after the first release: SQLite adds them to existing databases on connect.
JSON_COLUMNS = ("geo", "reclass")

# What a sync said about one frame in ``record_packets``.
RecordStatus = Literal["new", "pending", "done"]


@dataclass(frozen=True)
class PacketIn:
    """A decoded NEED ready to be stored."""
    frame: bytes
    need: Need
    hops: int
    reported_at: int


@dataclass(frozen=True)
class AckSpec:
    ack_id: bytes
    payload: bytes
    target_node: str
    ref_msg_id: str
    ticket_no: int
    status: int


@dataclass
class Plan:
    """The pipeline's decision for one packet.

    ``ticket_no`` is None to create a ticket (``fields`` is then the full set of ticket
    fields) or the matched ticket (``fields`` are updates). ``make_ack`` receives the final
    ticket number, which a new ticket only gets inside the store's transaction.
    """
    ticket_no: int | None
    fields: dict[str, Any]
    make_ack: Callable[[int], AckSpec]


@dataclass(frozen=True)
class IngestOutcome:
    # new: packet stored and triaged now; resumed: packet had been recorded (Pub/Sub sync)
    # but not triaged, and is triaged now; duplicate: already triaged, nothing written.
    status: Literal["new", "resumed", "duplicate"]
    ticket_no: int | None = None
    created_ticket: bool = False


# (category, reported_at, window_s): which OPEN tickets could the packet merge into
Lookup = tuple[str, int, int]


class Store(Protocol):
    # ------------------------------------------------------------------ gateways
    def touch_gateway(self, node: str, now: float) -> None: ...
    def gateways_online(self, now: float, window_s: float = 60.0) -> int: ...

    # ------------------------------------------------------------------ packets
    def record_packets(self, packets: list[PacketIn], gateway_node: str, received_at: int) -> list[RecordStatus]:
        """Store packets idempotently without triaging them (create-if-absent on msg_id).
        ``new``: stored now. ``pending``: stored earlier, never triaged. ``done``: triaged."""
        ...

    def ingest(
        self, *, packet: PacketIn, gateway_node: str, received_at: int, lookup: Lookup | None,
        plan: Callable[[list[dict[str, Any]]], Plan],
    ) -> IngestOutcome:
        """Atomically: record the packet if absent; if it is already triaged return
        ``duplicate``; otherwise run ``plan`` over the lookup candidates, create or update the
        ticket, link the packet and queue the RECEIVED ack (idempotent on ref_msg_id+status)."""
        ...

    def ticket_packets(self, ticket_no: int) -> list[dict[str, Any]]: ...
    def origins_for_ticket(self, ticket_no: int) -> list[dict[str, Any]]: ...

    # ------------------------------------------------------------------ tickets
    def get_ticket(self, ticket_no: int) -> dict[str, Any] | None: ...
    def list_tickets_with_links(self) -> list[tuple[dict[str, Any], list[dict[str, Any]]]]:
        """Tickets ordered by priority_score desc, first_reported_at, ticket_no, each with its
        linked packets as ``{msg_id, gateway_node, loc_acc}`` in link order."""
        ...

    def update_ticket_if_unchanged(self, ticket_no: int, report_count: int, fields: dict[str, Any]) -> bool:
        """Write ``fields`` only if the ticket is OPEN and still has ``report_count`` reports."""
        ...

    def approve_ticket(self, ticket_no: int, now: int, make_ack: Callable[[dict[str, Any]], AckSpec]) -> bool | None:
        """OPEN -> DISPATCHED and one ack per distinct origin (``make_ack`` gets the origin's
        packet). None if no such ticket, True if this call made the transition, else False."""
        ...

    def stats(self) -> dict[str, Any]: ...

    # ------------------------------------------------------------------ acks
    def clamp_ack_cursor(self, cursor: int) -> int:
        """A cursor beyond the newest ack sequence means the store was reset: treat as 0."""
        ...

    def acks_after(self, cursor: int, limit: int = 50) -> list[dict[str, Any]]: ...
    def ack_count(self) -> int: ...

    def close(self) -> None: ...
