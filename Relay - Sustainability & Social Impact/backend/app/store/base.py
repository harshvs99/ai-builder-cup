"""Store interface shared by the SQLite and Firestore repositories.

The pipeline never opens a transaction itself. Everything that must be atomic is one store
method, so each backend can use its own mechanism (a SQLite transaction, a Firestore
transaction) and the pipeline code is identical for both:

  * ``ingest``          dedupe + cluster + ticket upsert + link + RECEIVED ack, for one packet
  * ``approve_ticket``  OPEN -> DISPATCHED plus one DISPATCHED ack per distinct origin
  * ``advance_ticket``  DISPATCHED -> ON_THE_WAY -> RESOLVED plus one ack per distinct origin
  * ``update_ticket_if_unchanged``  compare-and-set used by the Gemini enrichment phase
  * ``set_geo``         guarded write of a location estimate (mesh, or an on-demand landmark one) onto a no-fix ticket

``ingest`` calls back into the pipeline (``plan``) with the candidate tickets the store read
inside its transaction. ``plan`` must be a pure function of its argument: Firestore may run
it more than once when a transaction is retried.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable, Literal, Protocol

from ..codec import Need

def keep_mesh_geo(current: dict[str, Any] | None, fields: dict[str, Any]) -> dict[str, Any]:
    """``fields`` for an update that must not replace a mesh estimate with a landmark one (the enrichment phase
    reads the ticket first and writes later; a hint may land in between)."""
    if "geo" in fields and (current or {}).get("source") == "mesh":
        return {k: v for k, v in fields.items() if k != "geo"}
    return fields


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
    # JSON objects, null when unset: estimated location from a landmark; category correction;
    # intake answers {question key: answer key}
    "geo", "reclass", "answers",
}
# Columns added after the first release: SQLite adds them to existing databases on connect.
JSON_COLUMNS = ("geo", "reclass", "answers")

# Ticket status -> the statuses it may be entered from (DISPATCHED comes from ``approve_ticket``).
ADVANCE_FROM: dict[str, tuple[str, ...]] = {
    "ON_THE_WAY": ("DISPATCHED",),
    "RESOLVED": ("DISPATCHED", "ON_THE_WAY"),
}

# What ``advance_ticket`` reports: the transition happened / the ticket is already there / not allowed.
AdvanceResult = Literal["done", "noop", "conflict"]


def merge_dispatch(existing: dict[str, Any] | None, patch: dict[str, Any]) -> dict[str, Any]:
    """The dispatch record after ``patch`` (timestamps, ETA). A plain approval stores no dispatch
    record, so one is created without ``approved_at``."""
    return {**(existing or {"team_id": None, "team_name": None, "eta_min": None}), **patch}


class TeamAtCapacity(Exception):
    """``approve_ticket`` refused to assign a team that already carries its capacity."""


@dataclass(frozen=True)
class HintIn:
    """A validated LOC_HINT ready to be stored. Keyed by (ref_msg_id, relay_node) via ``key`` (hex)."""
    key: str
    ref_msg_id: str  # hex of the NEED it is about
    relay_node: str
    lat_e5: int
    lon_e5: int
    acc: int
    rssi_dbm: int
    observed_at: int
    gateway_node: str
    received_at: int


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
    def list_gateways(self) -> list[dict[str, Any]]:
        """Every gateway that ever synced as ``{node, last_seen}``, most recently seen first."""
        ...

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

    # ------------------------------------------------------------------ mesh location hints
    def add_hints(self, hints: list[HintIn]) -> list[bool]:
        """Store LOC_HINTs idempotently (create-if-absent on ``key``, first one wins). True per hint stored now,
        False for one seen before. A hint may arrive before the NEED it refers to."""
        ...

    def hints_for(self, msg_ids: list[str]) -> list[dict[str, Any]]:
        """Stored hints (``HintIn`` fields as dicts) whose ``ref_msg_id`` is one of ``msg_ids``."""
        ...

    def ticket_for_msg(self, msg_id: str) -> int | None:
        """The ticket a NEED was triaged into, or None (unknown NEED, or recorded but not triaged yet)."""
        ...

    def set_geo(self, ticket_no: int, geo: dict[str, Any]) -> bool:
        """Atomically set ``geo`` on the ticket unless it does not exist or any of its packets carries a GPS fix
        (``loc_acc != 0``): a real fix is never overwritten or shadowed. A ``source: "mesh"`` record replaces a
        landmark estimate; a landmark record (gazetteer / gemini_maps) never replaces a mesh one (``keep_mesh_geo``).
        True if written."""
        ...

    # ------------------------------------------------------------------ control-room broadcasts (SPEC 5.6)
    def add_broadcast(self, bcast_id: str, issued_at: int, payload: bytes) -> bool:
        """Store a BROADCAST payload idempotently on ``bcast_id`` (hex). True if stored now."""
        ...

    def live_broadcasts(self, since: int) -> list[bytes]:
        """Payloads of the broadcasts with ``issued_at >= since``, oldest first."""
        ...

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

    def approve_ticket(
        self, ticket_no: int, now: int, make_ack: Callable[[dict[str, Any]], AckSpec], *,
        dispatch: dict[str, Any] | None = None, capacity: int | None = None,
    ) -> bool | None:
        """OPEN -> DISPATCHED and one ack per distinct origin (``make_ack`` gets the origin's
        packet). None if no such ticket, True if this call made the transition, else False.

        With ``dispatch`` ({team_id, team_name, eta_min, approved_at, ...}) the dispatch is
        recorded in the same transaction. If it names a team and ``capacity`` is given, raises
        ``TeamAtCapacity`` (nothing written) when the team already has ``capacity`` active
        dispatches. An already-dispatched ticket returns False before any capacity check."""
        ...

    def advance_ticket(
        self, ticket_no: int, status: str, now: int, make_ack: Callable[[dict[str, Any]], AckSpec], *,
        patch: dict[str, Any] | None = None,
    ) -> AdvanceResult | None:
        """DISPATCHED -> ON_THE_WAY, or DISPATCHED / ON_THE_WAY -> RESOLVED (``ADVANCE_FROM``), with one
        ack per distinct origin (``make_ack`` gets the origin's packet). None if no such ticket,
        ``noop`` if the ticket already has ``status`` (a retry; nothing written), ``conflict`` if the
        transition is not allowed. ``patch`` is merged into the dispatch record in the same
        transaction, and resolving also releases the dispatch (the team's load drops)."""
        ...

    def stats(self) -> dict[str, Any]: ...

    def ticket_counts(self) -> dict[str, Any]:
        """{open, p1_open, p1_open_nos (score desc), dispatched (DISPATCHED + ON_THE_WAY), resolved, open_by_category,
        oldest_open_no, oldest_open_at}. Backs /v1/stats and the sitrep."""
        ...

    # ------------------------------------------------------------------ teams + dispatch
    def seed_teams(self, teams: list[dict[str, Any]]) -> None:
        """Create teams that do not exist yet (by ``id``); existing rows are left untouched."""
        ...

    def list_teams(self) -> list[dict[str, Any]]:
        """{id, name, kind, capacity, base_lat, base_lon, status} ordered by id."""
        ...

    def set_team_status(self, team_id: str, status: str) -> bool: ...

    def team_loads(self) -> dict[str, int]:
        """team_id -> number of active (approved, not released) dispatches. Teams with none are absent."""
        ...

    def dispatch_records(self) -> dict[int, dict[str, Any]]:
        """ticket_no -> {proposal: dict | None, dispatch: dict | None}."""
        ...

    def get_dispatch_record(self, ticket_no: int) -> dict[str, Any] | None: ...

    def set_proposals(self, proposals: dict[int, dict[str, Any] | None]) -> None:
        """Write (or, with None, clear) the pending proposal of each ticket."""
        ...

    def release_dispatch(self, ticket_no: int) -> None:
        """The dispatch no longer counts toward its team's load (ticket resolved)."""
        ...

    # ------------------------------------------------------------------ sitreps
    def add_sitrep(self, sitrep: dict[str, Any]) -> dict[str, Any]:
        """Store a sitrep (without ``id``) and return it with the id assigned (1, 2, ...)."""
        ...

    def latest_sitrep(self) -> dict[str, Any] | None: ...

    # ------------------------------------------------------------------ acks
    def clamp_ack_cursor(self, cursor: int) -> int:
        """A cursor beyond the newest ack sequence means the store was reset: treat as 0."""
        ...

    def acks_after(self, cursor: int, limit: int = 50) -> list[dict[str, Any]]: ...
    def ack_count(self) -> int: ...

    def close(self) -> None: ...
