"""Recover mode: after the towers return, every ticket and dispatch becomes evidence.

Three read-only products built from the store, none of them persisted:

  * a **damage map** by village (``damage_map``): tickets grouped into areas, with households reached, duplicate
    reports merged before dispatch, response times and a damage level. Deterministic, no model.
  * an **evidence pack** per ticket, or per area (``ticket_evidence``, ``area_evidence``): a self-contained record of
    the ticket, where it was, who reported it (as pseudonyms), what happened when, and what the phones saw.
  * a **relief and insurer report** (``write_report``): the damage map's figures, with English and Odia / Hindi prose
    around them. Gemini writes the prose; text is used only if every number in it is one it was handed (the sitrep
    rule), otherwise the deterministic template.

Areas. A ticket's position is its GPS fix, else its approximate ``geo`` (landmark lookup or mesh estimate). The area is
the nearest gazetteer place within ``PLACE_RADIUS_M`` ("<name>, <block>"), else a grid cell of about ``GRID_M``
labelled by its district and nearest place, else "Area near lat, lon". No position at all is the "Unlocated" bucket:
counted everywhere, never dropped, and shown under every district filter. The district is the nearest centre
(``districts.nearest_district``).

Damage level (``damage_level``), the first rule that holds:
  severe    a collapsed house is reported, or >= 3 tickets report a damaged house, or >= 8 reports in all
  moderate  a damaged house is reported, or >= 4 reports, or a hazard tag (flood, road, landslide, fire, power line)
  light     at least one ticket
  none      no ticket
A "damaged house" is a ticket with a ``house_damaged`` / ``house_collapsed`` / ``roof_damaged`` photo tag or the intake
answer ``house_damaged = yes``. Photo tags are what a requester's phone saw: observations, not verified assessments.

Households. One origin node (phone) stands for one household; ``households`` counts distinct origin nodes on a
ticket's reports, ``households_reached`` those on RESOLVED tickets. Raw node ids never leave this module: evidence packs
carry a salted SHA-256 pseudonym per origin (data minimisation).

What the digest proves. Every evidence record carries ``integrity = {algorithm: "sha256", digest}``, the SHA-256 of its
canonical JSON (sorted keys, compact separators, UTF-8) without the ``integrity`` field. It gives **tamper-evidence of
the export**: anyone holding a record can recompute the digest and see that the record was not edited afterwards, and
an area pack's digest (over the list of its ticket digests) shows that no ticket was added, dropped or reordered. It
does **not** prove authenticity: nothing is signed, so it cannot show who produced the export or that the control
room's own data was true. Signing the digests (a key held by the control room) is future work.
"""

from __future__ import annotations

import hashlib
import json
import logging
import math
import re
import statistics
from dataclasses import dataclass
from typing import Any

from pydantic import BaseModel

from .cluster import haversine_m
from .districts import DISTRICTS, district_by_id, nearest_district
from .geocode import Gazetteer, Place, default_gazetteer
from .location import ticket_location
from .sitrep import numbers_in
from .store import Store
from .textgen import TextGen, call_json

log = logging.getLogger("relay.recover")

PLACE_RADIUS_M = 3000.0  # a ticket this close to a gazetteer place belongs to that village
GRID_M = 2000.0  # otherwise to a cell of about this size
GRID_NAME_RADIUS_M = 25_000.0  # a grid cell is named after the nearest place only if one is this close
UNLOCATED_ID = "unlocated"
MAX_TEXT_CHARS = 3500
MAX_AREAS_IN_TEXT = 8
EVIDENCE_SCHEMA = "relay.evidence.v1"
DEFAULT_SALT = "relay-evidence-v1"  # RELAY_EVIDENCE_SALT overrides; set your own in production

HOUSE_TAGS = ("house_damaged", "house_collapsed", "roof_damaged")
HAZARD_TAGS = ("floodwater", "road_blocked", "landslide", "fire", "power_line_down")
SEVERE_HOUSES = 3
SEVERE_REPORTS = 8
MODERATE_REPORTS = 4
LEVEL_ORDER = {"severe": 0, "moderate": 1, "light": 2, "none": 3}
LOCAL_LANG = {"puri": "or", "delhi": "hi"}

PHOTO_NOTE = "classified on the requester's phone; tags only, no image"


# --------------------------------------------------------------------------------------
# areas
# --------------------------------------------------------------------------------------

@dataclass(frozen=True)
class Area:
    area_id: str
    name: str
    district: str | None  # district id; None for the Unlocated bucket


UNLOCATED = Area(UNLOCATED_ID, "Unlocated", None)


def _slug(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")


def _title(name: str) -> str:
    return name.title() if name.islower() else name


def position(row: dict[str, Any], loc: dict[str, Any]) -> tuple[float, float, str] | None:
    """(lat, lon, "gps" | "geo") of a ticket: its GPS fix, else its approximate ``geo``; None when it has neither."""
    if loc["has_location"] and loc["lat"] is not None and loc["lon"] is not None:
        return float(loc["lat"]), float(loc["lon"]), "gps"
    g = row.get("geo")
    if g:
        try:
            lat, lon = float(g["lat"]), float(g["lon"])
        except (KeyError, TypeError, ValueError):
            return None
        if math.isfinite(lat) and math.isfinite(lon) and not (lat == 0 and lon == 0):
            return lat, lon, "geo"
    return None


def _nearest_place(lat: float, lon: float, gazetteer: Gazetteer) -> tuple[Place, float] | None:
    best: tuple[Place, float] | None = None
    for p in gazetteer.places():
        d = haversine_m(lat, lon, p.lat, p.lon)
        if best is None or d < best[1]:
            best = (p, d)
    return best


def assign_area(lat: float | None, lon: float | None, gazetteer: Gazetteer | None = None) -> Area:
    """The village a position belongs to (see the module docstring); ``None`` coordinates are the Unlocated bucket."""
    if lat is None or lon is None:
        return UNLOCATED
    gazetteer = gazetteer if gazetteer is not None else default_gazetteer()
    near = _nearest_place(lat, lon, gazetteer)
    if near is not None and near[1] <= PLACE_RADIUS_M:
        place = near[0]
        label = f"{_title(place.name)}, {place.block}" if place.block else _title(place.name)
        return Area(f"place:{_slug(place.name)}", label, nearest_district(place.lat, place.lon).id)
    # ~2 km cell: latitude rows are a fixed size, longitude columns shrink with the row's latitude
    step = GRID_M / 111_320.0
    i = math.floor(lat / step)
    clat = (i + 0.5) * step
    lstep = step / max(0.2, math.cos(math.radians(clat)))
    j = math.floor(lon / lstep)
    clon = (j + 0.5) * lstep
    district = nearest_district(clat, clon)
    cell_near = _nearest_place(clat, clon, gazetteer)
    if cell_near is not None and cell_near[1] <= GRID_NAME_RADIUS_M:
        name = f"{district.name}: near {_title(cell_near[0].name)}"
    else:
        name = f"{district.name}: area near {clat:.2f}, {clon:.2f}"
    return Area(f"grid:{i}:{j}", name, district.id)


# --------------------------------------------------------------------------------------
# cases: one ticket with everything the products need
# --------------------------------------------------------------------------------------

@dataclass
class Case:
    row: dict[str, Any]
    loc: dict[str, Any]  # location.ticket_location(): has_location, loc_acc, loc_acc_m, lat, lon
    packets: list[dict[str, Any]]
    dispatch: dict[str, Any] | None
    pos: tuple[float, float, str] | None
    area: Area


def make_case(
    row: dict[str, Any], packets: list[dict[str, Any]], record: dict[str, Any] | None, gazetteer: Gazetteer | None = None
) -> Case:
    loc = ticket_location(row, packets)
    pos = position(row, loc)
    area = assign_area(pos[0], pos[1], gazetteer) if pos else UNLOCATED
    return Case(row, loc, packets, (record or {}).get("dispatch"), pos, area)


def load_cases(store: Store, district: str = "all", gazetteer: Gazetteer | None = None) -> list[Case]:
    """Every ticket in the store (priority order), limited to a district; unlocated tickets are in every district."""
    records = store.dispatch_records()
    out: list[Case] = []
    for row, _links in store.list_tickets_with_links():
        no = row["ticket_no"]
        case = make_case(row, store.ticket_packets(no), records.get(no), gazetteer)
        if district == "all" or case.area.district in (None, district):
            out.append(case)
    return out


# --------------------------------------------------------------------------------------
# damage map
# --------------------------------------------------------------------------------------

def _tags(case: Case) -> dict[str, int]:
    return {k: int(v) for k, v in (case.row.get("photo_tags") or {}).items() if isinstance(v, int) and v > 0}


def _house_damaged(case: Case) -> bool:
    return any(t in _tags(case) for t in HOUSE_TAGS) or (case.row.get("answers") or {}).get("house_damaged") == "yes"


def _minutes_to_dispatch(case: Case) -> float | None:
    approved = (case.dispatch or {}).get("approved_at")
    if approved is None:
        return None
    return max(0.0, (approved - case.row["first_reported_at"]) / 60.0)


def metrics(cases: list[Case]) -> dict[str, Any]:
    """The figures shared by an area and the totals."""
    reports = sum(c.row["report_count"] for c in cases)
    status = [c.row["status"] for c in cases]
    resolved = [c for c in cases if c.row["status"] == "RESOLVED"]
    photo: dict[str, int] = {}
    by_category: dict[str, int] = {}
    for c in cases:
        by_category[c.row["category"]] = by_category.get(c.row["category"], 0) + 1
        for k, v in _tags(c).items():
            photo[k] = photo.get(k, 0) + v
    waits = [m for m in (_minutes_to_dispatch(c) for c in cases) if m is not None]
    located = sum(1 for c in cases if c.pos is not None)
    fixed = sum(1 for c in cases if c.pos is not None and c.pos[2] == "gps")
    n = len(cases)
    return {
        "tickets": n,
        "reports": reports,
        "duplicates_merged": reports - n,
        "households": len({p["origin_node"] for c in cases for p in c.packets}),
        "people": sum(c.row["people"] for c in cases),
        "households_reached": len({p["origin_node"] for c in resolved for p in c.packets}),
        "open": status.count("OPEN"),
        "dispatched": status.count("DISPATCHED") + status.count("ON_THE_WAY"),
        "resolved": len(resolved),
        "needs_confirmed_closed": len(resolved),
        "by_category": dict(sorted(by_category.items())),
        "photo_tags": dict(sorted(photo.items())),
        "houses_damaged": sum(1 for c in cases if _house_damaged(c)),
        "houses_collapsed": sum(1 for c in cases if "house_collapsed" in _tags(c)),
        "median_minutes_to_dispatch": round(statistics.median(waits), 1) if waits else None,
        # usable = a GPS fix or an approximate position (landmark / mesh); a fraction of tickets, 0 to 1
        "share_with_location": round(located / n, 3) if n else 0.0,
        "share_with_gps_fix": round(fixed / n, 3) if n else 0.0,
    }


def _plural(n: int, one: str, many: str) -> str:
    return f"{n} {one if n == 1 else many}"


def damage_level(m: dict[str, Any]) -> tuple[str, str]:
    """(level, one human-readable sentence of why) from an area's metrics; the rule is in the module docstring."""
    hazards = sorted(t for t in HAZARD_TAGS if m["photo_tags"].get(t))
    if m["tickets"] == 0:
        return "none", "No requests from this area."
    if m["houses_collapsed"] or m["houses_damaged"] >= SEVERE_HOUSES or m["reports"] >= SEVERE_REPORTS:
        level = "severe"
    elif m["houses_damaged"] or m["reports"] >= MODERATE_REPORTS or hazards:
        level = "moderate"
    else:
        level = "light"
    facts: list[str] = []
    if m["houses_collapsed"]:
        facts.append(_plural(m["houses_collapsed"], "request reports a collapsed house", "requests report a collapsed house"))
    if m["houses_damaged"]:
        facts.append(_plural(m["houses_damaged"], "request reports a damaged house", "requests report a damaged house"))
    else:
        facts.append("no house damage reported")
    facts.append(_plural(m["reports"], "report", "reports") + " in all")
    if hazards and level != "light":
        facts.append("photo tags show " + ", ".join(h.replace("_", " ") for h in hazards))
    return level, f"{level.capitalize()}: {'; '.join(facts)}."


def _centroid(cases: list[Case]) -> tuple[float, float] | None:
    pts = [c.pos for c in cases if c.pos is not None]
    if not pts:
        return None
    return round(sum(p[0] for p in pts) / len(pts), 5), round(sum(p[1] for p in pts) / len(pts), 5)


def damage_map(cases: list[Case], district: str, now: int) -> dict[str, Any]:
    """The ``GET /v1/recover/damage`` body: areas (worst first) and totals. Deterministic, no model."""
    groups: dict[str, list[Case]] = {}
    for c in cases:
        groups.setdefault(c.area.area_id, []).append(c)
    areas: list[dict[str, Any]] = []
    for group in groups.values():
        m = metrics(group)
        level, reason = damage_level(m)
        centre = _centroid(group)
        a = group[0].area
        areas.append({
            "area_id": a.area_id, "name": a.name, "district": a.district,
            "lat": centre[0] if centre else None, "lon": centre[1] if centre else None,
            **m, "damage_level": level, "damage_reason": reason,
        })
    areas.sort(key=lambda a: (LEVEL_ORDER[a["damage_level"]], -a["reports"], a["name"]))
    return {"generated_at": now, "district": district, "areas": areas, "totals": metrics(cases)}


# --------------------------------------------------------------------------------------
# evidence packs
# --------------------------------------------------------------------------------------

def canonical(obj: Any) -> bytes:
    """Canonical JSON: sorted keys, compact separators, UTF-8. What every digest is taken over."""
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def digest_of(record: dict[str, Any]) -> str:
    """SHA-256 (hex) of the record without its ``integrity`` field."""
    return hashlib.sha256(canonical({k: v for k, v in record.items() if k != "integrity"})).hexdigest()


def pseudonym(origin_node: str, salt: str) -> str:
    """Stable per-deployment stand-in for a phone id: the same phone gets the same value in every pack."""
    return "R-" + hashlib.sha256(f"{salt}:{origin_node}".encode()).hexdigest()[:12]


def _location(case: Case) -> dict[str, Any]:
    if case.pos is None:
        return {"basis": "none", "note": "no GPS fix and no approximate position"}
    lat, lon, kind = case.pos
    if kind == "gps":
        return {
            "basis": "gps", "lat": lat, "lon": lon,
            "accuracy_bucket": case.loc["loc_acc"], "accuracy": case.loc["loc_acc_m"],
        }
    g = case.row["geo"]
    out: dict[str, Any] = {
        "basis": "approximate", "source": g.get("source"), "lat": lat, "lon": lon,
        "note": "estimate, not a GPS fix",
    }
    if g.get("source") == "mesh":
        out["radius_m"] = g.get("radius_m")
        out["hint_count"] = g.get("hint_count")
    else:
        out["query"] = g.get("query")
        if g.get("source") == "gemini_maps":
            out.update({k: g[k] for k in ("name", "maps_uri") if g.get(k)})
            out["credit"] = "Google Maps"
    return out


def _timeline(case: Case) -> list[dict[str, Any]]:
    row, d = case.row, case.dispatch or {}
    received = [p["received_at"] for p in case.packets]
    events: list[tuple[int, dict[str, Any]]] = []
    events.append((row["first_reported_at"], {"event": "first_reported"}))
    if received:
        events.append((min(received), {"event": "received_by_control_room"}))
    if d.get("approved_at") is not None:
        ev: dict[str, Any] = {"event": "dispatched"}
        if d.get("team_name"):
            ev["team"] = d["team_name"]
        if d.get("eta_min") is not None:
            ev["eta_min"] = d["eta_min"]
        events.append((d["approved_at"], ev))
    if d.get("on_the_way_at") is not None:
        ev = {"event": "on_the_way"}
        if d.get("eta_min") is not None:
            ev["eta_min"] = d["eta_min"]
        events.append((d["on_the_way_at"], ev))
    if d.get("resolved_at") is not None:
        events.append((d["resolved_at"], {"event": "resolved"}))
    ordered = sorted(enumerate(events), key=lambda x: (x[1][0], x[0]))  # time, then the order above
    return [{"at": at, **ev} for _, (at, ev) in ordered]


def ticket_evidence(case: Case, salt: str) -> dict[str, Any]:
    """One ticket's self-contained evidence record, with its digest. A pure function of the stored data."""
    row = case.row
    record: dict[str, Any] = {
        "schema": EVIDENCE_SCHEMA,
        "ticket": {
            "ticket_no": row["ticket_no"], "category": row["category"], "item": row["item"],
            "landmark": row["landmark"], "summary": row["summary"], "people": row["people"],
            "urgency": row["urgency"], "lang": row["lang"], "status": row["status"],
            "report_count": row["report_count"], "first_reported_at": row["first_reported_at"],
        },
        "area": {"area_id": case.area.area_id, "name": case.area.name, "district": case.area.district},
        "location": _location(case),
        "reports": [
            {
                "msg_id": p["msg_id"], "reporter": pseudonym(p["origin_node"], salt), "hops": p["hops"],
                "gateway": p["gateway_node"], "received_at": p["received_at"], "reported_at": p["reported_at"],
            }
            for p in case.packets
        ],
        "timeline": _timeline(case),
        "answers": dict(row.get("answers") or {}),
        "photo_tags": {"tags": dict(sorted(_tags(case).items())), "note": PHOTO_NOTE},
        "priority": {"score": row["priority_score"], "tier": row["priority_tier"], "reason": row["priority_reason"]},
    }
    record["integrity"] = {"algorithm": "sha256", "digest": digest_of(record)}
    return record


def area_evidence(cases: list[Case], area: Area, district: str, salt: str, now: int) -> dict[str, Any]:
    """A pack of every ticket record of one area; ``integrity.digest`` is taken over the list of ticket digests."""
    records = [ticket_evidence(c, salt) for c in cases if c.area.area_id == area.area_id]
    digests = [r["integrity"]["digest"] for r in records]
    m = metrics([c for c in cases if c.area.area_id == area.area_id])
    return {
        "schema": EVIDENCE_SCHEMA + ".area",
        "generated_at": now,
        "district": district,
        "area": {"area_id": area.area_id, "name": area.name, "district": area.district},
        "summary": {k: m[k] for k in ("tickets", "reports", "duplicates_merged", "households", "households_reached", "resolved")},
        "tickets": records,
        "integrity": {
            "algorithm": "sha256", "ticket_count": len(digests),
            "digest": hashlib.sha256(canonical(digests)).hexdigest(),
        },
    }


def download_name(kind: str, ident: str) -> str:
    """File name of a downloaded pack, e.g. ``relay-evidence-ticket-101.json``."""
    return f"relay-evidence-{kind}-{_slug(ident)}.json"


# --------------------------------------------------------------------------------------
# relief and insurer report: figures, template, validated model prose
# --------------------------------------------------------------------------------------

LIMITATIONS = [
    "The figures come from the Relay offline mesh pilot; in this demo the reports are synthetic.",
    "Photo tags are unverified observations made by the requester's phone, and no image was ever stored.",
    "Locations are approximate: some come from a GPS fix, some from a landmark lookup or nearby phones.",
    "A household is counted as one reporting phone; evidence packs carry digests that make edits detectable but are not signed.",
]


def report_figures(dm: dict[str, Any]) -> dict[str, Any]:
    """The counts handed to the writer: the damage map's totals and rows, every figure an integer so the text can be
    checked against them (the median is rounded to whole minutes, the shares to whole percent)."""
    t = dm["totals"]
    levels = [a["damage_level"] for a in dm["areas"]]
    med = t["median_minutes_to_dispatch"]
    totals = {
        k: t[k] for k in (
            "tickets", "reports", "duplicates_merged", "households", "households_reached", "people", "open",
            "dispatched", "resolved", "needs_confirmed_closed", "houses_damaged",
        )
    }
    totals.update({
        "median_minutes_to_dispatch": None if med is None else int(round(med)),
        "share_with_location_pct": int(round(t["share_with_location"] * 100)),
        "area_count": len(dm["areas"]),
        "severe_areas": levels.count("severe"),
        "moderate_areas": levels.count("moderate"),
        "light_areas": levels.count("light"),
    })
    keep = ("name", "district", "tickets", "reports", "duplicates_merged", "households", "households_reached", "open",
            "dispatched", "resolved", "houses_damaged", "damage_level", "damage_reason")
    return {"totals": totals, "areas": [{k: a[k] for k in keep} for a in dm["areas"]]}


def _scope(district: str) -> str:
    d = district_by_id(district)
    return d.name if d else "all districts"


def template_en(counts: dict[str, Any], district: str) -> str:
    t = counts["totals"]
    paras = [
        f"Relief and damage report, {_scope(district)}. {_plural(t['tickets'], 'request was', 'requests were')} received "
        f"from {_plural(t['households'], 'household', 'households')} ({_plural(t['reports'], 'report', 'reports')}, "
        f"of which {t['duplicates_merged']} duplicate{'' if t['duplicates_merged'] == 1 else 's'} were merged before dispatch). "
        f"{t['resolved']} confirmed closed, {t['dispatched']} in progress, {t['open']} still open."
    ]
    if counts["areas"]:
        rows = [
            f"{a['name']}: {a['damage_level']} damage, {_plural(a['tickets'], 'request', 'requests')}, "
            f"{a['houses_damaged']} with reported house damage, {a['households_reached']} households reached"
            for a in counts["areas"][:MAX_AREAS_IN_TEXT]
        ]
        paras.append(
            f"Damage by village ({_plural(t['area_count'], 'area', 'areas')}: {t['severe_areas']} severe, "
            f"{t['moderate_areas']} moderate, {t['light_areas']} light). " + "; ".join(rows) + "."
        )
    paras.append(f"Households reached: {t['households_reached']} of {t['households']}.")
    med = t["median_minutes_to_dispatch"]
    paras.append(
        f"Response time: the median time from request to dispatch was {med} minutes."
        if med is not None else "Response time: no request has been dispatched yet."
    )
    paras.append(
        f"Data provenance and limitations: {t['share_with_location_pct']}% of requests have a usable location. "
        + " ".join(LIMITATIONS)
    )
    return "\n\n".join(paras)


# NOTE: the Odia and Hindi templates are first drafts pending native-speaker review.
def template_or(counts: dict[str, Any], district: str) -> str:
    t = counts["totals"]
    paras = [
        f"ରିଲିଫ୍ ଓ କ୍ଷୟକ୍ଷତି ରିପୋର୍ଟ, {_scope(district)}: {t['households']}ଟି ପରିବାରରୁ {t['tickets']}ଟି ଅନୁରୋଧ "
        f"({t['reports']}ଟି ରିପୋର୍ଟ, ପଠାଇବା ପୂର୍ବରୁ {t['duplicates_merged']}ଟି ଦୋହରା ମିଶାଯାଇଛି)। "
        f"{t['resolved']}ଟି ସମାଧାନ, {t['dispatched']}ଟି ଚାଲିଛି, {t['open']}ଟି ଖୋଲା।"
    ]
    if counts["areas"]:
        word = {"severe": "ଗୁରୁତର", "moderate": "ମଧ୍ୟମ", "light": "ହାଲୁକା", "none": "କିଛି ନାହିଁ"}
        rows = [
            f"{a['name']}: {word[a['damage_level']]} କ୍ଷୟକ୍ଷତି, {a['tickets']}ଟି ଅନୁରୋଧ, "
            f"{a['houses_damaged']}ଟିରେ ଘର କ୍ଷତି, {a['households_reached']}ଟି ପରିବାରକୁ ସହାୟତା"
            for a in counts["areas"][:MAX_AREAS_IN_TEXT]
        ]
        paras.append(f"ଗାଁ ଅନୁସାରେ କ୍ଷୟକ୍ଷତି ({t['area_count']}ଟି ଅଞ୍ଚଳ): " + "; ".join(rows) + "।")
    paras.append(f"{t['households']}ଟି ମଧ୍ୟରୁ {t['households_reached']}ଟି ପରିବାରକୁ ସହାୟତା ପହଞ୍ଚିଛି।")
    med = t["median_minutes_to_dispatch"]
    paras.append(
        f"ଅନୁରୋଧଠାରୁ ପଠାଇବା ପର୍ଯ୍ୟନ୍ତ ମଧ୍ୟମାନ ସମୟ {med} ମିନିଟ।" if med is not None else "ଏପର୍ଯ୍ୟନ୍ତ କିଛି ପଠାଯାଇନାହିଁ।"
    )
    paras.append(
        f"ତଥ୍ୟର ଉତ୍ସ ଓ ସୀମା: {t['share_with_location_pct']}% ଅନୁରୋଧର ସ୍ଥାନ ଜଣାଅଛି। ଏହି ତଥ୍ୟ ଡେମୋ (କୃତ୍ରିମ), "
        "ଫଟୋ ଟ୍ୟାଗ୍ ଅଯାଞ୍ଚିତ ପର୍ଯ୍ୟବେକ୍ଷଣ, ସ୍ଥାନ ଆନୁମାନିକ।"
    )
    return "\n\n".join(paras)


def template_hi(counts: dict[str, Any], district: str) -> str:
    t = counts["totals"]
    paras = [
        f"राहत एवं क्षति रिपोर्ट, {_scope(district)}: {t['households']} परिवारों से {t['tickets']} अनुरोध "
        f"({t['reports']} रिपोर्ट, भेजने से पहले {t['duplicates_merged']} दोहरी रिपोर्ट मिलाई गईं)। "
        f"{t['resolved']} का समाधान, {t['dispatched']} जारी, {t['open']} खुले हैं।"
    ]
    if counts["areas"]:
        word = {"severe": "गंभीर", "moderate": "मध्यम", "light": "हल्की", "none": "कोई नहीं"}
        rows = [
            f"{a['name']}: {word[a['damage_level']]} क्षति, {a['tickets']} अनुरोध, "
            f"{a['houses_damaged']} में घर को क्षति, {a['households_reached']} परिवारों तक सहायता"
            for a in counts["areas"][:MAX_AREAS_IN_TEXT]
        ]
        paras.append(f"गाँव के अनुसार क्षति ({t['area_count']} क्षेत्र): " + "; ".join(rows) + "।")
    paras.append(f"{t['households']} में से {t['households_reached']} परिवारों तक सहायता पहुँची।")
    med = t["median_minutes_to_dispatch"]
    paras.append(
        f"अनुरोध से रवाना करने तक का माध्य समय {med} मिनट रहा।" if med is not None else "अभी तक कोई सहायता नहीं भेजी गई।"
    )
    paras.append(
        f"डेटा का स्रोत और सीमाएँ: {t['share_with_location_pct']}% अनुरोधों का स्थान ज्ञात है। यह डेमो (कृत्रिम) डेटा है, "
        "फोटो टैग असत्यापित अवलोकन हैं, और स्थान अनुमानित हैं।"
    )
    return "\n\n".join(paras)


class ReportText(BaseModel):
    english: str
    local: str


_PROMPT = """You write the relief and damage report that a district disaster control room hands to the state relief
commissioner and to insurers after a cyclone, from the Relay offline mesh pilot. The figures below were computed by
the system and are final. Write prose AROUND them; do not recompute, round, convert or add any number, percentage,
time or date. Use only numbers that appear in the JSON (including inside area names and damage reasons). Say "top
priority" or leave tiers out; never write P1 to P4.

Figures JSON:
{figures}

Return JSON with two fields, each 5 to 8 short paragraphs or sections in plain text (no markdown), separated by a
blank line:
- english: the report in English.
- local: the same report in {language}.
Use this order: summary (requests, households, duplicates merged before dispatch, closed / in progress / open);
damage by village (the worst areas first, with their damage level and counts); households reached; response times
(the median minutes from request to dispatch, if any); data provenance and limitations, stating every item of
"limitations" in your own words and the share of requests with a usable location. Write for an official reader:
factual, no drama, no names of people. Do not claim anything the figures do not show.
"""


def allowed_numbers(counts: dict[str, Any]) -> set[int]:
    nums: set[int] = set()

    def walk(v: Any) -> None:
        if isinstance(v, bool):
            return
        if isinstance(v, int):
            nums.add(v)
        elif isinstance(v, str):
            nums.update(numbers_in(v))  # area names and reasons carry digits ("Area near 28.61, 77.21")
        elif isinstance(v, dict):
            for x in v.values():
                walk(x)
        elif isinstance(v, list):
            for x in v:
                walk(x)

    walk(counts)
    return nums


def valid_text(text: Any, counts: dict[str, Any], allowed: set[int]) -> str | None:
    """The model text if every number in it was handed over and the request count is stated; else None."""
    if not isinstance(text, str):
        return None
    text = text.strip()
    if not text or len(text) > MAX_TEXT_CHARS:
        return None
    found = numbers_in(text)
    if any(n not in allowed for n in found) or counts["totals"]["tickets"] not in found:
        return None
    return text


def write_report(
    counts: dict[str, Any], district: str, textgen: TextGen | None
) -> tuple[str, str, str, str]:
    """(english, local, local_lang, model): model prose if it passes validation, else the templates. Never raises."""
    lang = LOCAL_LANG.get(district, "or")
    template_local = template_hi if lang == "hi" else template_or
    en, local = template_en(counts, district), template_local(counts, district)
    if textgen is None:
        return en, local, lang, "template"
    figures = json.dumps({**counts, "scope": _scope(district), "limitations": LIMITATIONS}, ensure_ascii=False)
    prompt = _PROMPT.format(figures=figures, language="Hindi" if lang == "hi" else "Odia")
    try:
        data = call_json(textgen, prompt, ReportText, timeout_s=60.0)
    except Exception as e:
        log.warning("report model call failed, using templates: %s: %s", type(e).__name__, e)
        return en, local, lang, "template"
    allowed = allowed_numbers(counts)
    model_en = valid_text(data.get("english"), counts, allowed)
    model_local = valid_text(data.get("local"), counts, allowed)
    if model_en is None or model_local is None:
        # all or nothing: an English/local pair that disagree on content would be worse than templates
        log.warning("report model text rejected (numbers not in the counts); using templates")
        return en, local, lang, "template"
    return model_en, model_local, lang, "gemini"


def build_report(cases: list[Case], district: str, salt: str, textgen: TextGen | None, now: int) -> dict[str, Any]:
    """The ``POST /v1/recover/report`` body. Generated now from the store; nothing is persisted."""
    counts = report_figures(damage_map(cases, district, now))
    en, local, lang, model = write_report(counts, district, textgen)
    return {
        "generated_at": now, "district": district, "counts": counts,
        "text_en": en, "text_local": local, "local_lang": lang, "model": model,
        "evidence_digests": [
            {"ticket_no": c.row["ticket_no"], "digest": ticket_evidence(c, salt)["integrity"]["digest"]}
            for c in sorted(cases, key=lambda c: c.row["ticket_no"])
        ],
    }


def check_district(district: str) -> str:
    """A ``district`` query value: ``all`` or a known district id. Raises ValueError otherwise."""
    if district != "all" and district_by_id(district) is None:
        raise ValueError(f"district must be 'all' or one of {[d.id for d in DISTRICTS]}")
    return district
