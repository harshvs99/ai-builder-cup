"""Districts the control room serves, and which one a ticket or team belongs to.

The backend stores no district field. Like the dashboard (``dashboard/src/lib/districts.ts``, keep
the two in sync), a point belongs to the district whose centre is nearest. Each district has one
native language (``native_lang``). Dispatch orders and sitreps are written in English plus the
district's ``local_lang``: its native language when that language is enabled (``languages``), else
Hindi, the control room's second language.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Any

from . import languages


@dataclass(frozen=True)
class District:
    id: str
    name: str
    lat: float
    lon: float
    native_lang: str  # LANG_CODES tag

    @property
    def local_lang(self) -> str:
        return self.native_lang if languages.is_enabled(self.native_lang) else languages.CONTROL_ROOM_SECOND


DISTRICTS: tuple[District, ...] = (
    District("puri", "Puri", 19.81, 85.83, "or"),
    District("delhi", "Delhi", 28.614, 77.209, "hi"),
)
DEFAULT_DISTRICT = DISTRICTS[0]


def by_id(district_id: str) -> District | None:
    return next((d for d in DISTRICTS if d.id == district_id), None)


def nearest(lat: float, lon: float) -> District:
    # equirectangular is plenty to rank centres that are hundreds of km apart
    def score(d: District) -> float:
        dx = (lon - d.lon) * math.cos(math.radians(lat))
        dy = lat - d.lat
        return dx * dx + dy * dy

    return min(DISTRICTS, key=score)


def ticket_district(t: dict[str, Any]) -> District | None:
    """District of a ticket JSON from its GPS fix, else its estimated ``geo``; None when it has neither."""
    if t.get("has_location") and t.get("lat") is not None and t.get("lon") is not None:
        return nearest(float(t["lat"]), float(t["lon"]))
    geo = t.get("geo")
    if geo and geo.get("lat") is not None and geo.get("lon") is not None and (geo["lat"], geo["lon"]) != (0, 0):
        return nearest(float(geo["lat"]), float(geo["lon"]))
    return None


def team_district(team: dict[str, Any]) -> District:
    return nearest(float(team["base_lat"]), float(team["base_lon"]))


def ticket_in(t: dict[str, Any], district: District) -> bool:
    """A ticket with no location at all counts in every district: hiding a request we cannot place
    would be the worse failure (same rule as the dashboard)."""
    d = ticket_district(t)
    return d is None or d.id == district.id
