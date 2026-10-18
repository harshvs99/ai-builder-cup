"""The districts Relay runs in: id, name, bounding box and centre. Pure data, no I/O.

Ids and names match the dashboard's ``src/lib/districts.ts``. The backend has no district field on a
ticket: a position belongs to the nearest centre (``nearest_district``), and a position is *plausible*
for a district only inside its bounding box (``district_containing``), which is what the landmark
geocoder validates against. Bounding boxes carry a margin around the district.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

# (south, west, north, east) and (lat, lon)
BBox = tuple[float, float, float, float]
LatLon = tuple[float, float]

PURI_BBOX: BBox = (19.45, 85.05, 20.35, 86.30)
PURI_CENTRE: LatLon = (19.8133, 85.8312)


@dataclass(frozen=True)
class District:
    id: str
    name: str
    bbox: BBox
    centre: LatLon

    def contains(self, lat: float, lon: float) -> bool:
        s, w, n, e = self.bbox
        return math.isfinite(lat) and math.isfinite(lon) and s <= lat <= n and w <= lon <= e


DISTRICTS: tuple[District, ...] = (
    District("puri", "Puri", PURI_BBOX, PURI_CENTRE),
    District("delhi", "Delhi", (28.40, 76.83, 28.89, 77.35), (28.614, 77.209)),
)


def district_by_id(district_id: str) -> District | None:
    return next((d for d in DISTRICTS if d.id == district_id), None)


def nearest_district(lat: float, lon: float) -> District:
    """The district whose centre is closest (equirectangular: plenty to rank centres hundreds of km apart)."""
    def score(d: District) -> float:
        dx = (lon - d.centre[1]) * math.cos(math.radians(lat))
        dy = lat - d.centre[0]
        return dx * dx + dy * dy

    return min(DISTRICTS, key=score)


def district_containing(lat: float, lon: float) -> District | None:
    """The district whose bounding box holds the point, or None (including for a non-finite point)."""
    return next((d for d in DISTRICTS if d.contains(lat, lon)), None)
