"""Seed a synthetic cyclone night for Puri district: 40 NEED frames, posted through /v1/sync.

    uv run python scripts/seed_district.py --url http://localhost:8080            # local
    uv run python scripts/seed_district.py --url https://relay.harshvs.com --key "$RELAY_DEVICE_KEY"
    uv run python scripts/seed_district.py --dry-run                              # just describe the frames

The night runs 21:40 to 03:00 IST. The frames are built with the real codec and uploaded the way
gateway phones upload them, so every ticket goes through clustering, scoring and ACK queuing:

  * 8 reports have no GPS fix, only a landmark from ``app/data/gazetteer_puri.json``
  * 3 clusters of duplicate reports (3 + 3 + 2): same need, within ~60 m, different origin phones
  * 1 drinking-water request deliberately tagged MEDICAL (the category triage should correct)
  * languages en / hi / or, spread over three gateway phones
  * 14 reports carry synthetic photo tags (floodwater, house_collapsed, ...), as an on-device classifier would
    report them: only the tag bitmask travels, never a picture

Everything is derived from ``--seed`` (default 7) and ``--night-start`` (default: yesterday
21:40 IST), so a run is repeatable. Re-running with the same two values uploads the same msg_ids,
which the backend dedupes.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import random
import sys
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from app.cluster import haversine_m  # noqa: E402
from app.codec import (  # noqa: E402
    CATEGORY_NAMES,
    INITIAL_TTL,
    LANG_CODES,
    TYPE_NEED,
    VULN_NAMES,
    Need,
    encode_frame,
    encode_need,
    photo_tags_from_json,
)

IST = timezone(timedelta(hours=5, minutes=30))
GAZETTEER = ROOT / "app" / "data" / "gazetteer_puri.json"
TOTAL = 40
NIGHT_START_HM = (21, 40)
NIGHT_MINUTES = 5 * 60 + 20  # 21:40 -> 03:00
GATEWAYS = ["5e0d0000000000a1", "5e0d0000000000b2", "5e0d0000000000c3"]
MIN_SEPARATION_M = 250.0  # plain reports of one category stay apart so only the planted clusters merge

# category -> [(item, {lang: summary}, vulnerability flags)]. Summaries fit the 40-byte wire limit.
SCENARIOS: dict[str, list[tuple[str, dict[str, str], list[str]]]] = {
    "MEDICAL": [
        ("insulin", {"en": "needs insulin, diabetic", "hi": "इंसुलिन चाहिए", "or": "ଔଷଧ ଦରକାର"}, ["ELDERLY"]),
        ("fever medicine", {"en": "child has high fever", "hi": "दवा चाहिए", "or": "ଔଷଧ ଦରକାର"}, ["CHILD"]),
        ("bp medicine", {"en": "elder out of bp medicine", "hi": "दवा चाहिए", "or": "ଔଷଧ ଦରକାର"}, ["ELDERLY"]),
        ("wound dressing", {"en": "deep cut, needs dressing", "hi": "दवा चाहिए", "or": "ଔଷଧ ଦରକାର"}, ["INJURED"]),
    ],
    "RESCUE": [
        ("boat", {"en": "family stuck on roof, water up", "hi": "छत पर फंसे", "or": "ଛାତରେ ଫସିଛୁ"}, ["TRAPPED"]),
        ("rescue", {"en": "trapped, house flooding", "hi": "छत पर फंसे", "or": "ଛାତରେ ଫସିଛୁ"}, ["TRAPPED", "CHILD"]),
    ],
    "WATER": [
        ("drinking water", {"en": "no clean drinking water", "hi": "पानी चाहिए", "or": "ପାଣି ଦରକାର"}, []),
    ],
    "FOOD": [
        ("rice", {"en": "no food since morning", "hi": "खाना चाहिए", "or": "ଖାଦ୍ୟ ଦରକାର"}, []),
        ("baby food", {"en": "infant, no milk or food", "hi": "खाना चाहिए", "or": "ଖାଦ୍ୟ ଦରକାର"}, ["CHILD"]),
        ("dry food", {"en": "shelter out of dry food", "hi": "खाना चाहिए", "or": "ଖାଦ୍ୟ ଦରକାର"}, []),
    ],
    "SHELTER": [
        ("shelter", {"en": "house collapsed, no shelter", "hi": "घर गिर गया", "or": "ଘର ଭୁଷୁଡ଼ିଛି"}, []),
        ("tarpaulin", {"en": "roof blown off, need tarpaulin", "hi": "घर गिर गया", "or": "ଘର ଭୁଷୁଡ଼ିଛି"}, ["ELDERLY"]),
    ],
    "MISSING_PERSON": [
        ("child", {"en": "child missing since evening", "hi": "बच्चा लापता", "or": "ପିଲା ନିଖୋଜ"}, ["CHILD"]),
    ],
    "OTHER": [
        ("road", {"en": "tree fallen, road blocked", "hi": "रास्ता बंद है", "or": "ରାସ୍ତା ବନ୍ଦ"}, []),
    ],
}
PLAIN_MIX = (
    ["MEDICAL"] * 5 + ["RESCUE"] * 5 + ["WATER"] * 4 + ["FOOD"] * 4 + ["SHELTER"] * 3
    + ["MISSING_PERSON", "OTHER"]
)  # 23 reports
NO_GPS_MIX = ["RESCUE", "MEDICAL", "WATER", "FOOD", "SHELTER", "MEDICAL", "FOOD", "RESCUE"]
# Synthetic photo tags (SPEC 5.1) for the scenarios where someone would plausibly photograph the scene: what an
# on-device classifier would report, not a verified assessment. A fixed table, so the night stays repeatable.
PHOTO_TAGS_BY_ITEM: dict[str, list[str]] = {
    "boat": ["floodwater", "people_stranded"],
    "rescue": ["floodwater", "house_damaged"],
    "shelter": ["house_collapsed"],
    "tarpaulin": ["roof_damaged", "tree_fallen"],
}
# (gazetteer place, category, scenario index, reports): the duplicate clusters
CLUSTERS = [
    ("district headquarters hospital puri", "MEDICAL", 0, 3),
    ("daya river embankment", "RESCUE", 0, 3),
    ("nimapara market", "FOOD", 0, 2),
]


@dataclass(frozen=True)
class SeedFrame:
    frame: bytes
    need: Need
    gateway: str
    kind: str  # "plain" | "no_gps" | "cluster" | "water_as_medical"


def load_gazetteer() -> list[dict]:
    return json.loads(GAZETTEER.read_text(encoding="utf-8"))


def default_night_start(now: datetime | None = None) -> datetime:
    """Yesterday 21:40 IST: far enough back that the whole night is in the past."""
    now = (now or datetime.now(IST)).astimezone(IST)
    day = (now - timedelta(days=1)).date()
    return datetime(day.year, day.month, day.day, *NIGHT_START_HM, tzinfo=IST)


def _offset(lat: float, lon: float, metres: float, bearing: float) -> tuple[float, float]:
    dlat = metres * math.cos(bearing) / 111_320.0
    dlon = metres * math.sin(bearing) / (111_320.0 * math.cos(math.radians(lat)))
    return round(lat + dlat, 5), round(lon + dlon, 5)


def _short_landmark(place: dict, rng: random.Random) -> str:
    """Name or an alias that fits the 24-byte landmark field whole (so it still matches the gazetteer)."""
    options = [n for n in [place["name"], *place["aliases"]] if len(n.encode("utf-8")) <= 24]
    return rng.choice(options)


def build_frames(seed: int = 7, night_start: datetime | None = None) -> list[SeedFrame]:
    """The 40 frames, ordered by report time. A pure function of ``seed`` and ``night_start``."""
    rng = random.Random(seed)
    start = int((night_start or default_night_start()).timestamp())
    places = load_gazetteer()
    by_name = {p["name"]: p for p in places}
    cluster_places = {c[0] for c in CLUSTERS}
    free_places = [p for p in places if p["name"] not in cluster_places]
    rng.shuffle(free_places)

    used_ids: set[bytes] = set()

    def new_id() -> bytes:
        while True:
            b = rng.randbytes(8)
            if b not in used_ids:
                used_ids.add(b)
                return b

    def make(category: str, scenario: int, lat: float | None, lon: float | None, *, landmark: str,
             lang: str, minute: int, kind: str, loc_acc: int | None = None, urgency: int | None = None,
             people: int | None = None, scenario_item: tuple | None = None) -> SeedFrame:
        item, summaries, flags = scenario_item or SCENARIOS[category][scenario % len(SCENARIOS[category])]
        origin, msg_id = new_id(), new_id()
        flag_bits = 0
        for f in flags:
            flag_bits |= 1 << VULN_NAMES.index(f)
        has_fix = lat is not None
        need = Need.make(
            msg_id=msg_id, origin_node=origin, created_at=start + minute * 60,
            category=CATEGORY_NAMES.index(category),
            urgency=urgency if urgency is not None else rng.choice([1, 2, 2, 3, 3]),
            people=people if people is not None else rng.randint(1, 12),
            vuln_flags=flag_bits, lat=lat if has_fix else 0.0, lon=lon if has_fix else 0.0,
            loc_acc=(loc_acc if loc_acc is not None else rng.choice([1, 2, 2, 3])) if has_fix else 0,
            lang=LANG_CODES.index(lang), item=item, landmark=landmark, summary=summaries[lang],
            photo_tags=photo_tags_from_json(PHOTO_TAGS_BY_ITEM.get(item, [])),
        )
        assert len(need.summary.encode("utf-8")) <= 40 and len(need.item.encode("utf-8")) <= 16, need
        assert len(need.landmark.encode("utf-8")) <= 24, need.landmark
        hops = rng.randint(1, 5)
        frame = encode_frame(TYPE_NEED, INITIAL_TTL - hops, (start + minute * 60) * 1000 + rng.randint(0, 999),
                             origin, encode_need(need))
        return SeedFrame(frame, need, rng.choice(GATEWAYS), kind)

    def pick_lang() -> str:
        return rng.choices(["or", "hi", "en"], weights=[5, 3, 2])[0]

    frames: list[SeedFrame] = []

    # clusters: same need, tight spread, different phones, reports minutes to an hour apart
    for place_name, category, scenario, n in CLUSTERS:
        place = by_name[place_name]
        first = rng.randint(20, NIGHT_MINUTES - 120)
        for k in range(n):
            lat, lon = _offset(place["lat"], place["lon"], rng.uniform(5, 60), rng.uniform(0, 2 * math.pi))
            frames.append(make(category, scenario, lat, lon, landmark=_short_landmark(place, rng),
                               lang=pick_lang(), minute=first + k * rng.randint(8, 40), kind="cluster",
                               loc_acc=2, urgency=3 if k == n - 1 else 2))

    # the miscategorised one: a drinking-water request filed as MEDICAL
    place = free_places.pop()
    lat, lon = _offset(place["lat"], place["lon"], rng.uniform(300, 600), rng.uniform(0, 2 * math.pi))
    frames.append(make("MEDICAL", 0, lat, lon, landmark=_short_landmark(place, rng), lang="or",
                       minute=rng.randint(60, NIGHT_MINUTES - 30), kind="water_as_medical", urgency=1,
                       people=6, scenario_item=("drinking water", SCENARIOS["WATER"][0][1], [])))

    # no GPS fix: only a landmark, distinct places
    for i, category in enumerate(NO_GPS_MIX):
        place = free_places[i % len(free_places)]
        frames.append(make(category, i, None, None, landmark=_short_landmark(place, rng), lang=pick_lang(),
                           minute=rng.randint(10, NIGHT_MINUTES), kind="no_gps"))

    # plain GPS reports, kept apart from each other within a category
    placed: list[tuple[str, float, float]] = []
    for i, category in enumerate(PLAIN_MIX):
        for _ in range(200):
            place = rng.choice(places)
            lat, lon = _offset(place["lat"], place["lon"], rng.uniform(300, 900), rng.uniform(0, 2 * math.pi))
            if all(c != category or haversine_m(lat, lon, la, lo) > MIN_SEPARATION_M for c, la, lo in placed):
                break
        else:  # pragma: no cover - the gazetteer is big enough that this never happens
            raise RuntimeError("could not place a report")
        placed.append((category, lat, lon))
        frames.append(make(category, rng.randrange(len(SCENARIOS[category])), lat, lon,
                           landmark=_short_landmark(place, rng) if rng.random() < 0.6 else "",
                           lang=pick_lang(), minute=rng.randint(0, NIGHT_MINUTES), kind="plain"))

    assert len(frames) == TOTAL, len(frames)
    frames.sort(key=lambda f: (f.need.created_at, f.need.msg_id))
    return frames


def post(url: str, key: str, frames: list[SeedFrame]) -> dict:
    import base64

    import requests

    totals = {"accepted": 0, "duplicates": 0, "rejected": 0}
    for gateway in GATEWAYS:
        batch = [base64.b64encode(f.frame).decode() for f in frames if f.gateway == gateway]
        if not batch:
            continue
        r = requests.post(
            f"{url.rstrip('/')}/v1/sync", headers={"X-Relay-Key": key}, timeout=60,
            json={"gateway_node": gateway, "frames": batch, "ack_cursor": 0},
        )
        r.raise_for_status()
        body = r.json()
        for k in totals:
            totals[k] += body[k]
    return totals


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--url", default="http://localhost:8080")
    ap.add_argument("--key", default=os.environ.get("RELAY_DEVICE_KEY", "dev-relay-key"), help="X-Relay-Key")
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--night-start", help="ISO time the night starts (default: yesterday 21:40 IST)")
    ap.add_argument("--dry-run", action="store_true", help="build and describe the frames, upload nothing")
    args = ap.parse_args()

    night = datetime.fromisoformat(args.night_start) if args.night_start else default_night_start()
    if night.tzinfo is None:
        night = night.replace(tzinfo=IST)
    frames = build_frames(args.seed, night)
    kinds: dict[str, int] = {}
    for f in frames:
        kinds[f.kind] = kinds.get(f.kind, 0) + 1
    print(f"{len(frames)} frames, night starting {night.isoformat()}, seed {args.seed}: {kinds}")
    if args.dry_run:
        for f in frames:
            n = f.need
            when = datetime.fromtimestamp(n.created_at, IST).strftime("%H:%M")
            where = f"{n.lat:.5f},{n.lon:.5f}" if n.loc_acc else f"no fix ({n.landmark})"
            print(f"  {when} {CATEGORY_NAMES[n.category]:<14} {LANG_CODES[n.lang]} {n.item:<16} {where}")
        return 0
    print("uploaded:", post(args.url, args.key, frames))
    return 0


if __name__ == "__main__":
    sys.exit(main())
