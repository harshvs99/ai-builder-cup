"""Approximate position of a requester with no GPS fix, from the LOC_HINT frames of nearby phones.

A hint (protocol/SPEC.md 5.5) says: this relay, at this position with this accuracy, received the NEED directly from
its requester. So the requester was within radio range of the relay. We take that as a disc around the relay:

    radius = the relay's own accuracy bound + RADIO_RANGE_M (100 m)

and the requester is somewhere in the **intersection of the discs**. This is deliberately not RSSI trilateration:
signal strength indoors and across a village is far too noisy to turn into a distance. RSSI is used only as a weak
weight (at most a factor of two) when picking a point inside the intersection.

``estimate`` returns the ticket's ``geo`` record::

    {"lat", "lon", "source": "mesh", "radius_m": <multiple of 10>, "hint_count": <hints used>}

Hints are added most accurate first; a hint whose disc would empty the intersection (a forged or wrong position, or a
relay that is really not close) is left out and not counted. Pure functions, no I/O.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Any, Iterable

from .codec import HINT_ACC_MAX_M

RADIO_RANGE_M = 100.0  # how far from the relay the requester may be, on top of the relay's own accuracy
MAX_HINTS = 12  # the most accurate ones are used
_EARTH_M = 6_371_000.0
_EPS_M = 0.01  # tolerance for "inside a disc"
_GRID_CELLS = 40  # grid cells across the smallest disc's radius
_MIN_STEP_M = 2.0
_RSSI_STRONG, _RSSI_WEAK = -40, -100


@dataclass(frozen=True)
class HintView:
    """What the estimator needs from a stored hint."""
    lat: float
    lon: float
    accuracy_max_m: int
    rssi_dbm: int

    @classmethod
    def from_row(cls, row: dict[str, Any]) -> "HintView":
        return cls(row["lat_e5"] / 1e5, row["lon_e5"] / 1e5, HINT_ACC_MAX_M[row["acc"]], row["rssi_dbm"])


@dataclass(frozen=True)
class Disc:
    x: float  # metres east of the projection origin
    y: float  # metres north
    r: float
    w: float  # 0.5 (weak/unknown link) .. 1.0 (strong link): the only place RSSI is used


def rssi_weight(rssi_dbm: int | None) -> float:
    """0.5 for a weak link, 1.0 for a strong one, 0.75 when unknown (-128): never more than a 2x difference."""
    if rssi_dbm is None or rssi_dbm <= -128 or rssi_dbm >= 0:
        return 0.75
    strength = min(1.0, max(0.0, (rssi_dbm - _RSSI_WEAK) / (_RSSI_STRONG - _RSSI_WEAK)))
    return 0.5 + 0.5 * strength


def _project(lat: float, lon: float, lat0: float, lon0: float) -> tuple[float, float]:
    return (
        math.radians(lon - lon0) * _EARTH_M * math.cos(math.radians(lat0)),
        math.radians(lat - lat0) * _EARTH_M,
    )


def _unproject(x: float, y: float, lat0: float, lon0: float) -> tuple[float, float]:
    return (
        lat0 + math.degrees(y / _EARTH_M),
        lon0 + math.degrees(x / (_EARTH_M * math.cos(math.radians(lat0)))),
    )


def _inside(px: float, py: float, d: Disc, slack: float = _EPS_M) -> bool:
    return math.hypot(px - d.x, py - d.y) <= d.r + slack


def _circle_points(a: Disc, b: Disc) -> list[tuple[float, float]]:
    """Intersection points of the two boundary circles (0 or 2; tangent counts as 2 equal points)."""
    dx, dy = b.x - a.x, b.y - a.y
    dist = math.hypot(dx, dy)
    if dist < 1e-9 or dist > a.r + b.r + _EPS_M or dist < abs(a.r - b.r) - _EPS_M:
        return []
    along = (a.r * a.r - b.r * b.r + dist * dist) / (2 * dist)
    h = math.sqrt(max(0.0, a.r * a.r - along * along))
    mx, my = a.x + along * dx / dist, a.y + along * dy / dist
    return [(mx + h * dy / dist, my - h * dx / dist), (mx - h * dy / dist, my + h * dx / dist)]


def _vertices(discs: list[Disc]) -> list[tuple[float, float]]:
    """Points that are in every disc and are either a disc centre or a boundary crossing. The intersection of discs is
    convex, so it is non-empty iff this list is non-empty (its corners, or one disc lying inside all the others)."""
    cands = [(d.x, d.y) for d in discs]
    for i, a in enumerate(discs):
        for b in discs[i + 1:]:
            cands.extend(_circle_points(a, b))
    return [p for p in cands if all(_inside(p[0], p[1], d) for d in discs)]


def _region(discs: list[Disc]) -> tuple[list[tuple[float, float]], float]:
    """Sample points of the intersection (grid inside the smallest disc plus the exact corners) and the grid step."""
    smallest = min(discs, key=lambda d: d.r)
    step = max(_MIN_STEP_M, smallest.r / _GRID_CELLS)
    pts = list(_vertices(discs))
    n = int(smallest.r // step) + 1
    for i in range(-n, n + 1):
        for j in range(-n, n + 1):
            px, py = smallest.x + i * step, smallest.y + j * step
            if all(_inside(px, py, d) for d in discs):
                pts.append((px, py))
    return pts, step


def _largest_agreeing(discs: list[Disc]) -> list[Disc]:
    """The biggest group of discs (given most accurate first) that still share a point. Each disc in turn seeds a group
    that then takes every later-listed disc that fits, so one wrong hint, however accurate it claims to be, cannot
    shut out several that agree with each other. Ties go to the group of smaller discs, then to the earlier seed."""
    best: list[Disc] = []
    for i, seed in enumerate(discs):
        group = [seed]
        for d in discs[:i] + discs[i + 1:]:
            if _vertices(group + [d]):
                group.append(d)
        if len(group) > len(best) or (
            len(group) == len(best) and sum(d.r for d in group) < sum(d.r for d in best) - 1e-9
        ):
            best = group
    return best


def estimate(hints: Iterable[Any]) -> dict[str, Any] | None:
    """``geo`` record from hints (anything with ``lat``, ``lon``, ``accuracy_max_m``, ``rssi_dbm``), or None."""
    pool = sorted(
        hints, key=lambda h: (h.accuracy_max_m, -rssi_weight(h.rssi_dbm), h.lat, h.lon)
    )[:MAX_HINTS]
    if not pool:
        return None
    lat0, lon0 = pool[0].lat, pool[0].lon
    discs = []
    for h in pool:
        x, y = _project(h.lat, h.lon, lat0, lon0)
        discs.append(Disc(x, y, h.accuracy_max_m + RADIO_RANGE_M, rssi_weight(h.rssi_dbm)))
    used = _largest_agreeing(discs)
    if len(used) == 1:
        d = used[0]
        lat, lon = _unproject(d.x, d.y, lat0, lon0)
        return _record(lat, lon, d.r, 1)
    pts, step = _region(used)
    # weight each sample by how close it is to the relays, stronger links counting up to twice as much
    wsum = sx = sy = 0.0
    for px, py in pts:
        w = 1e-9 + sum(
            d.w * max(0.0, 1.0 - math.hypot(px - d.x, py - d.y) / d.r) for d in used
        )
        wsum += w
        sx += w * px
        sy += w * py
    cx, cy = sx / wsum, sy / wsum
    reach = max(math.hypot(px - cx, py - cy) for px, py in pts) + step / 2
    lat, lon = _unproject(cx, cy, lat0, lon0)
    return _record(lat, lon, reach, len(used))


def _record(lat: float, lon: float, radius_m: float, count: int) -> dict[str, Any]:
    return {
        "lat": round(lat, 5), "lon": round(lon, 5), "source": "mesh",
        "radius_m": int(math.ceil(radius_m / 10.0 - 1e-9) * 10), "hint_count": count,
    }


def distance_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Metres between two points (small distances; used by tests and callers that compare estimates)."""
    x, y = _project(lat2, lon2, lat1, lon1)
    return math.hypot(x, y)
