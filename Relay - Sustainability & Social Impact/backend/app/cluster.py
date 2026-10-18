"""Near-duplicate clustering of NEEDs into tickets.

A new NEED joins an existing OPEN ticket iff ALL hold:
  * same category
  * haversine distance to the ticket's canonical location <= 200 m
  * its (clock-sanitised) report time is within 6 h of the ticket's first report
  * items match: normalised-lowercase equal, or either side empty, or (when both items are non-empty
    and both have an embedding) the cosine similarity of ``"{category}: {item}"`` embeddings is
    >= ``embed.SIMILARITY_THRESHOLD`` ("insulin" ~ "इंसुलिन"). The embedding only replaces the
    item-text test, never the other gates. A ticket without a stored ``item_vec``, or an incoming need
    without a vector (no embedder, timeout, error), is judged by the text rule alone.

Decision (not in the brief): a NEED with loc_acc == 0 ("no fix") never merges, because its
coordinates are meaningless (phones send 0,0) and would otherwise glue unrelated reports
together.

The ticket's canonical location is the first report's; merges never move it, so a chain of
reports cannot drift the cluster across the map.
"""

from __future__ import annotations

import math
import re
from typing import Any

from .codec import CATEGORY_NAMES, LANG_CODES, Need, answers_to_json
from .embed import SIMILARITY_THRESHOLD, cosine, decode_vec, encode_vec

MAX_DISTANCE_M = 200.0
MAX_AGE_WINDOW_S = 6 * 3600
EARTH_RADIUS_M = 6_371_000.0


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dphi = p2 - p1
    dlmb = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dlmb / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(min(1.0, math.sqrt(a)))


def normalize_item(s: str) -> str:
    return re.sub(r"\s+", " ", s.strip()).casefold()


def items_match(a: str, b: str) -> bool:
    na, nb = normalize_item(a), normalize_item(b)
    return not na or not nb or na == nb


def item_reason(
    ticket: dict[str, Any], need: Need, need_vec: list[float] | None, threshold: float
) -> str | None:
    """Why the ticket's item counts as the need's item (``merge_reason``), or None. Pure, no I/O."""
    if items_match(ticket["item"], need.item):
        return "item match"
    if need_vec is not None and normalize_item(ticket["item"]) and normalize_item(need.item):
        tv = decode_vec(ticket.get("item_vec"))
        if tv is not None:
            sim = cosine(need_vec, tv)
            if sim >= threshold:
                return f"similar item ({sim:.2f})"
    return None


def find_match_reason(
    candidates: list[dict[str, Any]], need: Need, need_vec: list[float] | None = None,
    threshold: float = SIMILARITY_THRESHOLD,
) -> tuple[dict[str, Any], str] | None:
    """Nearest matching ticket among OPEN, same-category, in-window candidates, with the reason."""
    if need.loc_acc == 0:
        return None
    best: tuple[float, int, dict[str, Any], str] | None = None
    for t in candidates:
        if t["lat_e5"] == 0 and t["lon_e5"] == 0:
            continue  # a no-fix ticket (phones send 0,0): its coordinates mean nothing
        reason = item_reason(t, need, need_vec, threshold)
        if reason is None:
            continue
        d = haversine_m(need.lat, need.lon, t["lat_e5"] / 1e5, t["lon_e5"] / 1e5)
        if d <= MAX_DISTANCE_M and (best is None or (d, t["ticket_no"]) < best[:2]):
            best = (d, t["ticket_no"], t, reason)
    return (best[2], best[3]) if best else None


def find_match(
    candidates: list[dict[str, Any]], need: Need, need_vec: list[float] | None = None,
    threshold: float = SIMILARITY_THRESHOLD,
) -> dict[str, Any] | None:
    m = find_match_reason(candidates, need, need_vec, threshold)
    return m[0] if m else None


def new_ticket_fields(
    need: Need, hops: int, reported_at: int, now: int, item_vec: list[float] | None = None
) -> dict[str, Any]:
    fields = {
        "category": CATEGORY_NAMES[need.category],
        "item": need.item,
        "landmark": need.landmark,
        "summary": need.summary,
        "lat_e5": need.lat_e5,
        "lon_e5": need.lon_e5,
        "urgency": need.urgency,
        "people": need.people,
        "vuln_flags": need.vuln_flags,
        "lang": LANG_CODES[need.lang],
        "report_count": 1,
        "first_reported_at": reported_at,
        "max_hops": hops,
        "created_at": now,
        "updated_at": now,
    }
    if need.answers:
        fields["answers"] = answers_to_json(need.answers)
    if item_vec is not None and need.item.strip() and (enc := encode_vec(item_vec)):
        fields["item_vec"] = enc
    return fields


def merge_fields(
    ticket: dict[str, Any], need: Need, hops: int, reported_at: int, now: int, item_vec: list[float] | None = None
) -> dict[str, Any]:
    """Field updates for merging ``need`` into ``ticket``: count+1, max urgency/people/hops,
    OR of flags, earliest first report. Empty text fields are back-filled; the summary follows
    the most urgent report."""
    upd: dict[str, Any] = {
        "report_count": ticket["report_count"] + 1,
        "urgency": max(ticket["urgency"], need.urgency),
        "people": max(ticket["people"], need.people),
        "vuln_flags": ticket["vuln_flags"] | need.vuln_flags,
        "max_hops": max(ticket["max_hops"], hops),
        "first_reported_at": min(ticket["first_reported_at"], reported_at),
        "updated_at": now,
    }
    if not ticket["item"] and need.item:
        upd["item"] = need.item
    # canonical item vector: set when missing and this report's item is (now) the ticket's item
    if (
        item_vec is not None and not ticket.get("item_vec") and need.item.strip()
        and (not ticket["item"] or normalize_item(ticket["item"]) == normalize_item(need.item))
        and (enc := encode_vec(item_vec))
    ):
        upd["item_vec"] = enc
    if not ticket["landmark"] and need.landmark:
        upd["landmark"] = need.landmark
    if need.urgency > ticket["urgency"] and need.summary:
        upd["summary"] = need.summary
    elif not ticket["summary"] and need.summary:
        upd["summary"] = need.summary
    # intake answers: the first report's stand; a later report only fills questions the ticket lacks
    have = ticket.get("answers") or {}
    extra = {k: v for k, v in answers_to_json(need.answers).items() if k not in have}
    if extra:
        upd["answers"] = {**have, **extra}
    return upd
