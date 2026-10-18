"""Deterministic priority scoring.

score = category_weight + 1.5*urgency + 1.0*n_vuln_flags + log2(1+people)
        + 1.5*log2(report_count) + 0.25*min(hours_waiting, 24)

rounded half-up to one decimal. Tier bands are applied to the rounded score:
>=12 P1, >=8 P2, >=5 P3, else P4. The score is never changed by the LLM; the LLM may only
shift the tier by one step (see agent.py).
"""

from __future__ import annotations

import math
from dataclasses import dataclass

from .codec import PHOTO_TAG_NAMES, VULN_NAMES

CATEGORY_WEIGHTS = {
    "RESCUE": 5.0,
    "MEDICAL": 5.0,
    "WATER": 3.0,
    "MISSING_PERSON": 3.0,
    "SHELTER": 2.0,
    "FOOD": 2.0,
    "OTHER": 1.0,
}
URGENCY_LABELS = ("routine", "moderate", "high", "life-threatening")
TIERS = ("P1", "P2", "P3", "P4")


@dataclass(frozen=True)
class Score:
    score: float
    tier: str
    reason: str
    factors: tuple[tuple[str, float], ...]


def round1(x: float) -> float:
    """Round half up to one decimal (deterministic, unlike banker's rounding)."""
    return math.floor(x * 10 + 0.5) / 10


def tier_for(score: float) -> str:
    if score >= 12:
        return "P1"
    if score >= 8:
        return "P2"
    if score >= 5:
        return "P3"
    return "P4"


def shift_tier(tier: str, delta: int) -> str:
    """Move ``delta`` steps (negative = more urgent), clamped to P1..P4."""
    idx = TIERS.index(tier) + delta
    return TIERS[max(0, min(len(TIERS) - 1, idx))]


def photo_note(photo_tags: dict[str, int] | None) -> str:
    """"Photo shows: people stranded, floodwater." from a ticket's tag counts, most-reported first (bit order breaks
    ties). A note on the reason only: tags are what a phone's classifier saw, so they never move the score."""
    if not photo_tags:
        return ""
    order = {k: i for i, k in enumerate(PHOTO_TAG_NAMES)}
    keys = sorted(photo_tags, key=lambda k: (-photo_tags[k], order.get(k, len(order))))[:4]
    return "Photo shows: " + ", ".join(k.replace("_", " ") for k in keys) + "."


def add_photo_note(reason: str, photo_tags: dict[str, int] | None) -> str:
    """Priority reason plus the photo note (idempotent)."""
    note = photo_note(photo_tags)
    return reason if not note or "Photo shows:" in reason else f"{reason} {note}"


def vuln_names(flags: int) -> list[str]:
    return [name for bit, name in enumerate(VULN_NAMES) if flags & (1 << bit)]


def compute(
    category: str,
    urgency: int,
    people: int,
    vuln_flags: int,
    report_count: int,
    hours_waiting: float,
) -> Score:
    report_count = max(1, report_count)
    vulns = vuln_names(vuln_flags)
    factors: list[tuple[str, float]] = [
        (f"category {category}", CATEGORY_WEIGHTS.get(category, 1.0)),
        (f"urgency {urgency}/3 ({URGENCY_LABELS[max(0, min(3, urgency))]})", 1.5 * urgency),
        (
            "vulnerable (" + ", ".join(v.lower() for v in vulns) + ")" if vulns else "no vulnerability flags",
            1.0 * len(vulns),
        ),
        (
            f"{people} {'person' if people == 1 else 'people'}" if people else "unknown number of people",
            math.log2(1 + people),
        ),
        (
            f"{report_count} independent {'report' if report_count == 1 else 'reports'}",
            1.5 * math.log2(report_count),
        ),
        (f"waiting {max(0.0, hours_waiting):.1f} h", 0.25 * min(max(0.0, hours_waiting), 24.0)),
    ]
    total = round1(sum(p for _, p in factors))
    tier = tier_for(total)
    top = sorted((f for f in factors if f[1] > 0), key=lambda f: -f[1])[:3]  # stable: ties keep order
    parts = "; ".join(f"{label} +{points:.1f}" for label, points in top)
    reason = f"{tier} (score {total:.1f}): {parts}." if parts else f"{tier} (score {total:.1f})."
    return Score(total, tier, reason, tuple(factors))
