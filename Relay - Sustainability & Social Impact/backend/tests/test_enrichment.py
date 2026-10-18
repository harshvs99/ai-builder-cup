"""Landmark geocoding and category re-classification: unit tests and end-to-end through the
pipeline, on every store backend (the Firestore variants run only with the emulator)."""

import json
import sqlite3
import time

import pytest

from app import geocode, priority
from app.agent import GeminiAgent
from app.districts import DISTRICTS, district_by_id
from app.geocode import Found, Gazetteer, MapsGeocoder, in_bbox
from app.store import SqliteStore

from conftest import T0, need_frame, sync

# A small fixture: this suite never reads app/data/gazetteer_puri.json.
GAZ = [
    {"name": "jagannath temple", "aliases": ["jagannath mandir", "जगन्नाथ मंदिर", "ଜଗନ୍ନାଥ ମନ୍ଦିର", "shree mandir"],
     "lat": 19.80500, "lon": 85.81780, "block": "Puri Sadar"},
    {"name": "konark sun temple", "aliases": ["konark mandir", "surya mandir"],
     "lat": 19.88760, "lon": 86.09450, "block": "Konark"},
    {"name": "puri bus stand", "aliases": ["puri bus stop"], "lat": 19.81020, "lon": 85.83390, "block": "Puri Sadar"},
    {"name": "baliapanda", "aliases": ["baliapanda sahi", "बालियापांडा"], "lat": 19.79860, "lon": 85.82220, "block": "Puri Sadar"},
    # two different places share an alias: ambiguous, must never match on it
    {"name": "north primary school", "aliases": ["primary school north"], "lat": 19.83, "lon": 85.80, "block": "A"},
    {"name": "south primary school", "aliases": ["primary school north"], "lat": 19.70, "lon": 85.90, "block": "B"},
    # outside the district bounding box: dropped at load
    {"name": "bhubaneswar airport", "aliases": [], "lat": 20.2440, "lon": 85.8178 + 3.0, "block": "X"},
]


@pytest.fixture
def gaz():
    return Gazetteer(GAZ)


@pytest.fixture
def gazetteer_file(tmp_path, monkeypatch):
    path = tmp_path / "gaz.json"
    path.write_text(json.dumps(GAZ), encoding="utf-8")
    monkeypatch.setenv("RELAY_GAZETTEER", str(path))
    monkeypatch.setattr(geocode, "_default", None)
    yield path
    monkeypatch.setattr(geocode, "_default", None)


# ------------------------------------------------------------------ gazetteer

def test_gazetteer_exact_name_and_aliases(gaz):
    for text in ("Jagannath Temple", "jagannath mandir", "जगन्नाथ मंदिर", "ଜଗନ୍ନାଥ ମନ୍ଦିର", "Shree Mandir"):
        g = gaz.locate(text)
        assert g and (g["lat"], g["lon"], g["source"]) == (19.805, 85.8178, "gazetteer"), text
        assert g["query"] == text


def test_gazetteer_strips_near_words_in_three_languages(gaz):
    for text in ("near jagannath mandir", "Jagannath Mandir paas", "jagannath mandir pakhare", "जगन्नाथ मंदिर के पास",
                 "ଜଗନ୍ନାଥ ମନ୍ଦିର ପାଖରେ", "NEAR Jagannath Temple!"):
        assert gaz.locate(text), text


def test_gazetteer_tolerates_transliteration(gaz):
    for text in ("Jagannatha Mandira", "Jagannaath Mandir", "Baliapanda", "Baliyapanda", "Konaark Mandir"):
        assert gaz.locate(text), text
    assert gaz.locate("Baliyapanda")["lat"] == 19.7986


def test_gazetteer_contained_alias_in_longer_text(gaz):
    assert gaz.locate("jagannath mandir main gate")["lat"] == 19.805


def test_gazetteer_fuzzy_typo_but_not_a_different_place(gaz):
    assert gaz.match("konark sun tempel")[1] == "fuzzy"
    assert gaz.locate("puri bus stnad")["lat"] == 19.8102
    assert gaz.locate("puri railway station") is None


def test_gazetteer_no_match(gaz):
    for text in ("", "   ", "school", "temple", "the big banyan tree", "near", "x"):
        assert gaz.locate(text) is None, text


def test_gazetteer_ambiguous_alias_is_no_match(gaz):
    assert gaz.locate("primary school north") is None


def test_gazetteer_drops_out_of_bbox_and_malformed_entries():
    g = Gazetteer([*GAZ, {"name": "broken"}, {"name": "nan", "lat": "x", "lon": 1}])
    assert g.locate("bhubaneswar airport") is None
    assert len(Gazetteer([])) == 0 and Gazetteer([]).locate("jagannath") is None


def test_gazetteer_missing_file_is_empty(tmp_path):
    assert len(Gazetteer.from_file(tmp_path / "nope.json")) == 0


def test_shipped_gazetteer_loads_if_present():
    # Owned by another workstream; when it exists it must at least parse and stay in the district.
    if geocode.DEFAULT_GAZETTEER_PATH.exists():
        assert len(Gazetteer.from_file(geocode.DEFAULT_GAZETTEER_PATH)) > 0


# ------------------------------------------------------------------ maps geocoder

class Finder:
    def __init__(self, result=None, exc=None, delay=0.0):
        self.result, self.exc, self.delay, self.calls, self.searched = result, exc, delay, [], []

    def find(self, landmark, districts):
        self.calls.append(landmark)
        self.searched.append([d.id for d in districts])
        if self.delay:
            time.sleep(self.delay)
        if self.exc:
            raise self.exc
        return self.result


def at(lat, lon, name="Old Lighthouse", place_id="ChIJabc", maps_uri="https://maps.google.com/?cid=42"):
    return Found(lat, lon, name, place_id, maps_uri)


PURI = district_by_id("puri")
DELHI = district_by_id("delhi")


def test_maps_geocoder_accepts_point_inside_district():
    g = MapsGeocoder(Finder(at(19.81, 85.83))).locate("old lighthouse", PURI)
    assert g == {"lat": 19.81, "lon": 85.83, "source": "gemini_maps", "query": "old lighthouse",
                 "name": "Old Lighthouse", "place_id": "ChIJabc", "maps_uri": "https://maps.google.com/?cid=42",
                 "district": "puri"}


def test_maps_geocoder_optional_handles_and_unsafe_uri():
    g = MapsGeocoder(Finder(Found(19.81, 85.83, "X", "ChIJ", None))).locate("x", PURI)
    assert "maps_uri" not in g and g["place_id"] == "ChIJ"
    g = MapsGeocoder(Finder(Found(19.81, 85.83, "X", None, "javascript:alert(1)"))).locate("x", PURI)
    assert g is None  # the only handle was not an https link: effectively ungrounded


@pytest.mark.parametrize("found", [at(20.2961, 85.8245 + 5), at(28.61, 77.20), at(0.0, 0.0), at(float("nan"), 85.8),
                                   at("x", "y"), at(None, None), "junk", None])
def test_maps_geocoder_rejects_outside_district_and_junk(found):
    assert MapsGeocoder(Finder(found)).locate("somewhere", PURI) is None


def test_maps_geocoder_requires_grounding():
    for ungrounded in (Found(19.81, 85.83, "Old Lighthouse"), Found(19.81, 85.83, "X", "", "")):
        assert MapsGeocoder(Finder(ungrounded)).locate("x", PURI) is None


def test_maps_geocoder_validates_against_the_ticket_district():
    delhi_pt = at(28.6139, 77.2090, "India Gate")
    assert MapsGeocoder(Finder(delhi_pt)).locate("india gate", PURI) is None
    g = MapsGeocoder(Finder(delhi_pt)).locate("india gate", DELHI)
    assert (g["district"], g["name"]) == ("delhi", "India Gate")
    # unknown district: any known district's bbox will do, and the finder is told to look in all of them
    f = Finder(delhi_pt)
    assert MapsGeocoder(f).locate("india gate")["district"] == "delhi"
    assert f.searched == [[d.id for d in DISTRICTS]]
    assert MapsGeocoder(Finder(at(19.81, 85.83))).locate("x")["district"] == "puri"
    assert MapsGeocoder(Finder(at(40.0, 10.0))).locate("x") is None  # inside none of them
    f = Finder(at(19.81, 85.83))
    MapsGeocoder(f).locate("x", PURI)
    assert f.searched == [["puri"]]


def test_maps_geocoder_never_raises_and_times_out():
    assert MapsGeocoder(Finder(exc=ConnectionError("down"))).locate("x") is None
    assert MapsGeocoder(Finder(at(19.81, 85.83), delay=0.5), timeout_s=0.05).locate("x") is None


# ------------------------------------------------------------------ the genai finder (fake client, no network)

class Obj:
    def __init__(self, **kw):
        self.__dict__.update(kw)


def maps_chunk(title, uri="https://maps.google.com/?cid=1", place_id="places/1"):
    return Obj(maps=Obj(title=title, uri=uri, place_id=place_id))


def reply(text, chunks=None, candidates=True):
    cands = [Obj(grounding_metadata=Obj(grounding_chunks=chunks))] if candidates else []
    return Obj(text=text, candidates=cands)


class FakeModels:
    def __init__(self, resp):
        self.resp, self.calls = resp, []

    def generate_content(self, **kw):
        self.calls.append(kw)
        return self.resp


def finder_with(resp):
    models = FakeModels(resp)
    f = geocode.GenaiMapsFinder("m", 6.0, api_key="k", client=Obj(models=models))
    return f, models


ANSWER = json.dumps({"found": True, "name": "Pipili Bus Stand", "lat": 20.1, "lon": 85.83})


def test_genai_finder_picks_the_chunk_matching_the_model_name():
    f, models = finder_with(reply(ANSWER, [maps_chunk("Pipili Market", place_id="a"),
                                           maps_chunk("Pipili Bus Stand", place_id="b")]))
    got = f.find("pipili bus stand", [PURI])
    assert (got.name, got.place_id, got.lat, got.lon) == ("Pipili Bus Stand", "b", 20.1, 85.83)
    kw = models.calls[0]
    assert kw["config"].tools[0].google_maps is not None
    assert kw["config"].tool_config.retrieval_config.lat_lng.latitude == PURI.centre[0]
    assert '"pipili bus stand"' in kw["contents"] and "Puri district" in kw["contents"]


def test_genai_finder_falls_back_to_first_chunk_and_fenced_json():
    f, _ = finder_with(reply("```json\n" + ANSWER + "\n```", [maps_chunk("Totally Different", place_id="a"),
                                                            maps_chunk("Other", place_id="b")]))
    assert f.find("x", [PURI]).place_id == "a"


def test_genai_finder_unknown_district_names_all_and_sets_no_centre():
    f, models = finder_with(reply(ANSWER, [maps_chunk("Pipili Bus Stand")]))
    f.find("x", DISTRICTS)
    assert "Puri, Delhi" in models.calls[0]["contents"]
    assert models.calls[0]["config"].tool_config is None


@pytest.mark.parametrize("resp", [
    reply(ANSWER, None),  # no grounding chunks at all
    reply(ANSWER, []),
    reply(ANSWER, [Obj(maps=None), Obj(web=Obj(uri="https://example.com"))]),  # grounded, but not on Maps
    reply(ANSWER, [Obj(maps=Obj(title=None, uri=None, place_id=None))]),
    reply(ANSWER, candidates=False),
    reply(json.dumps({"found": False}), [maps_chunk("x")]),
    reply(json.dumps({"found": True, "lat": "20.1", "lon": 85.8}), [maps_chunk("x")]),
])
def test_genai_finder_rejects_ungrounded_and_unfound(resp):
    f, _ = finder_with(resp)
    assert f.find("x", [PURI]) is None


def test_genai_finder_end_to_end_through_the_geocoder():
    f, _ = finder_with(reply(ANSWER, [maps_chunk("Pipili Bus Stand")]))
    g = MapsGeocoder(f).locate("pipili bus stand", PURI)
    assert (g["name"], g["district"], g["place_id"]) == ("Pipili Bus Stand", "puri", "places/1")
    f, _ = finder_with(reply(ANSWER, None))
    assert MapsGeocoder(f).locate("pipili bus stand", PURI) is None


def test_geocoder_from_env_is_off_unless_explicit():
    assert geocode.geocoder_from_env({}).name == "none"
    assert geocode.geocoder_from_env({"RELAY_GEOCODER": "gemini_maps"}).name == "none"  # needs a backend too
    assert geocode.geocoder_from_env({"GEMINI_API_KEY": "k", "GOOGLE_GENAI_USE_VERTEXAI": "true"}).name == "none"
    on = geocode.geocoder_from_env({"RELAY_GEOCODER": "gemini_maps", "GOOGLE_GENAI_USE_VERTEXAI": "true"})
    assert on.name == "gemini_maps"  # lazy client: no credentials or network needed to construct


def test_geocoder_from_env_picks_vertex_or_api_key_and_model():
    base = {"RELAY_GEOCODER": "gemini_maps"}
    vertex = geocode.geocoder_from_env({**base, "GOOGLE_GENAI_USE_VERTEXAI": "1", "GOOGLE_CLOUD_PROJECT": "p"})
    assert vertex._finder._kwargs == {"vertexai": True, "project": "p", "location": "global"}
    key = geocode.geocoder_from_env({**base, "GEMINI_API_KEY": "k"})
    assert key.name == "gemini_maps" and key._finder._kwargs == {"api_key": "k"}
    both = geocode.geocoder_from_env({**base, "GEMINI_API_KEY": "k", "GOOGLE_GENAI_USE_VERTEXAI": "true"})
    assert both._finder._kwargs["vertexai"] is True  # Vertex wins, as for the other Gemini clients
    assert key._finder._model == "gemini-flash-latest"
    assert geocode.geocoder_from_env({**base, "GEMINI_API_KEY": "k", "GEMINI_MODEL": "m1"})._finder._model == "m1"
    assert geocode.geocoder_from_env(
        {**base, "GEMINI_API_KEY": "k", "GEMINI_MODEL": "m1", "GEMINI_GEOCODE_MODEL": "m2"})._finder._model == "m2"


def test_in_bbox():
    assert in_bbox(19.81, 85.83) and not in_bbox(20.5, 85.83) and not in_bbox(19.81, 87.0)


# ------------------------------------------------------------------ geocoding through the pipeline

def test_no_fix_ticket_gets_gazetteer_geo_but_stays_unlocated(make_client, gazetteer_file):
    c = make_client()
    f, _ = need_frame(lat=0.0, lon=0.0, loc_acc=0, landmark="near jagannath mandir")
    sync(c, [f])
    t = c.get("/v1/tickets/101").json()
    assert t["geo"] == {"lat": 19.805, "lon": 85.8178, "source": "gazetteer", "query": "near jagannath mandir"}
    # not a fix: the canonical location, the located flag and the "without location" signal are untouched
    assert (t["lat"], t["lon"], t["has_location"], t["loc_acc"]) == (None, None, False, 0)
    assert c.get("/v1/tickets").json()["tickets"][0]["geo"]["source"] == "gazetteer"
    row = c.app.state.store.get_ticket(101)
    assert (row["lat_e5"], row["lon_e5"]) == (0, 0)
    assert "no GPS fix" in t["dispatch_draft"]["en"]


def test_gps_fix_is_never_overwritten_by_a_geocode(make_client, gazetteer_file):
    c = make_client()
    finder = Finder(at(19.81, 85.83))
    c.app.state.pipeline.geocoder = MapsGeocoder(finder)
    f, _ = need_frame(lat=20.29612, lon=85.82451, loc_acc=1, landmark="jagannath mandir")
    sync(c, [f])
    t = c.get("/v1/tickets/101").json()
    assert "geo" not in t and (t["lat"], t["lon"], t["has_location"]) == (20.29612, 85.82451, True)
    assert finder.calls == []


def test_unknown_landmark_has_no_geo_and_maps_fills_it_in_phase_two(make_client, gazetteer_file):
    c = make_client()
    finder = Finder(at(19.8123, 85.8301, "Old Lighthouse"))
    c.app.state.pipeline.geocoder = MapsGeocoder(finder)
    f, _ = need_frame(lat=0.0, lon=0.0, loc_acc=0, landmark="old lighthouse")
    sync(c, [f])
    t = c.get("/v1/tickets/101").json()
    assert t["geo"] == {"lat": 19.8123, "lon": 85.8301, "source": "gemini_maps", "query": "old lighthouse",
                        "name": "Old Lighthouse", "place_id": "ChIJabc", "maps_uri": "https://maps.google.com/?cid=42",
                        "district": "puri"}
    assert finder.searched == [["puri", "delhi"]]  # no GPS-fixed ticket from this gateway yet: district unknown
    assert t["has_location"] is False and t["lat"] is None
    # a gazetteer hit short-circuits the model: no call, so no cost and no network
    f2, _ = need_frame(lat=0.0, lon=0.0, loc_acc=0, landmark="jagannath mandir")
    sync(c, [f2])
    assert c.get("/v1/tickets/102").json()["geo"]["source"] == "gazetteer"
    assert finder.calls == ["old lighthouse"]


def test_maps_result_outside_district_is_rejected_end_to_end(make_client, gazetteer_file):
    c = make_client()
    finder = Finder(at(28.6139, 77.2090))
    c.app.state.pipeline.geocoder = MapsGeocoder(finder)
    sync(c, [need_frame(lat=20.29612, lon=85.82451)[0]])  # this gateway carries Puri traffic with a fix
    f, _ = need_frame(lat=0.0, lon=0.0, loc_acc=0, landmark="old lighthouse")
    assert sync(c, [f]).json()["accepted"] == 1
    assert "geo" not in c.get("/v1/tickets/102").json()
    assert finder.searched == [["puri"]]


def test_no_fix_ticket_district_follows_its_gateway(make_client, gazetteer_file):
    c = make_client()
    finder = Finder(at(28.6139, 77.2090, "India Gate"))
    c.app.state.pipeline.geocoder = MapsGeocoder(finder)
    delhi_gw = "d" * 16
    sync(c, [need_frame(lat=28.60, lon=77.21)[0], need_frame(lat=28.63, lon=77.19)[0]], gateway=delhi_gw)
    sync(c, [need_frame(lat=20.29612, lon=85.82451)[0]])  # the Puri gateway
    f, _ = need_frame(lat=0.0, lon=0.0, loc_acc=0, landmark="india gate")
    sync(c, [f], gateway=delhi_gw)
    t = c.get("/v1/tickets/104").json()
    assert t["geo"]["district"] == "delhi" and t["geo"]["name"] == "India Gate"
    assert finder.searched == [["delhi"]]
    # the same Delhi answer for a ticket carried by the Puri gateway is out of its district
    f2, _ = need_frame(lat=0.0, lon=0.0, loc_acc=0, landmark="india gate")
    sync(c, [f2])
    assert "geo" not in c.get("/v1/tickets/105").json() and finder.searched[-1] == ["puri"]


def test_gateway_district_is_the_median_of_its_fixes_and_none_without_any():
    from app.geocode import gateway_district

    def tk(lat, lon, gw, acc=1):
        return {"lat_e5": round(lat * 1e5), "lon_e5": round(lon * 1e5)}, [{"gateway_node": gw, "loc_acc": acc}]

    rows = [tk(28.6, 77.2, "a"), tk(28.7, 77.1, "a"), tk(19.8, 85.8, "a"), tk(19.8, 85.8, "b"), tk(0, 0, "a", acc=0),
            tk(19.8, 85.8, "c", acc=0)]
    assert gateway_district(rows, {"a"}).id == "delhi"  # median of the fixes: one outlier doesn't move it
    assert gateway_district(rows, {"b"}).id == "puri"
    assert gateway_district(rows, {"c"}) is None  # only a no-fix ticket
    assert gateway_district(rows, {"zzz"}) is None and gateway_district([], {"a"}) is None


def test_gemini_maps_order_text_names_the_place():
    from app import dispatch

    geo = {"lat": 19.8123, "lon": 85.8301, "source": "gemini_maps", "query": "old lighthouse", "name": "Old Lighthouse"}
    t = {"ticket_no": 7, "category": "FOOD", "item": "rice", "people": 2, "landmark": "old lighthouse", "geo": geo,
         "has_location": False, "lat": None, "lon": None}
    assert "approx. near Old Lighthouse (Google Maps, from landmark 'old lighthouse')" in dispatch.order_template_en(t, "Team A", 30)
    assert dispatch.ticket_point(t) == (19.8123, 85.8301)


def test_geocoder_failure_does_not_lose_the_ticket_or_ack(make_client, gazetteer_file):
    c = make_client()
    c.app.state.pipeline.geocoder = MapsGeocoder(Finder(exc=RuntimeError("quota")))
    f, _ = need_frame(lat=0.0, lon=0.0, loc_acc=0, landmark="old lighthouse")
    r = sync(c, [f]).json()
    assert r["accepted"] == 1 and len(r["acks"]) == 1
    assert "geo" not in c.get("/v1/tickets/101").json()


# ------------------------------------------------------------------ reclass rules

def test_reclass_deterministic_rules():
    from app import reclass

    d = reclass.deterministic
    for item in ("drinking water", "Water", "पीने का पानी", "ପିଇବା ପାଣି", "paani", "bisleri"):
        assert d("MEDICAL", item)["to"] == "WATER", item
    assert d("MEDICAL", "drinking water") == {
        "from": "MEDICAL", "to": "WATER", "reason": 'item "drinking water" names drinking water, filed as MEDICAL'}
    assert d("WATER", "rice and dal")["to"] == "FOOD"
    assert d("OTHER", "tarpaulin")["to"] == "SHELTER"
    assert d("FOOD", "pani")["to"] == "WATER"
    # no change: already right, medical word present, ambiguous, empty, unrelated
    for cat, item in (("WATER", "drinking water"), ("MEDICAL", "ORS water"), ("MEDICAL", "insulin"),
                      ("MEDICAL", "food and water"), ("MEDICAL", ""), ("MEDICAL", "wheelchair"),
                      ("MEDICAL", "saline"), ("FOOD", "rice")):
        assert d(cat, item) is None, (cat, item)


def test_reclass_never_moves_out_of_life_safety_categories():
    from app import reclass

    assert reclass.deterministic("RESCUE", "drinking water") is None
    assert reclass.deterministic("MISSING_PERSON", "water") is None
    assert reclass.validate_proposal("RESCUE", "FOOD", "because", None) is None


def test_reclass_proposal_validation():
    from app import reclass

    ok = reclass.validate_proposal("MEDICAL", "WATER", "  water,\n not medicine ", None)
    assert ok == {"from": "MEDICAL", "to": "WATER", "reason": "water, not medicine"}
    for proposed, reason, existing in (("NOPE", "r", None), ("MEDICAL", "r", None), (None, "r", None),
                                       (5, "r", None), ("WATER", "", None), ("WATER", None, None),
                                       ("WATER", "r", {"from": "OTHER", "to": "MEDICAL", "reason": "x"})):
        assert reclass.validate_proposal("MEDICAL", proposed, reason, existing) is None
    assert len(reclass.validate_proposal("MEDICAL", "WATER", "x" * 999, None)["reason"]) == 200


# ------------------------------------------------------------------ reclass through the pipeline

def water_as_medical(**kw):
    return need_frame(category=0, item="drinking water", **kw)


def test_water_tagged_medical_end_to_end(make_client):
    c = make_client()
    f, need = water_as_medical(urgency=1)
    sync(c, [f])
    t = c.get("/v1/tickets/101").json()
    assert t["category"] == "WATER"
    assert t["reclass"] == {"from": "MEDICAL", "to": "WATER",
                            "reason": 'item "drinking water" names drinking water, filed as MEDICAL'}
    # re-scored with the existing priority function under the new category
    expected = priority.compute("WATER", 1, 1, 0, 1, 60 / 3600)
    assert (t["priority_score"], t["priority_tier"]) == (expected.score, expected.tier)
    # reason is appended to, not replaced
    assert t["priority_reason"].startswith(expected.reason)
    assert t["priority_reason"].endswith("Reclassified MEDICAL to WATER: " + t["reclass"]["reason"] + ".")
    assert "WATER" in t["dispatch_draft"]["en"] and t["dispatch_draft"]["en"].startswith(f"[{t['priority_tier']}]")
    # audit: the stored packet and its uploaded frame still say MEDICAL (category 0)
    [p] = c.app.state.store.ticket_packets(101)
    assert p["category"] == 0 and p["frame"] == f
    assert c.get("/v1/stats").json()["open_by_category"]["WATER"] == 1
    assert c.get("/v1/stats").json()["open_by_category"]["MEDICAL"] == 0


def test_untouched_ticket_has_no_reclass_or_geo_keys(make_client):
    c = make_client()
    sync(c, [need_frame(item="insulin")[0]])
    t = c.get("/v1/tickets/101").json()
    assert "reclass" not in t and "geo" not in t and t["category"] == "MEDICAL"


def test_p1_ticket_is_never_lowered_by_reclass(make_client):
    c = make_client()
    # MEDICAL: 5 + 4.5 + 2 (two flags) + 2 (3 people) = 13.5 -> P1. As WATER it would be 11.5 -> P2.
    f, _ = water_as_medical(urgency=3, people=3, vuln_flags=0b11)
    sync(c, [f])
    t = c.get("/v1/tickets/101").json()
    assert t["category"] == "WATER"
    assert t["priority_score"] == 11.5  # the score is the honest WATER score ...
    assert t["priority_tier"] == "P1"  # ... the tier is held
    assert "Held at P1" in t["priority_reason"] and t["priority_reason"].count("Reclassified") == 1
    assert t["dispatch_draft"]["en"].startswith("[P1] ")
    assert t["dispatch_draft"]["local"].count("[P1]") == 1 and "[P2]" not in t["dispatch_draft"]["local"]
    row = c.app.state.store.get_ticket(101)
    assert (row["priority_tier"], row["tier_adjust"]) == ("P1", 1)
    # contrast: filed as WATER in the first place, the same need is a plain P2
    f2, _ = need_frame(category=2, item="drinking water", urgency=3, people=3, vuln_flags=0b11, lat=20.5, lon=85.9)
    sync(c, [f2])
    t2 = c.get("/v1/tickets/102").json()
    assert t2["priority_tier"] == "P2" and "reclass" not in t2


def test_lower_tier_is_not_forced_up_by_reclass(make_client):
    c = make_client()
    f, _ = water_as_medical(urgency=0)
    sync(c, [f])
    t = c.get("/v1/tickets/101").json()
    assert t["priority_tier"] != "P1" and "Held at P1" not in t["priority_reason"]


def test_later_reports_cluster_with_the_reclassified_ticket(make_client):
    c = make_client()
    sync(c, [water_as_medical(origin=b"\x00" * 7 + b"\x01")[0]])
    # a neighbour 50 m away reports the same need, tagged MEDICAL again, then another tagged WATER
    sync(c, [need_frame(category=0, item="drinking water", lat=20.29640, origin=b"\x00" * 7 + b"\x02")[0]])
    sync(c, [need_frame(category=2, item="drinking water", lat=20.29650, origin=b"\x00" * 7 + b"\x03")[0]])
    tickets = c.get("/v1/tickets").json()["tickets"]
    assert len(tickets) == 1 and tickets[0]["report_count"] == 3 and tickets[0]["category"] == "WATER"
    assert tickets[0]["reclass"]["from"] == "MEDICAL"  # the merges left the correction alone


def test_medical_ticket_does_not_swallow_water_report_and_vice_versa(make_client):
    c = make_client()
    sync(c, [need_frame(category=0, item="insulin")[0]])
    sync(c, [water_as_medical(lat=20.29640)[0]])  # 30 m away but a different need
    cats = sorted(t["category"] for t in c.get("/v1/tickets").json()["tickets"])
    assert cats == ["MEDICAL", "WATER"]


# ------------------------------------------------------------------ reclass proposed by the model

class FakeClient:
    def __init__(self, response=None, exc=None):
        self.response, self.exc, self.prompts = response, exc, []

    def generate_json(self, prompt):
        self.prompts.append(prompt)
        if self.exc:
            raise self.exc
        return json.dumps(self.response)


def gem(**over):
    base = {
        "priority_reason": "Model reason.",
        "tier_adjustment": 0,
        "dispatch_en": "Need at 20.29612, 85.82451 near school; 1 report.",
        "dispatch_local": "ଆବଶ୍ୟକ 20.29612, 85.82451",
    }
    return {**base, **over}


def test_model_proposed_reclass_is_applied_validated_and_audited(make_client):
    fake = FakeClient(gem(category="SHELTER", category_reason="mosquito nets are shelter supplies"))
    c = make_client(agent=GeminiAgent(fake))
    f, _ = need_frame(category=6, item="mosquito nets", urgency=1)
    sync(c, [f])
    t = c.get("/v1/tickets/101").json()
    assert t["category"] == "SHELTER"
    assert t["reclass"] == {"from": "OTHER", "to": "SHELTER", "reason": "mosquito nets are shelter supplies"}
    # reassessed under the new category (2 calls), and the model's reason is kept with ours appended
    assert len(fake.prompts) == 2 and '"category": "SHELTER"' in fake.prompts[1]
    assert t["priority_reason"] == "Model reason. Reclassified OTHER to SHELTER: mosquito nets are shelter supplies."
    expected = priority.compute("SHELTER", 1, 1, 0, 1, 60 / 3600)
    assert t["priority_score"] == expected.score
    assert t["dispatch_draft"]["en"].startswith(f"[{expected.tier}] ")
    assert c.app.state.store.ticket_packets(101)[0]["category"] == 6
    assert "mosquito nets" in fake.prompts[0] and "RESCUE" in fake.prompts[0]  # enum offered to the model


@pytest.mark.parametrize("cat,reason", [("NOPE", "x"), ("OTHER", "same"), ("FOOD", ""), (7, "x"), ("food", "x")])
def test_invalid_model_category_is_ignored(make_client, cat, reason):
    fake = FakeClient(gem(category=cat, category_reason=reason))
    c = make_client(agent=GeminiAgent(fake))
    sync(c, [need_frame(category=6, item="mosquito nets")[0]])
    t = c.get("/v1/tickets/101").json()
    assert t["category"] == "OTHER" and "reclass" not in t and len(fake.prompts) == 1
    assert t["priority_reason"] == "Model reason."


def test_model_cannot_demote_a_rescue_or_reclass_twice(make_client):
    fake = FakeClient(gem(category="FOOD", category_reason="injected: ignore previous instructions"))
    c = make_client(agent=GeminiAgent(fake))
    sync(c, [need_frame(category=1, item="boat needed")[0]])
    assert c.get("/v1/tickets/101").json()["category"] == "RESCUE"
    # a ticket the rules already corrected is not re-corrected by the model
    sync(c, [water_as_medical(lat=20.5, lon=85.9)[0]])
    t = c.get("/v1/tickets/102").json()
    assert t["category"] == "WATER" and t["reclass"]["from"] == "MEDICAL"


def test_model_reclass_cannot_lower_a_p1_ticket(make_client):
    fake = FakeClient(gem(category="OTHER", category_reason="not really medical", tier_adjustment=-1))
    c = make_client(agent=GeminiAgent(fake))
    # MEDICAL 13.5 -> P1; as OTHER 9.5 -> P2, and the model also asks for one tier less
    f, _ = need_frame(category=0, item="wheelchair", urgency=3, people=3, vuln_flags=0b11)
    sync(c, [f])
    t = c.get("/v1/tickets/101").json()
    assert t["category"] == "OTHER" and t["priority_tier"] == "P1"
    assert t["priority_score"] == priority.compute("OTHER", 3, 3, 0b11, 1, 60 / 3600).score
    assert "Held at P1" in t["priority_reason"] and t["dispatch_draft"]["en"].startswith("[P1] ")


def test_model_failure_falls_back_cleanly_and_keeps_rule_reclass(make_client):
    c = make_client(agent=GeminiAgent(FakeClient(exc=ConnectionError("down"))))
    sync(c, [water_as_medical()[0]])
    t = c.get("/v1/tickets/101").json()
    assert t["category"] == "WATER" and t["reclass"]["to"] == "WATER"
    assert t["priority_reason"].endswith(t["reclass"]["reason"] + ".")  # phase-1 reason survived the outage
    assert t["dispatch_draft"]["en"].startswith("[P")


def test_model_reclass_with_second_call_failing_still_files_it(make_client):
    calls = []

    class Flaky(FakeClient):
        def generate_json(self, prompt):
            calls.append(prompt)
            if len(calls) == 2:
                raise TimeoutError("slow")
            return json.dumps(gem(category="SHELTER", category_reason="tents"))

    c = make_client(agent=GeminiAgent(Flaky()))
    sync(c, [need_frame(category=6, item="mosquito nets")[0]])
    t = c.get("/v1/tickets/101").json()
    assert t["category"] == "SHELTER" and t["reclass"]["to"] == "SHELTER"
    assert t["priority_reason"].endswith("Reclassified OTHER to SHELTER: tents.")
    assert t["dispatch_draft"]["en"].startswith("[P")


def test_model_reclassed_ticket_is_found_in_its_new_category(make_client):
    # the category the merge lookup queries must follow the reclass on every backend
    c = make_client(agent=GeminiAgent(FakeClient(gem(category="SHELTER", category_reason="tents"))))
    sync(c, [need_frame(category=6, item="mosquito nets", origin=b"\x00" * 7 + b"\x01")[0]])
    sync(c, [need_frame(category=4, item="mosquito nets", lat=20.29640, origin=b"\x00" * 7 + b"\x02")[0]])
    tickets = c.get("/v1/tickets").json()["tickets"]
    assert len(tickets) == 1 and tickets[0]["report_count"] == 2 and tickets[0]["category"] == "SHELTER"
    assert c.get("/v1/stats").json()["open_by_category"]["OTHER"] == 0


# ------------------------------------------------------------------ store round trip

def test_sqlite_json_columns_round_trip_and_migrate(tmp_path):
    path = str(tmp_path / "old.db")
    s = SqliteStore(path)
    no = s.create_ticket({
        "category": "WATER", "lat_e5": 0, "lon_e5": 0, "urgency": 1, "people": 1, "vuln_flags": 0, "lang": "en",
        "first_reported_at": T0, "created_at": T0, "updated_at": T0,
        "geo": {"lat": 19.805, "lon": 85.8178, "source": "gazetteer", "query": "ଜଗନ୍ନାଥ"},
        "reclass": {"from": "MEDICAL", "to": "WATER", "reason": "r"},
    })
    got = s.get_ticket(no)
    assert got["geo"]["query"] == "ଜଗନ୍ନାଥ" and got["reclass"]["to"] == "WATER"
    s.update_ticket(no, geo=None)
    assert s.get_ticket(no)["geo"] is None
    assert s.list_tickets()[0]["reclass"]["from"] == "MEDICAL"
    s.close()
    # a database from before the columns existed gains them on open, with existing rows reading as unset
    db = sqlite3.connect(path)
    db.execute("ALTER TABLE tickets DROP COLUMN geo")
    db.execute("ALTER TABLE tickets DROP COLUMN reclass")
    db.commit()
    db.close()
    s2 = SqliteStore(path)
    row = s2.get_ticket(no)
    assert row["geo"] is None and row["reclass"] is None
    s2.update_ticket(no, geo={"lat": 1.0, "lon": 2.0, "source": "gazetteer", "query": "q"})
    assert s2.get_ticket(no)["geo"]["lat"] == 1.0
    s2.close()
