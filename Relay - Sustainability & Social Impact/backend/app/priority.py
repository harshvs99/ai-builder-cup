"""Deterministic priority scoring.

The score is computed here, in code, so that it is stable and explainable.
Gemini may only nudge the resulting tier by one step (``Ticket.tier_adjust``).
"""

from __future__ import annotations

import math

from .models import Ticket

CATEGORY_WEIGHT = {
    "RESCUE": 40,
    "MEDICAL": 35,
    "MISSING_PERSON": 30,
    "WATER": 25,
    "SHELTER": 18,
    "FOOD": 15,
    "OTHER": 10,
}

FLAG_WEIGHT = {
    "TRAPPED": 15,
    "INJURED": 10,
    "PREGNANT": 8,
    "CHILD": 6,
    "ELDERLY": 6,
    "DISABLED": 6,
}

URGENCY_WEIGHT = 8
# Tier 1 is the most urgent.
TIER_THRESHOLDS = ((1, 75.0), (2, 55.0), (3, 35.0))
MAX_WAITING_BONUS = 24.0


def base_score(category: str, urgency: int, flags: list[str], people: int, report_count: int) -> float:
    score = float(CATEGORY_WEIGHT.get(category, CATEGORY_WEIGHT["OTHER"]))
    score += URGENCY_WEIGHT * max(0, min(urgency, 3))
    score += sum(FLAG_WEIGHT.get(f, 0) for f in flags)
    score += min(6 * math.log2(1 + max(people, 0)), 24)
    score += 5 * math.log2(max(report_count, 1))
    return score


def waiting_hours(ticket: Ticket, now: float) -> float:
    return max(0.0, (now - ticket.first_reported_at) / 3600)


def score(ticket: Ticket, now: float) -> float:
    waiting_bonus = min(2 * waiting_hours(ticket, now), MAX_WAITING_BONUS)
    return round(
        base_score(ticket.category, ticket.urgency, ticket.flags, ticket.people, ticket.report_count)
        + waiting_bonus,
        1,
    )


def tier_for(value: float) -> int:
    for tier, threshold in TIER_THRESHOLDS:
        if value >= threshold:
            return tier
    return 4


def final_tier(ticket: Ticket, now: float) -> int:
    return max(1, min(4, tier_for(score(ticket, now)) - ticket.tier_adjust))


def explain(ticket: Ticket, now: float) -> str:
    parts = [ticket.category.replace("_", " ").lower()]
    if ticket.item:
        parts[0] += f" ({ticket.item})"
    parts.append(f"urgency {ticket.urgency}/3")
    if ticket.flags:
        parts.append(", ".join(f.lower() for f in ticket.flags))
    if ticket.people > 1:
        parts.append(f"{ticket.people} people")
    if ticket.report_count > 1:
        parts.append(f"{ticket.report_count} reports")
    hours = waiting_hours(ticket, now)
    if hours >= 0.1:
        parts.append(f"waiting {hours:.1f} h")
    return "; ".join(parts).capitalize()


def sort_key(ticket: Ticket, now: float) -> tuple:
    return (final_tier(ticket, now), -score(ticket, now), ticket.first_reported_at)
