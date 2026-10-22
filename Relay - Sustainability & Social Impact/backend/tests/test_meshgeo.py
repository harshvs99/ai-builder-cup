"""Mesh-assisted location: the estimator, then LOC_HINT frames through /v1/sync on every store backend (the
Firestore variants run only with the emulator)."""

import math
import random
from dataclasses import dataclass

import pytest

from app import codec, meshgeo
from app.meshgeo import distance_m, estimate, rssi_weight

from conftest import GATEWAY, KEY, T0, b64, need_frame, sync

# a requester somewhere in the village, and phones around it
TRUTH = (20.29625, 85.82455)


@dataclass
class H:
    lat: float
    lon: float
    accuracy_max_m: int = 25
    rssi_dbm: int = -128


def offset(lat, lon, north_m, east_m):
    return (lat + math.degrees(north_m / 6_371_000), lon + math.degrees(east_m / (6_371_000 * math.cos(math.radians(lat)))))


def near(north_m, east_m, **kw) -> H:
    return H(*offset(*TRUTH, north_m, east_m), **kw)


# ------------------------------------------------------------------ estimator

def test_one_hint_is_the_relay_position_widened_by_its_accuracy_and_the_radio_range():
    for acc, radius in ((10, 110), (25, 130), (50, 150), (100, 200)):  # 100 + bound, rounded up to 10
        g = estimate([H(20.29601, 85.82433, acc)])
        assert g == {"lat": 20.29601, "lon": 85.82433, "source": "mesh", "radius_m": radius, "hint_count": 1}


def test_no_hints_no_estimate():
    assert estimate([]) is None


def test_two_hints_intersect_into_a_smaller_disc_that_still_holds_the_truth():
    a, b = near(60, 0, accuracy_max_m=25), near(-60, 0, accuracy_max_m=25)   # 120 m apart, discs of 125 m
    g = estimate([a, b])
    assert g["hint_count"] == 2 and g["radius_m"] < 125
    assert distance_m(g["lat"], g["lon"], *TRUTH) < 5          # the lens is centred between the relays
    assert distance_m(g["lat"], g["lon"], *TRUTH) <= g["radius_m"]
    # lens half-width = sqrt(125^2 - 60^2) = 109.7 m across, 65 m along the axis: the circle covers it
    assert 60 <= g["radius_m"] <= 120


def test_three_hints_narrow_it_further():
    two = estimate([near(60, 0), near(-60, 0)])
    three = estimate([near(60, 0), near(-60, 0), near(0, 70)])
    assert three["hint_count"] == 3 and three["radius_m"] < two["radius_m"]
    assert distance_m(three["lat"], three["lon"], *TRUTH) <= three["radius_m"]


def test_a_wrong_hint_that_empties_the_intersection_is_left_out():
    good = [near(30, 0, accuracy_max_m=10), near(-30, 10, accuracy_max_m=10)]
    far = H(*offset(*TRUTH, 2000, 0), accuracy_max_m=10)                    # 2 km away: its disc cannot meet the others
    g = estimate(good + [far])
    assert g["hint_count"] == 2
    assert distance_m(g["lat"], g["lon"], *TRUTH) <= g["radius_m"]


def test_the_most_accurate_hint_wins_when_discs_are_disjoint():
    precise = H(20.0, 85.0, accuracy_max_m=10)
    coarse = H(20.05, 85.0, accuracy_max_m=100)
    g = estimate([coarse, precise])
    assert g["hint_count"] == 1 and (g["lat"], g["lon"]) == (20.0, 85.0)


def test_a_liar_claiming_great_accuracy_cannot_shut_out_hints_that_agree():
    honest = [near(30, 0, accuracy_max_m=50), near(-30, 10, accuracy_max_m=50), near(0, -40, accuracy_max_m=50)]
    liar = H(*offset(*TRUTH, 1500, 0), accuracy_max_m=10)       # the best claimed accuracy, but somewhere else
    g = estimate([liar] + honest)
    assert g["hint_count"] == 3 and distance_m(g["lat"], g["lon"], *TRUTH) <= g["radius_m"]


def test_tangent_discs_still_agree():
    a = H(*offset(*TRUTH, 0, 0), accuracy_max_m=10)                          # radius 110 each, centres 220 m apart
    b = H(*offset(*TRUTH, 0, 220), accuracy_max_m=10)
    g = estimate([a, b])
    assert g["hint_count"] == 2 and g["radius_m"] <= 20


def test_rssi_is_only_a_weak_weight():
    assert rssi_weight(-128) == 0.75 and rssi_weight(None) == 0.75
    assert rssi_weight(-40) == 1.0 and rssi_weight(-100) == 0.5 and rssi_weight(-20) == 1.0 and rssi_weight(-120) == 0.5
    weights = [rssi_weight(r) for r in range(-127, 0)]
    assert max(weights) / min(weights) <= 2
    # same positions, opposite signal strengths: the point moves a little, never out of the intersection
    a, b = (20.29600, 85.82400), (20.29640, 85.82440)
    strong_a = estimate([H(*a, rssi_dbm=-45), H(*b, rssi_dbm=-95)])
    strong_b = estimate([H(*a, rssi_dbm=-95), H(*b, rssi_dbm=-45)])
    plain = estimate([H(*a), H(*b)])
    shift = distance_m(strong_a["lat"], strong_a["lon"], strong_b["lat"], strong_b["lon"])
    assert 0 < shift < 0.2 * distance_m(*a, *b)
    assert strong_a["radius_m"] == strong_b["radius_m"] == plain["radius_m"]
    for g in (strong_a, strong_b):
        for p in (a, b):
            assert distance_m(g["lat"], g["lon"], *p) <= 125 + 1e-6


def test_stronger_link_pulls_towards_that_relay():
    a, b = (20.29600, 85.82400), (20.29640, 85.82440)
    toward_a = estimate([H(*a, rssi_dbm=-45), H(*b, rssi_dbm=-95)])
    toward_b = estimate([H(*a, rssi_dbm=-95), H(*b, rssi_dbm=-45)])
    assert distance_m(toward_a["lat"], toward_a["lon"], *a) < distance_m(toward_b["lat"], toward_b["lon"], *a)


def test_estimate_is_deterministic_and_order_independent():
    hs = [near(40, 10, rssi_dbm=-60), near(-20, 50, rssi_dbm=-80), near(0, -45, rssi_dbm=-70)]
    assert estimate(hs) == estimate(list(reversed(hs)))


def test_truthful_hints_always_cover_the_true_position():
    """Simulate requesters and relays within radio range and honest accuracy: the circle always holds the truth."""
    rnd = random.Random(7)
    for _ in range(300):
        n = rnd.randint(1, 6)
        relays = []
        for _ in range(n):
            ang, dist = rnd.uniform(0, 2 * math.pi), rnd.uniform(0, 95)          # within ~100 m radio range
            acc = rnd.choice((10, 25, 50, 100))
            err = rnd.uniform(0, acc)                                            # the relay's own error
            eang = rnd.uniform(0, 2 * math.pi)
            n_m, e_m = dist * math.cos(ang) + err * math.cos(eang), dist * math.sin(ang) + err * math.sin(eang)
            relays.append(H(*offset(*TRUTH, n_m, e_m), accuracy_max_m=acc, rssi_dbm=rnd.choice((-128, -50, -70, -90))))
        g = estimate(relays)
        assert g is not None and g["hint_count"] == n
        assert distance_m(g["lat"], g["lon"], *TRUTH) <= g["radius_m"], (relays, g)


def test_at_most_twelve_hints_are_used():
    hs = [near(random.Random(i).uniform(-40, 40), random.Random(i + 99).uniform(-40, 40)) for i in range(40)]
    g = estimate(hs)
    assert g["hint_count"] <= meshgeo.MAX_HINTS


def test_works_in_the_southern_and_western_hemisphere():
    g = estimate([H(-12.00000, -77.00000, 25), H(-12.00050, -77.00050, 25)])
    assert g["hint_count"] == 2 and -12.001 < g["lat"] < -12.0 and -77.001 < g["lon"] < -77.0


# ------------------------------------------------------------------ through the API

RELAY_A, RELAY_B, RELAY_C = (bytes([0xA0 + i]) * 8 for i in range(3))


def hint_frame(need, relay, pos, acc=2, rssi=-67, observed_at=T0 + 5, sender=None, ttl=7):
    h = codec.LocHint(need.msg_id, relay, codec.to_e5(pos[0]), codec.to_e5(pos[1]), acc, rssi, observed_at)
    return codec.encode_frame(codec.TYPE_LOC_HINT, ttl, (T0 + 6) * 1000, sender or relay, codec.encode_loc_hint(h))


def nofix(**kw):
    return need_frame(lat=0.0, lon=0.0, loc_acc=0, landmark="", **kw)


def ticket(c, n=101):
    return c.get(f"/v1/tickets/{n}").json()


def test_a_hint_gives_a_no_fix_ticket_a_mesh_estimate_but_not_a_location(make_client):
    c = make_client()
    f, need = nofix()
    assert "hints" not in sync(c, [f]).json()                       # only present when the upload held hints
    assert "geo" not in ticket(c)
    pos = near(0, 0)
    r = sync(c, [hint_frame(need, RELAY_A, (pos.lat, pos.lon), acc=2)]).json()
    assert r["hints"] == {"accepted": 1, "duplicates": 0, "rejected": 0} and r["accepted"] == 0
    t = ticket(c)
    assert t["geo"] == {"lat": round(pos.lat, 5), "lon": round(pos.lon, 5), "source": "mesh", "radius_m": 130, "hint_count": 1}
    # an estimate, never a fix: the "without location" signal is untouched
    assert (t["has_location"], t["loc_acc"], t["lat"], t["lon"]) == (False, 0, None, None)
    assert c.get("/v1/tickets").json()["tickets"][0]["geo"]["source"] == "mesh"
    row = c.app.state.store.get_ticket(101)
    assert (row["lat_e5"], row["lon_e5"]) == (0, 0)


def test_hints_that_arrive_before_their_need_are_kept_and_applied(make_client):
    c = make_client()
    f, need = nofix()
    a, b = near(40, 0), near(-40, 0)
    r = sync(c, [hint_frame(need, RELAY_A, (a.lat, a.lon)), hint_frame(need, RELAY_B, (b.lat, b.lon))]).json()
    assert r["hints"]["accepted"] == 2 and c.get("/v1/tickets").json()["tickets"] == []
    sync(c, [f])
    g = ticket(c)["geo"]
    assert g["source"] == "mesh" and g["hint_count"] == 2
    assert distance_m(g["lat"], g["lon"], *TRUTH) < 5


def test_a_mesh_estimate_replaces_the_landmark_estimate(make_client, monkeypatch, tmp_path):
    import json

    from app import geocode

    path = tmp_path / "gaz.json"
    path.write_text(json.dumps([{"name": "jagannath temple", "aliases": [], "lat": 19.805, "lon": 85.8178}]))
    monkeypatch.setenv("RELAY_GAZETTEER", str(path))
    monkeypatch.setattr(geocode, "_default", None)
    c = make_client()
    f, need = need_frame(lat=0.0, lon=0.0, loc_acc=0, landmark="jagannath temple")
    sync(c, [f])
    assert ticket(c)["geo"]["source"] == "gazetteer"
    sync(c, [hint_frame(need, RELAY_A, (19.8049, 85.8179), acc=1)])
    g = ticket(c)["geo"]
    assert g["source"] == "mesh" and "query" not in g and g["radius_m"] == 110
    monkeypatch.setattr(geocode, "_default", None)


def test_more_hints_refine_the_estimate_and_an_outlier_is_ignored(make_client):
    c = make_client()
    f, need = nofix()
    sync(c, [f])
    p1, p2 = near(60, 0), near(-60, 0)
    sync(c, [hint_frame(need, RELAY_A, (p1.lat, p1.lon))])
    one = ticket(c)["geo"]
    sync(c, [hint_frame(need, RELAY_B, (p2.lat, p2.lon))])
    two = ticket(c)["geo"]
    assert (one["hint_count"], two["hint_count"]) == (1, 2) and two["radius_m"] < one["radius_m"]
    liar = near(3000, 0)
    r = sync(c, [hint_frame(need, RELAY_C, (liar.lat, liar.lon), acc=1)]).json()
    assert r["hints"]["accepted"] == 1                               # stored, but its disc cannot join the others
    assert ticket(c)["geo"] == two


def test_one_hint_per_relay_per_need_first_wins(make_client):
    c = make_client()
    f, need = nofix()
    sync(c, [f])
    p, q = near(0, 0), near(500, 0)
    first = hint_frame(need, RELAY_A, (p.lat, p.lon))
    assert sync(c, [first]).json()["hints"]["accepted"] == 1
    again = sync(c, [first, hint_frame(need, RELAY_A, (q.lat, q.lon), acc=1, rssi=-30)]).json()
    assert again["hints"] == {"accepted": 0, "duplicates": 2, "rejected": 0}
    g = ticket(c)["geo"]
    assert distance_m(g["lat"], g["lon"], p.lat, p.lon) < 1 and g["hint_count"] == 1


def test_a_ticket_with_a_gps_fix_never_gets_a_mesh_estimate(make_client):
    c = make_client()
    f, need = need_frame(lat=20.29612, lon=85.82451, loc_acc=1, landmark="school")
    sync(c, [f])
    p = near(0, 0)
    assert sync(c, [hint_frame(need, RELAY_A, (p.lat, p.lon))]).json()["hints"]["accepted"] == 1
    t = ticket(c)
    assert "geo" not in t and (t["lat"], t["lon"], t["has_location"]) == (20.29612, 85.82451, True)
    assert c.app.state.store.set_geo(101, {"lat": 1.0, "lon": 1.0, "source": "mesh", "radius_m": 110, "hint_count": 1}) is False
    assert "geo" not in ticket(c)
    assert c.app.state.store.set_geo(999, {"lat": 1.0}) is False


def test_bad_hints_are_rejected_and_counted(make_client):
    c = make_client()
    f, need = nofix()
    sync(c, [f])
    p = near(0, 0)
    good = codec.encode_loc_hint(codec.LocHint(need.msg_id, RELAY_A, codec.to_e5(p.lat), codec.to_e5(p.lon), 2, -60, T0))
    bad = [
        hint_frame(need, RELAY_A, (p.lat, p.lon), sender=RELAY_B),                 # relay is not the frame sender
        codec.encode_frame(codec.TYPE_LOC_HINT, 7, T0 * 1000, RELAY_A, good[:-1]),  # does not decode
        codec.encode_frame(codec.TYPE_LOC_HINT, 7, T0 * 1000, RELAY_A, bytes([2]) + good[1:]),  # unknown version
        hint_frame(need, RELAY_B, (0.0, 0.0)),                                      # 0,0 is not a place
        hint_frame(need, RELAY_B, (p.lat, p.lon), observed_at=T0 - 73 * 3600),      # older than the mesh keeps things
        hint_frame(need, RELAY_B, (p.lat, p.lon), observed_at=T0 + 25 * 3600),      # from the future
    ]
    r = sync(c, bad).json()
    assert r["hints"] == {"accepted": 0, "duplicates": 0, "rejected": 6} and r["rejected"] == 0
    assert "geo" not in ticket(c)
    # a frame that is neither a NEED nor a hint is still just "rejected"
    junk = codec.encode_frame(0x7E, 3, T0 * 1000, RELAY_A, b"\x01")
    r = sync(c, [junk]).json()
    assert r["rejected"] == 1 and "hints" not in r


def test_hint_frames_ride_in_the_same_upload_as_needs(make_client):
    c = make_client()
    f, need = nofix()
    p = near(0, 0)
    r = sync(c, [hint_frame(need, RELAY_A, (p.lat, p.lon)), f]).json()           # hint listed before its NEED
    assert (r["accepted"], r["hints"]["accepted"]) == (1, 1)
    assert ticket(c)["geo"]["source"] == "mesh"


def test_a_late_landmark_geocode_cannot_replace_a_mesh_estimate(make_client):
    c = make_client()
    f, need = nofix()
    sync(c, [f])
    p = near(0, 0)
    sync(c, [hint_frame(need, RELAY_A, (p.lat, p.lon))])
    mesh = ticket(c)["geo"]
    store = c.app.state.store
    landmark = {"lat": 19.8, "lon": 85.8, "source": "gemini_maps", "query": "x"}
    assert store.update_ticket_if_unchanged(101, 1, {"geo": landmark, "priority_reason": "kept"}) is True
    t = ticket(c)
    assert t["geo"] == mesh and t["priority_reason"].startswith("kept")
    assert store.update_ticket_if_unchanged(101, 1, {"geo": landmark}) is True
    assert ticket(c)["geo"] == mesh


def test_hints_are_stored_per_need_and_resolved_to_the_ticket(make_client):
    c = make_client()
    f1, n1 = nofix()
    f2, n2 = nofix()
    sync(c, [f1, f2])
    p, q = near(0, 0), near(900, 900)
    sync(c, [hint_frame(n1, RELAY_A, (p.lat, p.lon)), hint_frame(n2, RELAY_A, (q.lat, q.lon))])
    g1, g2 = ticket(c, 101)["geo"], ticket(c, 102)["geo"]
    assert distance_m(g1["lat"], g1["lon"], p.lat, p.lon) < 1 and distance_m(g2["lat"], g2["lon"], q.lat, q.lon) < 1
    store = c.app.state.store
    assert [h["relay_node"] for h in store.hints_for([n1.msg_id.hex()])] == [RELAY_A.hex()]
    assert store.hints_for([]) == [] and store.hints_for(["00" * 8]) == []
    assert store.ticket_for_msg(n1.msg_id.hex()) == 101 and store.ticket_for_msg("00" * 8) is None


def test_dispatch_text_uses_the_mesh_estimate():
    from app.dispatch import _where

    t = {"landmark": "school", "has_location": False, "lat": None,
         "geo": {"lat": 20.2962, "lon": 85.8245, "source": "mesh", "radius_m": 130, "hint_count": 2}}
    assert _where(t) == "approx. 20.2962, 85.8245 (within about 130 m, estimated from 2 nearby phone(s), near school)"


def test_dev_inject_can_seed_mesh_hints(client):
    body = {"needs": [{"category": "WATER", "urgency": 2, "lat": 0, "lon": 0, "loc_acc": 0, "landmark": "school",
                       "mesh_hints": [{"lat": 20.2960, "lon": 85.8244, "accuracy_m": 20, "rssi_dbm": -60},
                                      {"lat": 20.2965, "lon": 85.8247, "accuracy_m": 40}]},
                      {"category": "WATER", "urgency": 2, "lat": 20.3, "lon": 85.8, "loc_acc": 1,
                       "mesh_hints": [{"lat": 20.2960, "lon": 85.8244}]}]}
    r = client.post("/v1/dev/inject", json=body).json()
    assert r["hints"] == 2 and r["accepted"] == 2
    nofix_t, fix_t = (client.get(f"/v1/tickets/{n}").json() for n in r["ticket_nos"])
    assert nofix_t["geo"]["source"] == "mesh" and nofix_t["geo"]["hint_count"] == 2 and not nofix_t["has_location"]
    assert "geo" not in fix_t
    bad = {"needs": [{**body["needs"][0], "mesh_hints": [{"lat": 20.0, "lon": 85.0, "accuracy_m": 150}]}]}
    assert client.post("/v1/dev/inject", json=bad).status_code == 422


def test_repeating_a_sync_with_hints_changes_nothing(make_client):
    c = make_client()
    f, need = nofix()
    p, q = near(30, 0), near(-30, 0)
    frames = [f, hint_frame(need, RELAY_A, (p.lat, p.lon)), hint_frame(need, RELAY_B, (q.lat, q.lon))]
    sync(c, frames)
    before = ticket(c)
    again = sync(c, frames).json()
    assert again["accepted"] == 0 and again["duplicates"] == 1 and again["hints"]["accepted"] == 0
    assert ticket(c) == before


def test_hint_with_the_device_key_missing_is_refused(client):
    f, need = nofix()
    p = near(0, 0)
    assert sync(client, [hint_frame(need, RELAY_A, (p.lat, p.lon))], headers={}).status_code == 401
