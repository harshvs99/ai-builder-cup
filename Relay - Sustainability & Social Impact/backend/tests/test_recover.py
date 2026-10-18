"""Recover mode: areas, damage map, evidence packs, relief report. API tests run on both stores (``make_client``)."""

import hashlib
import json

import pytest

from app import recover
from app.geocode import Gazetteer

from conftest import KEY, T0, need_frame, sync

GAZ = Gazetteer([
    {"name": "jagannath temple", "lat": 19.8048, "lon": 85.818, "block": "Puri Sadar"},
    {"name": "pipili bus stand", "lat": 20.1146, "lon": 85.8309, "block": "Pipili"},
])
FLOOD, HOUSE_DAMAGED, COLLAPSED, ROOF = 1 << 0, 1 << 1, 1 << 2, 1 << 3
O_A, O_B, O_C, O_D, O_E = (bytes([x]) * 8 for x in (0xA1, 0xB2, 0xC3, 0xD4, 0xE5))
OP = {"X-Operator-Key": "s3cret"}


class FakeTextGen:
    def __init__(self, respond):
        self.respond, self.prompts = respond, []

    def generate_json(self, prompt, schema):
        self.prompts.append(prompt)
        out = self.respond(prompt)
        if isinstance(out, Exception):
            raise out
        return out if isinstance(out, str) else json.dumps(out)


def figures_in(prompt):
    return json.loads(prompt.split("Figures JSON:\n")[1].split("\n\nReturn JSON")[0])


def seed(client):
    """Puri: a collapsed house at the temple reported by two phones (merged); a damaged house on open ground far from any
    place (grid cell); a request with no position at all. Delhi: one plain request. Ticket 101 is then resolved."""
    sync(client, [
        need_frame(origin=O_A, lat=19.8050, lon=85.8185, category=0, photo_tags=COLLAPSED | FLOOD)[0],
        need_frame(origin=O_B, lat=19.8050, lon=85.8185, category=0, photo_tags=COLLAPSED)[0],
        need_frame(origin=O_C, lat=19.95, lon=85.83, category=1, item="boat", answers=((7, 1),))[0],
        need_frame(origin=O_D, lat=28.61, lon=77.21, category=3, item="rice")[0],
        need_frame(origin=O_E, lat=0.0, lon=0.0, loc_acc=0, category=2, item="water", landmark="")[0],
    ])
    # an approval with an ETA records its time; a plain approval stores no dispatch record, so no time
    assert client.post("/v1/tickets/101/approve", json={"eta_min": 30}).status_code == 200
    assert client.post("/v1/tickets/101/resolve").status_code == 200


def damage(client, district="all"):
    return client.get(f"/v1/recover/damage?district={district}").json()


def by_name(dm):
    return {a["name"]: a for a in dm["areas"]}


# ------------------------------------------------------------------ areas

def test_assign_area_nearest_gazetteer_place_within_3_km():
    a = recover.assign_area(19.8060, 85.8190, GAZ)
    assert (a.area_id, a.name, a.district) == ("place:jagannath-temple", "Jagannath Temple, Puri Sadar", "puri")
    assert recover.assign_area(19.8048 + 0.0275, 85.818, GAZ).area_id.startswith("grid:")   # ~3.06 km: out
    assert recover.assign_area(19.8048 + 0.0260, 85.818, GAZ).area_id == a.area_id         # ~2.9 km: in


def test_assign_area_grid_cell_is_stable_and_named_by_district_and_place():
    a = recover.assign_area(19.95, 85.83, GAZ)
    b = recover.assign_area(19.9501, 85.8301, GAZ)
    assert a == b and a.area_id.startswith("grid:") and a.district == "puri"
    assert a.name == "Puri: near Jagannath Temple"                                           # ~16 km away, in the district
    assert recover.assign_area(19.95, 85.90, GAZ).area_id != a.area_id                      # ~7 km east: another cell


def test_assign_area_far_from_every_place_is_labelled_by_coordinates():
    a = recover.assign_area(28.61, 77.21, GAZ)
    assert a.district == "delhi" and a.name.startswith("Delhi: area near 28.6") and "77.2" in a.name


def test_assign_area_without_a_position_is_unlocated():
    for lat, lon in ((None, None), (19.8, None)):
        a = recover.assign_area(lat, lon, GAZ)
        assert (a.area_id, a.name, a.district) == ("unlocated", "Unlocated", None)


def test_gazetteer_places_are_listed_once_each():
    assert [p.name for p in GAZ.places()] == ["jagannath temple", "pipili bus stand"]


# ------------------------------------------------------------------ damage levels

def m(**kw):
    base = {"tickets": 1, "reports": 1, "houses_damaged": 0, "houses_collapsed": 0, "photo_tags": {}}
    return {**base, **kw}


@pytest.mark.parametrize("metrics,level", [
    (m(tickets=0, reports=0), "none"),
    (m(), "light"),
    (m(reports=3), "light"),
    (m(reports=4), "moderate"),
    (m(houses_damaged=1), "moderate"),
    (m(photo_tags={"floodwater": 1}), "moderate"),
    (m(photo_tags={"person_injured": 1, "crops_damaged": 2}), "light"),    # not a hazard tag
    (m(houses_damaged=2, reports=3), "moderate"),
    (m(houses_damaged=3), "severe"),
    (m(houses_collapsed=1, houses_damaged=1), "severe"),
    (m(reports=8), "severe"),
])
def test_damage_level_rule(metrics, level):
    got, reason = recover.damage_level(metrics)
    assert got == level and reason and reason.endswith(".")
    if level != "none":
        assert reason.startswith(level.capitalize() + ":")


def test_damage_reason_names_the_facts():
    _, reason = recover.damage_level(m(houses_collapsed=1, houses_damaged=2, reports=5, photo_tags={"floodwater": 2}))
    assert "1 request reports a collapsed house" in reason and "2 requests report a damaged house" in reason
    assert "5 reports in all" in reason and "floodwater" in reason


# ------------------------------------------------------------------ damage map

def test_damage_map_groups_tickets_by_area(client):
    seed(client)
    dm = damage(client)
    assert set(dm) == {"generated_at", "district", "areas", "totals"} and dm["district"] == "all"
    areas = by_name(dm)
    grid_name = "Puri: near Satyabadi Bus Stand"
    assert set(areas) == {"Jagannath Temple, Puri Sadar", grid_name, "Unlocated"} | {n for n in areas if n.startswith("Delhi")}
    temple = areas["Jagannath Temple, Puri Sadar"]
    assert (temple["tickets"], temple["reports"], temple["duplicates_merged"]) == (1, 2, 1)
    assert temple["households"] == 2 and temple["households_reached"] == 2 and temple["resolved"] == 1
    assert temple["damage_level"] == "severe" and temple["houses_damaged"] == 1
    assert temple["photo_tags"] == {"floodwater": 1, "house_collapsed": 2}
    assert temple["district"] == "puri" and temple["lat"] == pytest.approx(19.805) and temple["lon"] == pytest.approx(85.8185)
    assert "collapsed" in temple["damage_reason"]
    grid = areas[grid_name]
    assert grid["houses_damaged"] == 1 and grid["damage_level"] == "moderate" and grid["households_reached"] == 0
    assert grid["by_category"] == {"RESCUE": 1} and grid["open"] == 1
    un = areas["Unlocated"]
    assert (un["tickets"], un["lat"], un["lon"], un["district"]) == (1, None, None, None)     # counted, never dropped
    assert [a["damage_level"] for a in dm["areas"]] == ["severe", "moderate", "light", "light"]  # worst first


def test_damage_map_totals_and_response_time(client):
    seed(client)
    t = damage(client)["totals"]
    assert t["tickets"] == 4 and t["reports"] == 5 and t["duplicates_merged"] == 1
    assert t["households"] == 5 and t["households_reached"] == 2 and t["needs_confirmed_closed"] == 1
    assert (t["open"], t["dispatched"], t["resolved"]) == (3, 0, 1)
    first = client.get("/v1/tickets/101").json()["first_reported_at"]
    assert t["median_minutes_to_dispatch"] == round((T0 + 60 - first) / 60, 1)
    assert t["share_with_location"] == 0.75 and t["share_with_gps_fix"] == 0.75
    assert t["houses_damaged"] == 2 and t["photo_tags"]["house_collapsed"] == 2


def test_damage_map_empty_store(client):
    dm = damage(client)
    assert dm["areas"] == [] and dm["totals"]["tickets"] == 0 and dm["totals"]["median_minutes_to_dispatch"] is None
    assert dm["totals"]["share_with_location"] == 0.0


def test_district_filter_keeps_unlocated_everywhere(client):
    seed(client)
    puri, delhi = by_name(damage(client, "puri")), by_name(damage(client, "delhi"))
    assert "Unlocated" in puri and "Unlocated" in delhi
    assert not any(n.startswith("Delhi") for n in puri) and "Jagannath Temple, Puri Sadar" not in delhi
    assert damage(client, "puri")["totals"]["tickets"] == 3 and damage(client, "delhi")["totals"]["tickets"] == 2
    assert damage(client, "delhi")["district"] == "delhi"
    assert client.get("/v1/recover/damage?district=mars").status_code == 422


def test_house_damage_from_the_intake_answer_alone(client):
    sync(client, [need_frame(origin=O_A, answers=((7, 1),))[0], need_frame(origin=O_B, lat=19.95, lon=85.83, answers=((7, 2),))[0]])
    assert damage(client)["totals"]["houses_damaged"] == 1


def test_landmark_estimate_places_a_no_fix_ticket(make_client, tmp_path, monkeypatch):
    from app import geocode

    path = tmp_path / "gaz.json"
    path.write_text(json.dumps([{"name": "jagannath temple", "lat": 19.805, "lon": 85.8178, "block": "Puri Sadar"}]), encoding="utf-8")
    monkeypatch.setenv("RELAY_GAZETTEER", str(path))
    monkeypatch.setattr(geocode, "_default", None)
    c = make_client()
    sync(c, [need_frame(lat=0.0, lon=0.0, loc_acc=0, landmark="jagannath temple")[0]])
    dm = damage(c)
    assert [a["name"] for a in dm["areas"]] == ["Jagannath Temple, Puri Sadar"]            # approximate, still placed
    assert dm["totals"]["share_with_location"] == 1.0 and dm["totals"]["share_with_gps_fix"] == 0.0
    ev = c.get("/v1/recover/evidence/101").json()
    assert ev["location"]["basis"] == "approximate" and ev["location"]["source"] == "gazetteer"
    assert ev["location"]["query"] == "jagannath temple"
    monkeypatch.setattr(geocode, "_default", None)


# ------------------------------------------------------------------ evidence

def test_ticket_evidence_content(client):
    seed(client)
    r = client.get("/v1/recover/evidence/101")
    assert r.status_code == 200 and "content-disposition" not in r.headers
    ev = r.json()
    assert ev["schema"] == "relay.evidence.v1"
    assert ev["ticket"]["ticket_no"] == 101 and ev["ticket"]["status"] == "RESOLVED" and ev["ticket"]["report_count"] == 2
    assert ev["area"] == {"area_id": "place:jagannath-temple", "name": "Jagannath Temple, Puri Sadar", "district": "puri"}
    assert ev["location"]["basis"] == "gps" and ev["location"]["accuracy_bucket"] == 1 and ev["location"]["accuracy"] == "<10 m"
    assert len(ev["reports"]) == 2 and {r_["hops"] for r_ in ev["reports"]} == {0}
    assert all(r_["reporter"].startswith("R-") and len(r_["reporter"]) == 14 for r_ in ev["reports"])
    assert len({r_["reporter"] for r_ in ev["reports"]}) == 2
    assert [e["event"] for e in ev["timeline"]] == ["first_reported", "received_by_control_room", "dispatched", "resolved"]
    assert [e["at"] for e in ev["timeline"]] == sorted(e["at"] for e in ev["timeline"])
    assert ev["photo_tags"] == {"tags": {"floodwater": 1, "house_collapsed": 2},
                                "note": "classified on the requester's phone; tags only, no image"}
    assert ev["priority"]["tier"] in ("P1", "P2", "P3", "P4") and ev["priority"]["reason"]
    assert ev["integrity"]["algorithm"] == "sha256" and len(ev["integrity"]["digest"]) == 64
    assert client.get("/v1/recover/evidence/999").status_code == 404


def test_evidence_never_contains_a_raw_origin_node(client):
    seed(client)
    texts = [client.get("/v1/recover/evidence/101").text, client.get("/v1/recover/evidence/103").text]
    area = client.get("/v1/recover/evidence?area=place:jagannath-temple").text
    report = client.post("/v1/recover/report").text
    for text in texts + [area, report]:
        for origin in (O_A, O_B, O_C, O_D, O_E):
            assert origin.hex() not in text


def test_pseudonyms_are_salted_and_stable(make_client):
    a, b = make_client(evidence_salt="one"), make_client(evidence_salt="two")
    for c in (a, b):
        sync(c, [need_frame(origin=O_A)[0]])
    ra = [x["reporter"] for x in a.get("/v1/recover/evidence/101").json()["reports"]]
    assert ra == [x["reporter"] for x in a.get("/v1/recover/evidence/101").json()["reports"]]
    assert ra != [x["reporter"] for x in b.get("/v1/recover/evidence/101").json()["reports"]]
    assert recover.pseudonym(O_A.hex(), "one") == ra[0]


def test_digest_is_canonical_stable_and_sensitive(client):
    seed(client)
    ev = client.get("/v1/recover/evidence/101").json()
    assert client.get("/v1/recover/evidence/101").json() == ev                        # nothing time-dependent in a record
    body = {k: v for k, v in ev.items() if k != "integrity"}
    canon = json.dumps(body, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    assert ev["integrity"]["digest"] == hashlib.sha256(canon).hexdigest() == recover.digest_of(ev)
    for edit in (
        lambda e: e["ticket"].update(people=ev["ticket"]["people"] + 1),
        lambda e: e["timeline"].pop(),
        lambda e: e["reports"][0].update(hops=5),
        lambda e: e["location"].update(lat=0.0),
    ):
        tampered = json.loads(json.dumps(ev))
        edit(tampered)
        assert recover.digest_of(tampered) != ev["integrity"]["digest"]
    # a real change in the store changes the digest of the export
    assert client.get("/v1/recover/evidence/102").json()["integrity"]["digest"] != ev["integrity"]["digest"]
    before = client.get("/v1/recover/evidence/102").json()["integrity"]["digest"]
    client.post("/v1/tickets/102/approve", json={"eta_min": 20})
    assert client.get("/v1/recover/evidence/102").json()["integrity"]["digest"] != before


def test_download_sets_content_disposition(client):
    seed(client)
    r = client.get("/v1/recover/evidence/101?download=1")
    assert r.headers["content-disposition"] == 'attachment; filename="relay-evidence-ticket-101.json"'
    assert r.json()["ticket"]["ticket_no"] == 101
    r = client.get("/v1/recover/evidence?area=place:jagannath-temple&download=1")
    assert r.headers["content-disposition"] == 'attachment; filename="relay-evidence-area-place-jagannath-temple.json"'


def test_area_pack_wraps_ticket_records_with_its_own_digest(client):
    seed(client)
    pack = client.get("/v1/recover/evidence?area=place:jagannath-temple&district=puri").json()
    assert pack["district"] == "puri" and pack["area"]["name"] == "Jagannath Temple, Puri Sadar"
    assert [t["ticket"]["ticket_no"] for t in pack["tickets"]] == [101]
    assert pack["tickets"][0] == client.get("/v1/recover/evidence/101").json()
    digests = [t["integrity"]["digest"] for t in pack["tickets"]]
    assert pack["integrity"] == {"algorithm": "sha256", "ticket_count": 1,
                                 "digest": hashlib.sha256(json.dumps(digests, separators=(",", ":")).encode()).hexdigest()}
    un = client.get("/v1/recover/evidence?area=unlocated").json()
    assert un["tickets"][0]["location"]["basis"] == "none"
    assert client.get("/v1/recover/evidence?area=place:nowhere").status_code == 404
    assert client.get("/v1/recover/evidence?area=place:jagannath-temple&district=delhi").status_code == 404
    assert client.get("/v1/recover/evidence").status_code == 422                       # area is required


# ------------------------------------------------------------------ report

def good_text(prompt):
    f = figures_in(prompt)
    t = f["totals"]
    return {
        "english": f"{t['tickets']} requests came from {t['households']} households; {t['resolved']} are closed.",
        "local": f"{t['tickets']} ଅନୁରୋଧ, {t['households_reached']} ପରିବାର",
    }


def test_report_without_a_model_is_the_template(client, all_langs):
    seed(client)
    r = client.post("/v1/recover/report")
    assert r.status_code == 200
    rep = r.json()
    assert set(rep) == {"generated_at", "district", "counts", "text_en", "text_local", "local_lang", "model", "evidence_digests"}
    assert rep["model"] == "template" and rep["local_lang"] == "or" and rep["generated_at"] == int(T0 + 60)
    t = rep["counts"]["totals"]
    assert t["tickets"] == 4 and t["households_reached"] == 2 and t["median_minutes_to_dispatch"] is not None
    assert isinstance(t["median_minutes_to_dispatch"], int) and t["share_with_location_pct"] == 75
    assert "4 requests were received from 5 households" in rep["text_en"]
    assert "Jagannath Temple, Puri Sadar: severe damage" in rep["text_en"] and "synthetic" in rep["text_en"]
    assert [d["ticket_no"] for d in rep["evidence_digests"]] == [101, 102, 103, 104]
    assert rep["evidence_digests"][0]["digest"] == client.get("/v1/recover/evidence/101").json()["integrity"]["digest"]
    assert [a["name"] for a in rep["counts"]["areas"]][0] == "Jagannath Temple, Puri Sadar"
    assert client.post("/v1/recover/report").json()["text_en"] == rep["text_en"]          # nothing persisted or stateful


def test_report_local_language_follows_the_languages_registry(client, monkeypatch):
    seed(client)
    # Odia is dormant by default, so Puri's report falls back to Hindi (District.local_lang); "all" reads as Puri
    for q in ("", "?district=puri"):
        rep = client.post(f"/v1/recover/report{q}").json()
        assert rep["local_lang"] == "hi" and "राहत" in rep["text_local"]
    monkeypatch.setenv("RELAY_LANGS", "en,hi,or")
    assert client.post("/v1/recover/report?district=puri").json()["local_lang"] == "or"
    assert client.post("/v1/recover/report?district=delhi").json()["local_lang"] == "hi"


def test_report_for_delhi_is_in_hindi(client):
    seed(client)
    rep = client.post("/v1/recover/report?district=delhi").json()
    assert rep["local_lang"] == "hi" and rep["district"] == "delhi" and "राहत" in rep["text_local"]
    assert rep["counts"]["totals"]["tickets"] == 2 and "Delhi" in rep["text_en"]


def test_templates_pass_their_own_validation(client):
    seed(client)
    for district in ("all", "puri", "delhi"):
        counts = client.post(f"/v1/recover/report?district={district}").json()["counts"]
        allowed = recover.allowed_numbers(counts)
        for text in (recover.template_en(counts, district), recover.template_or(counts, district),
                     recover.template_hi(counts, district)):
            assert recover.valid_text(text, counts, allowed) == text


def test_valid_model_text_is_used(make_client, all_langs):
    fake = FakeTextGen(good_text)
    c = make_client(recover_textgen=fake)
    seed(c)
    rep = c.post("/v1/recover/report?district=puri").json()
    handed = figures_in(fake.prompts[0])
    assert handed["totals"] == rep["counts"]["totals"] and handed["areas"] == rep["counts"]["areas"]
    assert handed["scope"] == "Puri" and handed["limitations"] and "Odia" in fake.prompts[0]
    assert rep["model"] == "gemini" and rep["text_en"].startswith("3 requests came from")
    assert rep["text_local"].startswith("3 ଅନୁରୋଧ")


def test_invented_number_falls_back_to_the_template(make_client):
    bad = {"english": "4 requests were received and 99 houses collapsed.", "local": "4 ଅନୁରୋଧ"}
    c = make_client(recover_textgen=FakeTextGen(lambda p: bad))
    seed(c)
    rep = c.post("/v1/recover/report").json()
    assert rep["model"] == "template" and "99" not in rep["text_en"]
    assert rep["text_en"] == recover.template_en(rep["counts"], "all")


def test_report_number_rules():
    counts = {"totals": {"tickets": 4, "households": 5, "resolved": 1}, "areas": [{"name": "Area near 28.61, 77.21", "tickets": 2}]}
    allowed = recover.allowed_numbers(counts)
    assert {4, 5, 1, 2, 28, 61, 77, 21} <= allowed and 7 not in allowed
    ok = "4 requests from 5 households in Area near 28.61, 77.21. P1 and P2 are tiers."
    assert recover.valid_text(ok, counts, allowed) == ok
    assert recover.valid_text("5 households asked", counts, allowed) is None                  # request count not stated
    assert recover.valid_text("4 requests, 7 closed", counts, allowed) is None
    assert recover.valid_text("४ requests", counts, allowed) == "४ requests"                  # Devanagari digits are read
    assert recover.valid_text("", counts, allowed) is None and recover.valid_text(4, counts, allowed) is None
    assert recover.valid_text("4 " + "x" * recover.MAX_TEXT_CHARS, counts, allowed) is None


def test_one_bad_language_or_a_failing_model_falls_back(make_client):
    for respond in (
        lambda p: {"english": "4 requests.", "local": "50 ଅନୁରୋଧ"},     # one bad language rejects both
        lambda p: RuntimeError("quota"), lambda p: TimeoutError(), lambda p: "not json", lambda p: "[]",
        lambda p: {"english": 3},
    ):
        c = make_client(recover_textgen=FakeTextGen(respond))
        seed(c)
        rep = c.post("/v1/recover/report").json()
        assert rep["model"] == "template", respond


# ------------------------------------------------------------------ auth

ROUTES = [
    ("GET", "/v1/recover/damage"),
    ("GET", "/v1/recover/evidence/101"),
    ("GET", "/v1/recover/evidence?area=unlocated"),
    ("POST", "/v1/recover/report"),
]


@pytest.mark.parametrize("method,path", ROUTES)
def test_operator_key_protects_recover_routes(make_client, method, path):
    c = make_client(operator_key="s3cret")
    sync(c, [need_frame()[0]])
    assert c.request(method, path).status_code == 401
    assert c.request(method, path, headers={"X-Operator-Key": "wrong"}).status_code == 401
    assert c.request(method, path, headers=KEY).status_code == 401
    assert c.request(method, path, headers=OP).status_code != 401
