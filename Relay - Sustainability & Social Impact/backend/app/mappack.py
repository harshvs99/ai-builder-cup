"""Offline map packs for phones: the shelters, hospitals and rivers around a position.

A phone asks for one in normal times (``GET /v1/mappack``) and keeps it, so that if a disaster later hits the same area
it already holds a map and needs no network to say "the nearest cyclone shelter is 3 km north". Phones never ask during a
declared disaster: the network is for requests then.

Only places whose name says what they are are included, so nothing is guessed: "... cyclone shelter" is a shelter,
"... hospital" a hospital, "... river embankment" and beaches are water. Names in Devanagari and Odia script come from the
gazetteer aliases, so a Hindi or Odia speaker hears the place in their own script.
"""

from __future__ import annotations

import json
import logging
import math
import os
import re
from pathlib import Path
from typing import Any

from .geocode import DEFAULT_GAZETTEER_PATH

log = logging.getLogger("relay.mappack")

VERSION = 1
DEFAULT_RADIUS_KM = 30
MAX_RADIUS_KM = 100

KINDS = [
    ("shelter", re.compile(r"\bcyclone shelter\b|\bflood shelter\b|\brelief camp\b")),
    ("hospital", re.compile(r"\bhospital\b|\bphc\b|\bchc\b|\bhealth cent")),
    ("water", re.compile(r"\briver\b|\bembankment\b|\bcanal\b|\bbeach\b|\bswargadwar\b")),
]
_SCRIPTS = {"hi": re.compile(r"[ऀ-ॿ]"), "or": re.compile(r"[଀-୿]")}


def kind_of(entry: dict[str, Any]) -> str | None:
    text = " ".join([str(entry.get("name", "")), *map(str, entry.get("aliases") or [])]).lower()
    return next((k for k, rx in KINDS if rx.search(text)), None)


def _km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = p2 - p1, math.radians(lon2 - lon1)
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 6371.0 * 2 * math.asin(math.sqrt(min(1.0, h)))


def build(entries: list[dict[str, Any]], lat: float, lon: float, radius_km: int, now: int) -> dict[str, Any]:
    """The pack for a circle. Malformed entries are skipped."""
    places = []
    for e in entries:
        try:
            plat, plon, name = float(e["lat"]), float(e["lon"]), str(e["name"]).strip()
        except (KeyError, TypeError, ValueError):
            continue
        kind = kind_of(e)
        if not name or kind is None or _km(lat, lon, plat, plon) > radius_km:
            continue
        names: dict[str, str] = {}
        for alias in map(str, e.get("aliases") or []):
            for lang, rx in _SCRIPTS.items():
                if rx.search(alias) and len(alias) > len(names.get(lang, "")):
                    names[lang] = alias  # the longest: usually the one that also says what the place is
        places.append({
            "name": name, "kind": kind, "lat_e5": round(plat * 100000), "lon_e5": round(plon * 100000),
            **({"names": names} if names else {}),
        })
    return {
        "version": VERSION, "generated_at": now, "centre": {"lat": round(lat, 5), "lon": round(lon, 5)},
        "radius_km": radius_km, "places": places,
    }


_entries: list[dict[str, Any]] | None = None


def gazetteer_entries() -> list[dict[str, Any]]:
    """The raw gazetteer (``RELAY_GAZETTEER`` overrides the path), loaded once; [] if missing or broken."""
    global _entries
    if _entries is None:
        path = Path(os.environ.get("RELAY_GAZETTEER") or DEFAULT_GAZETTEER_PATH)
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            _entries = [e for e in data if isinstance(e, dict)] if isinstance(data, list) else []
        except (OSError, ValueError):
            log.warning("no usable gazetteer at %s; map packs will be empty", path)
            _entries = []
    return _entries
