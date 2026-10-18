"""Offline map packs (GET /v1/mappack): what a phone downloads in normal times and keeps for a disaster."""

from app import mappack

from conftest import KEY

GOP = (20.065, 86.12)


def test_a_pack_holds_only_places_whose_name_says_what_they_are(client):
    r = client.get("/v1/mappack", params={"lat": GOP[0], "lon": GOP[1], "radius_km": 10}, headers=KEY)
    assert r.status_code == 200
    body = r.json()
    assert body["version"] == 1 and body["radius_km"] == 10 and body["centre"] == {"lat": 20.065, "lon": 86.12}
    by_name = {p["name"]: p for p in body["places"]}
    assert set(by_name) == {"gop cyclone shelter", "devi river embankment"}   # not the market or the bus stand
    shelter = by_name["gop cyclone shelter"]
    assert shelter["kind"] == "shelter" and shelter["lat_e5"] == 2009000 and shelter["lon_e5"] == 8611000
    assert shelter["names"] == {"hi": "गोप चक्रवात आश्रय", "or": "ଗୋପ ବାତ୍ୟା ଆଶ୍ରୟସ୍ଥଳ"}


def test_the_whole_district_from_its_centre_and_nothing_far_away(client):
    kinds = [p["kind"] for p in client.get("/v1/mappack", params={"lat": 19.95, "lon": 85.95, "radius_km": 60}, headers=KEY).json()["places"]]
    assert kinds.count("shelter") == 5 and kinds.count("hospital") == 1 and kinds.count("water") >= 5
    assert client.get("/v1/mappack", params={"lat": 28.61, "lon": 77.2}, headers=KEY).json()["places"] == []


def test_the_device_key_is_required_and_inputs_are_checked(client):
    assert client.get("/v1/mappack", params={"lat": 20, "lon": 86}).status_code == 401
    assert client.get("/v1/mappack", params={"lat": 95, "lon": 86}, headers=KEY).status_code == 422
    assert client.get("/v1/mappack", params={"lat": 20, "lon": 86, "radius_km": 500}, headers=KEY).status_code == 422


def test_malformed_gazetteer_entries_are_skipped():
    entries = [{"name": "x hospital"}, {"name": "", "lat": 20, "lon": 86}, {"name": "y hospital", "lat": "a", "lon": 1},
               {"name": "z hospital", "lat": 20.0, "lon": 86.0, "aliases": None}]
    assert [p["name"] for p in mappack.build(entries, 20, 86, 5, 0)["places"]] == ["z hospital"]
