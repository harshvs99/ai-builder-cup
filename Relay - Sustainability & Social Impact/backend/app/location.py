"""Location quality, derived from the linked packets' ``loc_acc`` (SPEC §5.1).

``loc_acc``: 0 = no fix, 1 <10 m, 2 <50 m, 3 <200 m, 4 <1 km, 5 >=1 km. A ticket has no stored
location-quality column; it is always computed from its packets, so rows written before this
module existed render correctly with no migration.
"""

from __future__ import annotations

import unicodedata
from typing import Any, Iterable

LOC_ACC_LABELS: tuple[str | None, ...] = (None, "<10 m", "<50 m", "<200 m", "<1 km", "≥1 km")


def best_loc_acc(accs: Iterable[int | None]) -> int:
    """Best (lowest non-zero) accuracy bucket; 0 only if no packet had a fix."""
    fixes = [a for a in accs if a]
    return min(fixes) if fixes else 0


def loc_acc_label(acc: int) -> str | None:
    return LOC_ACC_LABELS[acc] if 0 <= acc < len(LOC_ACC_LABELS) else None


def ticket_location(row: dict[str, Any], links: list[dict[str, Any]]) -> dict[str, Any]:
    """``has_location``, ``loc_acc``, ``loc_acc_m``, ``lat``, ``lon`` for a ticket JSON.

    ``links`` are the ticket's packets (each with ``loc_acc``). If none carries accuracy
    information (should not happen), fall back to the coordinates: all-zero means no fix,
    anything else is a fix of unknown quality, reported conservatively as bucket 5.
    """
    known = [lk["loc_acc"] for lk in links if lk.get("loc_acc") is not None]
    if known:
        acc = best_loc_acc(known)
    else:
        acc = 0 if (row["lat_e5"] == 0 and row["lon_e5"] == 0) else 5
    has = acc != 0
    return {
        "lat": round(row["lat_e5"] / 1e5, 5) if has else None,
        "lon": round(row["lon_e5"] / 1e5, 5) if has else None,
        "has_location": has,
        "loc_acc": acc,
        "loc_acc_m": loc_acc_label(acc),
    }


def no_fix_note(landmark: str) -> str:
    return "no GPS fix — landmark only" if landmark else "no GPS fix — location unknown"


def add_no_fix_note(reason: str, landmark: str) -> str:
    """Priority reason plus the no-fix caveat (idempotent)."""
    return reason if "no GPS fix" in reason else f"{reason} Note: {no_fix_note(landmark)}."


def scrub_legacy_draft(text: str, landmark: str) -> str:
    """Drafts stored before this change print a no-fix ticket's 0,0 placeholder as a location;
    rewrite them on the way out (no data migration)."""
    zero = "Location: 0.00000, 0.00000"
    if landmark:
        text = text.replace(f"{zero} near {landmark}", f"Location: unknown (no GPS fix) — near {landmark}")
    return text.replace(zero, "Location: unknown (no GPS fix)")


def tokenize(text: str) -> list[str]:
    """Lowercase word tokens for Latin, Devanagari and Odia text.

    Splits on punctuation, symbols and whitespace only. ``\\w`` is not used because it treats
    Indic vowel signs (combining marks) as separators and would shred "पानी" into fragments.
    """
    out: list[str] = []
    cur: list[str] = []
    for ch in unicodedata.normalize("NFKC", text).casefold():
        if unicodedata.category(ch)[0] in "PSZC":
            if cur:
                out.append("".join(cur))
                cur = []
        else:
            cur.append(ch)
    if cur:
        out.append("".join(cur))
    return out
