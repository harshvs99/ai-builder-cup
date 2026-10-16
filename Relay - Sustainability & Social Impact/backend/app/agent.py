"""Triage agent: turns NEED packets into prioritised tickets and confirmations.

Pipeline for each new packet (synchronous, rules only, so it's fast and always works):
  dedupe by msg_id → merge with a nearby matching ticket, or open a new one
  → deterministic priority score → rule-based dispatch draft → RECEIVED ACK

Then, in the background, Gemini enriches the ticket:
  priority review (may move the tier by one step, with a reason)
  → polished dispatch draft in English and the requester's language.

Gemini also judges "same need?" when two nearby reports name different items,
and translates operator notes into the requester's language for confirmations.
"""

from __future__ import annotations

import logging
import threading
import time
from dataclasses import dataclass
from datetime import datetime
from typing import Callable, Literal
from zoneinfo import ZoneInfo

from pydantic import BaseModel

from . import priority
from .config import Settings
from .geo import haversine_m
from .llm import LLM
from .models import Report, Ticket, TicketStatus
from .protocol import AckPacket, AckStatus, Flag, NeedPacket, encode_ack, node_hex
from .store import Store
from .templates import ack_text

log = logging.getLogger(__name__)

LANG_NAMES = {"en": "English", "hi": "Hindi", "or": "Odia", "tl": "Tagalog", "war": "Waray", "ceb": "Cebuano"}

# Reports older than this are never merge candidates, however recently a ticket was updated.
_MATCH_LOOKBACK_S = 72 * 3600
# A phone clock this far off is ignored in favour of the server receive time.
_MAX_CLOCK_AHEAD_S = 300
_MAX_REPORT_AGE_S = 7 * 24 * 3600

_STATUS_FOR_ACK: dict[AckStatus, TicketStatus] = {
    AckStatus.DISPATCHED: "DISPATCHED",
    AckStatus.ON_THE_WAY: "DISPATCHED",
    AckStatus.RESOLVED: "RESOLVED",
}


class SameNeedVerdict(BaseModel):
    same: bool
    reason: str


class PriorityReview(BaseModel):
    tier_adjust: int
    reason: str


class DispatchDraft(BaseModel):
    english: str
    local: str


class Translation(BaseModel):
    text: str


@dataclass(frozen=True)
class IngestResult:
    outcome: Literal["created", "merged", "duplicate"]
    ticket_id: int | None = None
    ack_seq: int | None = None


class Triage:
    def __init__(self, store: Store, llm: LLM, settings: Settings, clock: Callable[[], float] = time.time):
        self.store = store
        self.llm = llm
        self.settings = settings
        self.clock = clock
        self._tz = ZoneInfo(settings.timezone)
        # Serialises match-then-write so two concurrent syncs can't open twin tickets.
        self._lock = threading.Lock()

    # --- ingest ------------------------------------------------------------

    def ingest(
        self, need: NeedPacket, raw: bytes, gateway_node: str, hops: int | None = None
    ) -> IngestResult:
        now = self.clock()
        msg_id = need.msg_id_hex
        origin = node_hex(need.origin_node)
        if not self.store.add_packet(msg_id, origin, raw, gateway_node, hops, now):
            return IngestResult("duplicate")

        report = Report(
            msg_id=msg_id,
            origin_node=origin,
            lang=need.lang.code,
            summary=need.summary,
            created_at=need.created_at,
            received_at=now,
            gateway_node=gateway_node,
            hops=hops,
        )
        with self._lock:
            match = self._find_match(need, now)
            if match is not None:
                ticket = self._merge(match, need, report)
                outcome: Literal["created", "merged"] = "merged"
            else:
                ticket = self._new_ticket(need, report, self._reported_at(need, now))
                outcome = "created"
            ticket = self._refresh_rules(ticket, now)
            ticket = self.store.save_ticket(ticket) if outcome == "merged" else self.store.create_ticket(ticket)
            if outcome == "created":
                # The draft quotes the ticket number, which only exists after insert.
                ticket = self.store.save_ticket(self._refresh_rules(ticket, now))
        self.store.link_packet(msg_id, ticket.id)
        ack_seq = self._queue_ack(ticket, report, AckStatus.RECEIVED)
        log.info("NEED %s from %s → ticket #%d (%s)", msg_id, origin, ticket.id, outcome)
        return IngestResult(outcome, ticket.id, ack_seq)

    def _reported_at(self, need: NeedPacket, now: float) -> float:
        if now - _MAX_REPORT_AGE_S <= need.created_at <= now + _MAX_CLOCK_AHEAD_S:
            return min(float(need.created_at), now)
        return now

    def _find_match(self, need: NeedPacket, now: float) -> Ticket | None:
        window_start = now - self.settings.merge_window_h * 3600
        origin = node_hex(need.origin_node)
        best: Ticket | None = None
        best_distance = float("inf")
        for ticket in self.store.match_candidates(need.category.name, now - _MATCH_LOOKBACK_S):
            if max(r.received_at for r in ticket.reports) < window_start:
                continue
            if need.has_location and ticket.lat is not None and ticket.lon is not None:
                distance = haversine_m(need.lat, need.lon, ticket.lat, ticket.lon)
                if distance > self.settings.merge_radius_m:
                    continue
            elif origin in ticket.origin_nodes:
                # Same phone re-reporting, but one side has no GPS fix.
                distance = 0.0
            else:
                continue
            if distance < best_distance and self._same_item(need, ticket):
                best, best_distance = ticket, distance
        return best

    def _same_item(self, need: NeedPacket, ticket: Ticket) -> bool:
        a, b = need.item, ticket.item
        if not a or not b or a == b:
            return True
        verdict = self.llm.generate(
            "Two reports from the same place and category were received after a cyclone.\n"
            f"Category: {ticket.category}\n"
            f"Existing ticket item: <report>{b}</report>, summary: <report>{ticket.summary}</report>\n"
            f"New report item: <report>{a}</report>, summary: <report>{need.summary}</report>\n"
            "Do they describe the same need (one relief action would satisfy both)? "
            "Answer same=true only if clearly the same need.",
            SameNeedVerdict,
        )
        return bool(verdict and verdict.same)

    def _new_ticket(self, need: NeedPacket, report: Report, reported_at: float) -> Ticket:
        return Ticket(
            category=need.category.name,
            item=need.item,
            landmark=need.landmark,
            summary=need.summary,
            urgency=need.urgency,
            people=need.people,
            flags=[f.name for f in Flag if f in need.flags],
            lat=need.lat,
            lon=need.lon,
            loc_acc_m=need.loc_acc_m,
            reports=[report],
            first_reported_at=reported_at,
        )

    def _merge(self, ticket: Ticket, need: NeedPacket, report: Report) -> Ticket:
        flags = set(ticket.flags) | {f.name for f in Flag if f in need.flags}
        update: dict = {
            "reports": [*ticket.reports, report],
            "urgency": max(ticket.urgency, need.urgency),
            "people": max(ticket.people, need.people),
            "flags": [f.name for f in Flag if f.name in flags],
            "item": ticket.item or need.item,
            "landmark": ticket.landmark or need.landmark,
            "summary": ticket.summary or need.summary,
        }
        if need.has_location and (
            ticket.lat is None or (need.loc_acc_m or 9999) < (ticket.loc_acc_m or 9999)
        ):
            update.update(lat=need.lat, lon=need.lon, loc_acc_m=need.loc_acc_m)
        return ticket.model_copy(update=update)

    def _refresh_rules(self, ticket: Ticket, now: float) -> Ticket:
        update: dict = {"priority_score": priority.score(ticket, now)}
        if ticket.enriched_by == "rules":
            update["priority_reason"] = priority.explain(ticket, now)
            update["dispatch_draft"] = {"en": self._rules_dispatch(ticket, now)}
        return ticket.model_copy(update=update)

    def _rules_dispatch(self, t: Ticket, now: float) -> str:
        tier = priority.final_tier(t, now)
        what = t.item or "unspecified"
        who = f"{t.people} people" if t.people > 1 else "1 person" if t.people == 1 else "number of people unknown"
        if t.flags:
            who += f" ({', '.join(f.lower() for f in t.flags)})"
        if t.lat is not None and t.lon is not None:
            where = f"{t.lat:.5f}, {t.lon:.5f} (±{t.loc_acc_m or '?'} m)"
            if t.landmark:
                where += f", near {t.landmark}"
        else:
            where = f"no GPS fix, near {t.landmark}" if t.landmark else "no GPS fix, location unknown"
        first = datetime.fromtimestamp(t.first_reported_at, self._tz).strftime("%d %b %H:%M %Z")
        via = f" · via mesh ({t.max_hops} hops)" if t.max_hops is not None else ""
        lines = [
            f"[P{tier}] {t.category.replace('_', ' ')}: {what} (ticket #{t.id})",
            f"WHO: {who}",
            f"WHERE: {where}",
        ]
        if t.summary:
            lines.append(f"REPORTED: {t.summary}")
        lines.append(f"REPORTS: {t.report_count} · first {first}{via}")
        return "\n".join(lines)

    # --- enrichment (Gemini) ----------------------------------------------

    def enrich(self, ticket_id: int) -> Ticket | None:
        ticket = self.store.get_ticket(ticket_id)
        if ticket is None or self.llm.name == "none" or ticket.status == "RESOLVED":
            return ticket
        now = self.clock()
        review = self.llm.generate(self._priority_prompt(ticket, now), PriorityReview)
        draft = self.llm.generate(self._dispatch_prompt(ticket, now), DispatchDraft)
        if review is None and draft is None:
            return ticket
        with self._lock:
            current = self.store.get_ticket(ticket_id)
            if current is None:
                return None
            update: dict = {"enriched_by": "gemini"}
            if review is not None:
                update["tier_adjust"] = max(-1, min(1, review.tier_adjust))
                update["priority_reason"] = review.reason.strip()[:300]
            else:
                update["priority_reason"] = priority.explain(current, now)
            if draft is not None and draft.english.strip():
                drafts = {"en": draft.english.strip()[:1500]}
                lang = current.primary_lang
                if lang in LANG_NAMES and lang != "en" and draft.local.strip():
                    drafts[lang] = draft.local.strip()[:1500]
                update["dispatch_draft"] = drafts
            else:
                update["dispatch_draft"] = {"en": self._rules_dispatch(current, now)}
            return self.store.save_ticket(current.model_copy(update=update))

    def _ticket_facts(self, t: Ticket, now: float) -> str:
        location = (
            f"{t.lat:.5f}, {t.lon:.5f} (accuracy ±{t.loc_acc_m} m)" if t.lat is not None else "no GPS fix"
        )
        summaries = "\n".join(f"<report>{r.summary}</report>" for r in t.reports[:10] if r.summary)
        return (
            f"Ticket #{t.id}\n"
            f"Category: {t.category}\nItem: {t.item or 'unspecified'}\n"
            f"Urgency reported by the phone (0-3): {t.urgency}\n"
            f"People affected: {t.people or 'unknown'}\nVulnerabilities: {', '.join(t.flags) or 'none stated'}\n"
            f"Location: {location}\nLandmark: {t.landmark or 'none'}\n"
            f"Independent reports: {t.report_count}\n"
            f"Waiting: {priority.waiting_hours(t, now):.1f} hours\n"
            f"Report summaries:\n{summaries or '(none)'}\n"
        )

    def _priority_prompt(self, t: Ticket, now: float) -> str:
        base_tier = priority.tier_for(priority.score(t, now))
        return (
            self._ticket_facts(t, now)
            + f"\nA rule-based score put this ticket in priority tier {base_tier} "
            "(tier 1 = most urgent, tier 4 = least).\n"
            "Decide tier_adjust: +1 to make it one tier MORE urgent, -1 for one tier LESS urgent, "
            "0 to keep it. Adjust only for a concrete reason in the facts above, e.g. an "
            "insulin-dependent person without insulin, or someone trapped by rising water. "
            "reason: one sentence of at most 25 words for the control-room operator, stating "
            "the decisive facts."
        )

    def _dispatch_prompt(self, t: Ticket, now: float) -> str:
        lang = t.primary_lang
        local = LANG_NAMES.get(lang) if lang not in ("en", "und") else None
        tier = priority.final_tier(t, now)
        return (
            self._ticket_facts(t, now)
            + f"Priority tier: P{tier}\n\n"
            "Write a dispatch message for the District Emergency Operations Centre. "
            "Plain text, at most 6 short lines, in this order: WHAT, WHO, WHERE (copy the coordinates "
            "and landmark exactly), PRIORITY, REPORTS, SUGGESTED ACTION. Put it in 'english'. "
            + (
                f"Put the same message translated into {local} in 'local', keeping coordinates and "
                "numbers unchanged."
                if local
                else "Set 'local' to an empty string."
            )
        )

    # --- operator actions --------------------------------------------------

    def set_status(
        self, ticket_id: int, status: AckStatus, eta_min: int | None = None, note: str = ""
    ) -> tuple[Ticket, int]:
        """Apply an operator action and confirm it to every reporter. Returns (ticket, acks queued)."""
        if status not in _STATUS_FOR_ACK:
            raise ValueError(f"operators cannot set {status.name}")
        with self._lock:
            ticket = self.store.get_ticket(ticket_id)
            if ticket is None:
                raise KeyError(ticket_id)
            ticket = self.store.save_ticket(
                ticket.model_copy(
                    update={
                        "status": _STATUS_FOR_ACK[status],
                        "eta_min": eta_min if eta_min is not None else ticket.eta_min,
                        "operator_note": note.strip() or ticket.operator_note,
                    }
                )
            )
        # One ACK per reporting phone, answering that phone's latest report.
        latest_by_origin: dict[str, Report] = {}
        for report in ticket.reports:
            latest_by_origin[report.origin_node] = report
        translations: dict[str, str] = {}
        for report in latest_by_origin.values():
            extra = ""
            if note.strip():
                if report.lang not in translations:
                    translations[report.lang] = self._translate(note.strip(), report.lang)
                extra = translations[report.lang]
            self._queue_ack(ticket, report, status, extra)
        return ticket, len(latest_by_origin)

    def _translate(self, text: str, lang: str) -> str:
        if lang not in LANG_NAMES or lang == "en":
            return text
        result = self.llm.generate(
            f"Translate this short message from a disaster control room into {LANG_NAMES[lang]}. "
            "Keep numbers and place names unchanged. Keep it brief.\n"
            f"<message>{text}</message>",
            Translation,
        )
        # Falling back to English beats sending nothing: someone nearby may read it.
        return result.text.strip() if result and result.text.strip() else text

    def _queue_ack(self, ticket: Ticket, report: Report, status: AckStatus, extra: str = "") -> int:
        ack = AckPacket(
            ref_msg_id=bytes.fromhex(report.msg_id),
            target_node=int(report.origin_node, 16),
            ticket_no=ticket.id,
            status=status,
            created_at=int(self.clock()),
            text=ack_text(report.lang, status, ticket.id, extra),
            eta_min=ticket.eta_min or 0,
        )
        return self.store.queue_ack(encode_ack(ack), report.msg_id, report.origin_node, ticket.id, int(status))
