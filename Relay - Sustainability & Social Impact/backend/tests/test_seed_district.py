"""The synthetic Puri-district night: 40 valid, decodable, deterministic frames."""

import base64
import json
from datetime import datetime

import pytest

from app import codec
from app.geocode import Gazetteer, in_bbox
from scripts import seed_district as seed

from conftest import GATEWAY, KEY, T0

NIGHT = datetime(2026, 10, 17, 21, 40, tzinfo=seed.IST)


@pytest.fixture(scope="module")
def frames():
    return seed.build_frames(7, NIGHT)


def test_forty_valid_decodable_frames(frames):
    assert len(frames) == 40
    assert len({f.need.msg_id for f in frames}) == 40
    for f in frames:
        frame = codec.decode_frame(f.frame)
        assert frame.type == codec.TYPE_NEED and 2 <= frame.ttl <= 6
        need = codec.decode_need(frame.payload)
        assert need == f.need
        assert 0 <= need.category < len(codec.CATEGORY_NAMES) and need.item and need.summary
        assert f.gateway in seed.GATEWAYS
        if need.loc_acc:
            assert in_bbox(need.lat, need.lon)


def test_deterministic_with_a_seed(frames):
    again = seed.build_frames(7, NIGHT)
    assert [f.frame for f in again] == [f.frame for f in frames]
    assert [f.frame for f in seed.build_frames(8, NIGHT)] != [f.frame for f in frames]


def test_the_night_runs_2140_to_0300_ist(frames):
    times = [datetime.fromtimestamp(f.need.created_at, seed.IST) for f in frames]
    assert times == sorted(times)
    assert min(times) >= NIGHT and max(times) <= NIGHT.replace(day=18, hour=3, minute=0)
    assert max(times).hour < 3 or (max(times).hour == 3 and max(times).minute == 0)


def test_mix_of_languages_and_categories(frames):
    langs = {codec.LANG_CODES[f.need.lang] for f in frames}
    cats = {codec.CATEGORY_NAMES[f.need.category] for f in frames}
    assert langs == {"en", "hi", "or"}
    assert cats == set(codec.CATEGORY_NAMES)


def test_no_gps_reports_carry_a_landmark_the_gazetteer_knows(frames):
    nofix = [f for f in frames if f.kind == "no_gps"]
    assert len(nofix) == 8
    gaz = Gazetteer.from_file(seed.GAZETTEER)
    for f in nofix:
        n = f.need
        assert n.loc_acc == 0 and (n.lat_e5, n.lon_e5) == (0, 0) and n.landmark
        assert gaz.match(n.landmark) is not None, n.landmark
    assert sum(1 for f in frames if f.need.loc_acc == 0) == 8


def test_three_duplicate_clusters_from_different_phones(frames):
    from app.cluster import haversine_m

    groups: dict[str, list] = {}
    for f in (f for f in frames if f.kind == "cluster"):
        groups.setdefault(f"{f.need.category}/{f.need.item}", []).append(f.need)
    assert sorted(len(g) for g in groups.values()) == [2, 3, 3]
    for g in groups.values():
        assert len({n.origin_node for n in g}) == len(g)
        assert all(haversine_m(g[0].lat, g[0].lon, n.lat, n.lon) < 150 for n in g)


def test_one_drinking_water_request_is_tagged_medical(frames):
    odd = [f for f in frames if f.kind == "water_as_medical"]
    assert len(odd) == 1
    n = odd[0].need
    assert codec.CATEGORY_NAMES[n.category] == "MEDICAL" and "water" in n.item
    assert [f for f in frames if f.need.item == "drinking water" and codec.CATEGORY_NAMES[f.need.category] == "MEDICAL"] == odd


def test_gazetteer_file_matches_the_contract():
    entries = json.loads(seed.GAZETTEER.read_text(encoding="utf-8"))
    assert 28 <= len(entries) <= 40
    for e in entries:
        assert set(e) == {"name", "aliases", "lat", "lon", "block"}
        assert e["name"] == e["name"].lower() and e["aliases"] and in_bbox(e["lat"], e["lon"])
    assert len({e["name"] for e in entries}) == len(entries)
    scripts = {c for e in entries for a in e["aliases"] for c in a if ord(c) > 0x900}
    assert any(0x900 <= ord(c) < 0xB00 for c in scripts) and any(0xB00 <= ord(c) < 0xB80 for c in scripts)


def test_uploading_through_sync_creates_the_expected_tickets(make_client, clock):
    clock.t = datetime(2026, 10, 18, 9, 0, tzinfo=seed.IST).timestamp()
    client = make_client()
    frames = seed.build_frames(7, NIGHT)
    accepted = 0
    for gw in seed.GATEWAYS:
        batch = [base64.b64encode(f.frame).decode() for f in frames if f.gateway == gw]
        r = client.post("/v1/sync", headers=KEY, json={"gateway_node": gw, "frames": batch})
        assert r.status_code == 200 and r.json()["rejected"] == 0
        accepted += r.json()["accepted"]
    assert accepted == 40
    tickets = client.get("/v1/tickets").json()["tickets"]
    assert len(tickets) == 40 - (2 + 2 + 1)                # only the planted clusters merge
    assert sorted(t["report_count"] for t in tickets)[-3:] == [2, 3, 3]
    assert sum(1 for t in tickets if not t["has_location"]) == 8
    # same seed and night: the same msg_ids, so a rerun is a no-op
    again = client.post("/v1/sync", headers=KEY, json={
        "gateway_node": seed.GATEWAYS[0],
        "frames": [base64.b64encode(f.frame).decode() for f in frames if f.gateway == seed.GATEWAYS[0]]}).json()
    assert again["accepted"] == 0


def test_some_reports_carry_synthetic_photo_tags(frames):
    tagged = [f for f in frames if f.need.photo_tags]
    assert 10 <= len(tagged) <= 14
    for f in tagged:
        assert codec.decode_frame(f.frame).payload[0] == 3
        assert codec.photo_tags_to_json(f.need.photo_tags)
