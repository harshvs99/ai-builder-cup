from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field

TicketStatus = Literal["OPEN", "DISPATCHED", "RESOLVED"]


class Report(BaseModel):
    """One NEED packet that was folded into a ticket."""

    msg_id: str
    origin_node: str
    lang: str
    summary: str
    created_at: int
    received_at: float
    gateway_node: str
    hops: int | None = None


class Ticket(BaseModel):
    id: int = 0
    status: TicketStatus = "OPEN"
    category: str
    item: str = ""
    landmark: str = ""
    summary: str = ""
    urgency: int = 0
    people: int = 0
    flags: list[str] = Field(default_factory=list)
    lat: float | None = None
    lon: float | None = None
    loc_acc_m: int | None = None
    reports: list[Report] = Field(default_factory=list)
    first_reported_at: float = 0.0
    # Priority: the score is deterministic (see priority.py). Gemini may move the
    # tier by one step and must say why.
    priority_score: float = 0.0
    tier_adjust: int = 0
    priority_reason: str = ""
    dispatch_draft: dict[str, str] = Field(default_factory=dict)
    enriched_by: Literal["rules", "gemini"] = "rules"
    eta_min: int | None = None
    operator_note: str = ""
    created_at: float = 0.0
    updated_at: float = 0.0
    rev: int = 0

    @property
    def report_count(self) -> int:
        return len(self.reports)

    @property
    def origin_nodes(self) -> set[str]:
        return {r.origin_node for r in self.reports}

    @property
    def primary_lang(self) -> str:
        return self.reports[0].lang if self.reports else "und"

    @property
    def max_hops(self) -> int | None:
        hops = [r.hops for r in self.reports if r.hops is not None]
        return max(hops) if hops else None
