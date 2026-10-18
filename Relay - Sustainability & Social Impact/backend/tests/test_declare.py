"""Operator words -> a broadcast draft (app/declare.py), and the dashboard's declare / clear round trip."""

import json

from app import declare
from app.geocode import default_gazetteer

OP = {"X-Operator-Key": "s3cret"}
PURI = {"name": "Puri", "lat": 19.81, "lon": 85.83, "radius_km": 60}


class FakeTextGen:
    def __init__(self, respond):
        self.respond = respond
        self.prompts = []

    def generate_json(self, prompt, schema):
        self.prompts.append(prompt)
        out = self.respond(prompt)
        if isinstance(out, Exception):
            raise out
        return out if isinstance(out, str) else json.dumps(out)


def model(**kw):
    base = {"kind": "disaster", "disaster": "flood", "place": "", "lat": None, "lon": None, "radius_km": 0,
            "label": "Flood", "reason": "river flooding"}
    return FakeTextGen(lambda _p: {**base, **kw})


def run(text, textgen=None, district=PURI):
    return declare.interpret(text, textgen=textgen, gazetteer=default_gazetteer(), district=district)


# ------------------------------------------------------------------ rules (no model configured)

def test_rules_map_everyday_words_onto_the_four_codes():
    assert run("Dam release on the Bhargavi, villages inundated")["disaster"] == "flood"
    assert run("Storm surge along the coast")["disaster"] == "flood"
    assert run("Cyclone Dana landfall tonight")["disaster"] == "cyclone"
    assert run("Building collapse in the old town, people under rubble")["disaster"] == "earthquake"
    assert run("Mudslide on the ghat road")["disaster"] == "landslide"


def test_rules_without_a_place_use_the_operators_district_not_the_whole_network():
    d = run("Flooding everywhere")
    assert d["source"] == "rules" and d["kind"] == "disaster"
    assert d["area"] == {"lat": 19.81, "lon": 85.83, "source": "district", "name": "Puri", "radius_km": 60}
    assert "rising water" in d["phone_effect"] and d["wire_bytes"] == 25


def test_rules_read_a_radius_and_an_all_clear():
    assert run("flood within 15 km")["area"]["radius_km"] == 15
    d = run("The flood alert is withdrawn")
    assert d["kind"] == "cleared" and d["disaster"] == "flood"
    assert run("water flowing over the embankment, flood")["kind"] == "disaster"


def test_nothing_the_wire_can_carry_says_so():
    d = run("Heatwave, 47 degrees")
    assert d["disaster"] is None and any("nothing can be broadcast" in n for n in d["notes"])


# ------------------------------------------------------------------ the model

def test_a_place_the_gazetteer_knows_wins_over_the_models_coordinates():
    tg = model(disaster="cyclone", place="Gop cyclone shelter", lat=1.0, lon=1.0, radius_km=25, label="Cyclone near Gop")
    d = run("cyclone hitting gop", tg)
    assert d["disaster"] == "cyclone" and d["source"] == "model" and d["label"] == "Cyclone near Gop"
    assert d["area"] == {"lat": 20.09, "lon": 86.11, "source": "gazetteer", "name": "gop cyclone shelter", "radius_km": 25}
    assert '"cyclone hitting gop"' in tg.prompts[0]   # operator text is quoted data in the prompt


def test_model_coordinates_are_used_only_near_the_district_and_flagged():
    d = run("flood at Kakatpur", model(place="Kakatpur", lat=19.99, lon=86.19, radius_km=12))
    assert d["area"]["source"] == "model" and d["area"]["radius_km"] == 12
    assert any("check it on the map" in n for n in d["notes"])
    far = run("flood at Kakatpur", model(place="Kakatpur", lat=28.6, lon=77.2, radius_km=12))
    assert far["area"]["source"] == "district" and any("far from this district" in n for n in far["notes"])


def test_a_named_point_without_a_size_gets_a_town_sized_circle():
    assert run("flood at gop", model(place="Gop bus stand"))["area"]["radius_km"] == 10


def test_model_failure_or_nonsense_falls_back_to_the_rules():
    assert run("earthquake in the city", FakeTextGen(lambda _p: TimeoutError()))["source"] == "rules"
    assert run("earthquake in the city", FakeTextGen(lambda _p: "not json"))["disaster"] == "earthquake"
    # a code the wire cannot carry, while the rules recognise the words
    d = run("earthquake in the city", model(disaster="tsunami"))
    assert d["source"] == "rules" and d["disaster"] == "earthquake"
    # the model and the rules agree nothing fits: the model's draft (with its reason) is shown
    assert run("heatwave", model(disaster="none", reason="heat"))["reason"] == "heat"


def test_out_of_range_numbers_are_clamped_not_trusted():
    d = run("flood", model(place="Gop bus stand", radius_km=99999))
    assert d["area"]["radius_km"] == declare.MAX_RADIUS_KM
    assert run("flood", model(kind="whatever"))["kind"] == "disaster"


# ------------------------------------------------------------------ API

def test_interpret_sends_nothing_and_needs_the_operator_key(make_client):
    c = make_client(operator_key="s3cret", textgen=model(disaster="landslide", label="Landslide on ghat road"))
    assert c.post("/v1/broadcasts/interpret", json={"text": "slide"}).status_code == 401
    r = c.post("/v1/broadcasts/interpret", json={"text": "slope gave way", "district": PURI}, headers=OP)
    assert r.status_code == 200 and r.json()["disaster"] == "landslide"
    assert c.get("/v1/broadcasts", headers=OP).json() == {"broadcasts": []}
    assert c.post("/v1/broadcasts/interpret", json={"text": ""}, headers=OP).status_code == 422


def test_the_label_stays_in_the_control_room(make_client):
    c = make_client(operator_key="s3cret")
    r = c.post("/v1/broadcasts", json={"disaster": "cyclone", "lat": 19.81, "lon": 85.83, "radius_km": 60,
                                         "label": "  Cyclone   Dana, Puri coast "}, headers=OP)
    assert r.status_code == 200 and r.json()["label"] == "Cyclone Dana, Puri coast"
    listed = c.get("/v1/broadcasts", headers=OP).json()["broadcasts"]
    assert [b["label"] for b in listed] == ["Cyclone Dana, Puri coast"]
    c.post("/v1/broadcasts", json={"kind": "cleared", "disaster": "cyclone"}, headers=OP)
    assert [b.get("label") for b in c.get("/v1/broadcasts", headers=OP).json()["broadcasts"]] == ["Cyclone Dana, Puri coast", None]


def test_a_database_from_before_labels_gains_the_column(tmp_path):
    import sqlite3

    from app.store import SqliteStore

    path = str(tmp_path / "old.db")
    with sqlite3.connect(path) as conn:
        conn.execute("CREATE TABLE broadcasts (bcast_id TEXT PRIMARY KEY, issued_at INTEGER NOT NULL, payload BLOB NOT NULL)")
        conn.execute("INSERT INTO broadcasts VALUES ('00', 100, x'01')")
    s = SqliteStore(path)
    try:
        assert s.live_broadcasts(0) == [b"\x01"] and s.broadcast_labels(0) == {}
        assert s.add_broadcast("01", 200, b"\x02", "Flood") and s.broadcast_labels(0) == {"01": "Flood"}
    finally:
        s.close()


def test_rules_find_a_known_place_inside_the_sentence():
    d = run("Cyclone made landfall near Gop cyclone shelter, 25 km")
    assert d["area"] == {"lat": 20.09, "lon": 86.11, "source": "gazetteer", "name": "gop cyclone shelter", "radius_km": 25}
    assert d["notes"] == []
