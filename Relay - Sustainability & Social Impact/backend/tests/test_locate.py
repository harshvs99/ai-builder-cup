"""POST /v1/tickets/{no}/locate: relocate a no-fix ticket from its landmark now (SQLite and, with the emulator, Firestore)."""

import json

import pytest

from app import geocode
from app.geocode import Found, MapsGeocoder, NoGeocoder

from conftest import need_frame, sync

MESH_GEO = {"lat": 19.8, "lon": 85.8, "source": "mesh", "radius_m": 50, "hint_count": 1}


class Finder:
    def __init__(self, result=None):
        self.result, self.calls = result, []

    def find(self, landmark, districts):
        self.calls.append((landmark, [d.id for d in districts]))
        return self.result


LIGHTHOUSE = Found(19.8123, 85.8301, "Old Lighthouse", "ChIJabc", "https://maps.google.com/?cid=42")


@pytest.fixture(autouse=True)
def gazetteer_file(tmp_path, monkeypatch):
    path = tmp_path / "gaz.json"
    path.write_text(json.dumps([{"name": "jagannath temple", "aliases": ["jagannath mandir"],
                                 "lat": 19.805, "lon": 85.8178}]), encoding="utf-8")
    monkeypatch.setenv("RELAY_GAZETTEER", str(path))
    monkeypatch.setattr(geocode, "_default", None)
    yield
    monkeypatch.setattr(geocode, "_default", None)


def no_fix(c, landmark):
    f, _ = need_frame(lat=0.0, lon=0.0, loc_acc=0, landmark=landmark)
    sync(c, [f])
    return max(t["ticket_no"] for t in c.get("/v1/tickets").json()["tickets"])


def test_locate_with_maps_writes_a_rich_geo_and_returns_the_ticket(make_client):
    finder = Finder()  # finds nothing while the ticket is being triaged
    c = make_client(geocoder=MapsGeocoder(finder))
    no = no_fix(c, "old lighthouse")
    assert "geo" not in c.get(f"/v1/tickets/{no}").json()
    finder.result = LIGHTHOUSE
    r = c.post(f"/v1/tickets/{no}/locate")
    assert r.status_code == 200
    t = r.json()
    assert t["ticket_no"] == no and t["has_location"] is False and t["lat"] is None
    assert t["geo"] == {"lat": 19.8123, "lon": 85.8301, "source": "gemini_maps", "query": "old lighthouse",
                        "name": "Old Lighthouse", "place_id": "ChIJabc", "maps_uri": "https://maps.google.com/?cid=42",
                        "district": "puri"}
    assert c.get(f"/v1/tickets/{no}").json()["geo"] == t["geo"]  # persisted
    assert finder.calls[-1] == ("old lighthouse", ["puri", "delhi"])  # no fix from this gateway: district unknown


def test_locate_prefers_the_gazetteer_and_replaces_an_earlier_estimate(make_client):
    finder = Finder(LIGHTHOUSE)
    c = make_client(geocoder=MapsGeocoder(finder))
    no = no_fix(c, "old lighthouse")
    assert c.get(f"/v1/tickets/{no}").json()["geo"]["source"] == "gemini_maps"
    # the operator corrected the landmark text's meaning: a gazetteer hit wins and costs no model call
    c.app.state.store.update_ticket(no, landmark="jagannath mandir")
    n = len(finder.calls)
    t = c.post(f"/v1/tickets/{no}/locate").json()
    assert t["geo"]["source"] == "gazetteer" and len(finder.calls) == n


def test_locate_uses_the_gateway_district(make_client):
    finder = Finder(Found(28.6139, 77.2090, "India Gate", "p", None))
    c = make_client(geocoder=MapsGeocoder(finder))
    sync(c, [need_frame(lat=28.60, lon=77.21)[0]])
    no = no_fix(c, "india gate")
    assert c.get(f"/v1/tickets/{no}").json()["geo"]["district"] == "delhi"
    finder.result = Found(19.81, 85.83, "Somewhere in Puri", "p", None)  # wrong district for this gateway
    assert c.post(f"/v1/tickets/{no}/locate").status_code == 422
    assert finder.calls[-1][1] == ["delhi"]


def test_locate_unknown_ticket_is_404(client):
    assert client.post("/v1/tickets/9999/locate").status_code == 404


def test_locate_refuses_a_ticket_with_a_gps_fix(make_client):
    finder = Finder(LIGHTHOUSE)
    c = make_client(geocoder=MapsGeocoder(finder))
    sync(c, [need_frame(landmark="old lighthouse")[0]])
    assert c.post("/v1/tickets/101/locate").status_code == 409
    assert finder.calls == [] and "geo" not in c.get("/v1/tickets/101").json()


def test_locate_never_replaces_a_mesh_estimate(make_client):
    finder = Finder(LIGHTHOUSE)
    c = make_client(geocoder=MapsGeocoder(finder))
    no = no_fix(c, "old lighthouse")
    assert c.app.state.store.set_geo(no, MESH_GEO)
    n = len(finder.calls)
    assert c.post(f"/v1/tickets/{no}/locate").status_code == 409
    assert c.get(f"/v1/tickets/{no}").json()["geo"]["source"] == "mesh" and len(finder.calls) == n
    # the store guard itself: a landmark estimate does not replace a mesh one, a mesh one replaces anything
    assert c.app.state.store.set_geo(no, {"lat": 1.0, "lon": 1.0, "source": "gemini_maps"}) is False
    assert c.app.state.store.set_geo(no, {**MESH_GEO, "radius_m": 20}) is True
    assert c.app.state.store.get_ticket(no)["geo"]["radius_m"] == 20


def test_store_set_geo_guards(make_client):
    c = make_client(geocoder=NoGeocoder())
    store = c.app.state.store
    sync(c, [need_frame()[0]])  # 101: GPS fix
    assert store.set_geo(101, {"lat": 1.0, "lon": 1.0, "source": "gemini_maps"}) is False
    assert store.set_geo(9999, {"lat": 1.0, "lon": 1.0, "source": "gemini_maps"}) is False
    no = no_fix(c, "nowhere known")
    assert store.set_geo(no, {"lat": 1.0, "lon": 1.0, "source": "gemini_maps", "query": "q"}) is True
    assert store.get_ticket(no)["geo"]["lat"] == 1.0


def test_locate_without_a_geocoder_is_503_unless_the_gazetteer_knows_it(make_client):
    c = make_client(geocoder=NoGeocoder())
    unknown = no_fix(c, "old lighthouse")
    assert c.post(f"/v1/tickets/{unknown}/locate").status_code == 503
    # a gazetteer entry added later is found without any model
    c.app.state.store.update_ticket(unknown, landmark="near jagannath mandir")
    r = c.post(f"/v1/tickets/{unknown}/locate")
    assert r.status_code == 200 and r.json()["geo"]["source"] == "gazetteer"


def test_locate_422_for_an_empty_landmark_or_nothing_confident(make_client):
    finder = Finder(None)
    c = make_client(geocoder=MapsGeocoder(finder))
    blank = no_fix(c, "")
    assert c.post(f"/v1/tickets/{blank}/locate").status_code == 422
    assert finder.calls == []
    vague = no_fix(c, "the big banyan tree")
    assert c.post(f"/v1/tickets/{vague}/locate").status_code == 422
    assert "geo" not in c.get(f"/v1/tickets/{vague}").json()
    # an ungrounded or out-of-district answer is "nothing confident" too
    finder.result = Found(19.81, 85.83, "Guess")
    assert c.post(f"/v1/tickets/{vague}/locate").status_code == 422


def test_locate_is_an_operator_route(make_client):
    c = make_client(operator_key="secret", geocoder=NoGeocoder())
    assert c.post("/v1/tickets/101/locate").status_code == 401
    assert c.post("/v1/tickets/101/locate", headers={"X-Operator-Key": "secret"}).status_code == 404
