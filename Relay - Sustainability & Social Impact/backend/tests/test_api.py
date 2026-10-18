import base64

from app import codec, templates

from conftest import GATEWAY, KEY, T0, b64, decode_acks, need_frame, sync


def test_healthz(client):
    assert client.get("/healthz").json() == {"ok": True}
    assert client.get("/v1/health").json() == {"ok": True}


def test_bad_or_missing_key_is_401(client):
    f, _ = need_frame()
    assert sync(client, [f], headers={"X-Relay-Key": "nope"}).status_code == 401
    assert sync(client, [f], headers={}).status_code == 401
    assert client.get("/v1/tickets").json() == {"tickets": []}  # nothing was ingested


def test_sync_idempotent_on_msg_id(client):
    f, _ = need_frame()
    r1 = sync(client, [f]).json()
    assert (r1["accepted"], r1["duplicates"], r1["rejected"]) == (1, 0, 0)
    r2 = sync(client, [f]).json()
    assert (r2["accepted"], r2["duplicates"], r2["rejected"]) == (0, 1, 0)
    # same frame twice in one batch
    g, _ = need_frame()
    r3 = sync(client, [g, g]).json()
    assert (r3["accepted"], r3["duplicates"]) == (1, 1)
    tickets = client.get("/v1/tickets").json()["tickets"]
    assert sum(t["report_count"] for t in tickets) == 2
    # re-upload did not queue a second RECEIVED ack
    assert client.app.state.store.ack_count() == 2


def test_rejected_frames_counted(client):
    good, need = need_frame()
    ack_frame = codec.encode_frame(codec.TYPE_ACK, 7, 1, bytes(8), b"\x01" + bytes(40), recipient_id=bytes(8))
    bad_version = bytes([2]) + good[1:]
    trailing = good + b"\x00"
    # NEED frame with a payload_version != 1
    pl = bytearray(codec.encode_need(need))
    pl[0] = 2
    bad_payload = codec.encode_frame(codec.TYPE_NEED, 7, 1, bytes(8), bytes(pl))
    r = client.post("/v1/sync", headers=KEY, json={
        "gateway_node": GATEWAY,
        "frames": [b64(good), b64(ack_frame), b64(bad_version), b64(trailing), b64(bad_payload),
                   "!!not-base64!!", "", b64(b"\x01")],
    })
    assert r.status_code == 200
    body = r.json()
    assert (body["accepted"], body["duplicates"], body["rejected"]) == (1, 0, 7)


def test_out_of_range_coordinates_rejected(client):
    f, need = need_frame()
    import dataclasses
    bad = dataclasses.replace(need, lat_e5=9_500_000)
    frame = codec.encode_frame(codec.TYPE_NEED, 7, 1, need.origin_node, codec.encode_need(bad))
    assert sync(client, [frame]).json()["rejected"] == 1


def test_validation_errors_are_422_not_500(client):
    assert client.post("/v1/sync", headers=KEY, json={"gateway_node": "xyz", "frames": []}).status_code == 422
    assert client.post("/v1/sync", headers=KEY, json={"frames": []}).status_code == 422
    assert client.post("/v1/sync", headers=KEY, json={"gateway_node": GATEWAY, "ack_cursor": -1}).status_code == 422


def test_five_near_duplicates_make_one_ticket_with_five_received_acks(client):
    # five different origins, same category/item, spread within ~150 m (0.0004 deg lat ~ 44 m)
    frames = []
    for i in range(5):
        f, _ = need_frame(category=0, item="Insulin " if i % 2 else "insulin", lat=20.29612 + 0.0003 * i * (1 if i % 2 else -1) / 2,
                          lon=85.82451, urgency=1 + (i == 3), vuln_flags=1 << (i % 3), people=i + 1, ttl=7 - i % 3)
        frames.append(f)
    body = sync(client, frames).json()
    assert body["accepted"] == 5
    tickets = client.get("/v1/tickets").json()["tickets"]
    assert len(tickets) == 1
    t = tickets[0]
    assert t["ticket_no"] == 101 and t["report_count"] == 5 and len(t["msg_ids"]) == 5
    assert t["urgency"] == 2 and t["people"] == 5          # max
    assert set(t["vuln_flags"]) == {"ELDERLY", "CHILD", "PREGNANT"}   # OR
    assert t["max_hops"] == 2                              # max(7-ttl)
    acks = decode_acks(body)
    assert len(acks) == 5
    assert all(a.status == 0 and a.ticket_no == 101 and a.eta_min == 0xFFFF for a in acks)
    assert len({a.target_node for a in acks}) == 5                       # each reporter confirmed
    assert len({a.ref_msg_id for a in acks}) == 5


def test_need_500m_away_is_separate_ticket(client):
    a, _ = need_frame(lat=20.29612, lon=85.82451)
    b, _ = need_frame(lat=20.29612 + 0.0045, lon=85.82451)  # ~500 m north
    sync(client, [a, b])
    tickets = client.get("/v1/tickets").json()["tickets"]
    assert sorted(t["ticket_no"] for t in tickets) == [101, 102]
    assert all(t["report_count"] == 1 for t in tickets)


def test_clustering_requires_same_category_matching_item_and_6h_window(client, clock):
    clock.t = T0 + 8 * 3600  # arrival time after every created_at below
    base = dict(lat=20.29612, lon=85.82451)
    frames = [
        need_frame(category=0, item="insulin", **base)[0],                           # 101
        need_frame(category=2, item="insulin", **base)[0],                           # 102 other category
        need_frame(category=0, item="oxygen", **base)[0],                            # 103 other item
        need_frame(category=0, item="", **base)[0],                                  # joins 101 (empty item)
        need_frame(category=0, item="insulin", created_at=T0 + 7 * 3600, **base)[0],  # 104 outside 6h window
        need_frame(category=0, item="insulin", loc_acc=0, **base)[0],                # 105 no-fix never merges
    ]
    sync(client, frames)
    by_no = {t["ticket_no"]: t for t in client.get("/v1/tickets").json()["tickets"]}
    assert sorted(by_no) == [101, 102, 103, 104, 105]
    assert by_no[101]["report_count"] == 2


def test_approve_queues_dispatched_acks_once(client):
    fs = [need_frame(origin=bytes([i]) * 8, lang=i, summary=f"s{i}")[0] for i in range(1, 4)]
    first = sync(client, fs).json()
    cursor = first["ack_cursor"]
    assert len(first["acks"]) == 3

    r = client.post("/v1/tickets/101/approve")
    assert r.status_code == 200 and r.json()["status"] == "DISPATCHED"
    r2 = client.post("/v1/tickets/101/approve")
    assert r2.status_code == 200 and r2.json()["status"] == "DISPATCHED"

    body = sync(client, [], cursor=cursor).json()
    acks = decode_acks(body)
    assert len(acks) == 3                         # once, not twice
    assert all(a.status == 1 and a.eta_min == 45 for a in acks)
    assert {a.target_node for a in acks} == {bytes([i]) * 8 for i in range(1, 4)}
    langs = {a.target_node[0]: a.text for a in acks}
    assert langs[1] == templates.render("DISPATCHED", "hi", 101, 45)
    assert langs[2] == templates.render("DISPATCHED", "or", 101, 45)
    assert langs[3] == templates.render("DISPATCHED", "tl", 101, 45)
    assert sync(client, [], cursor=body["ack_cursor"]).json()["acks"] == []
    assert client.post("/v1/tickets/999/approve").status_code == 404


def test_approve_distinct_origins_only(client):
    origin = bytes([9]) * 8
    # same reporter sends two near-duplicate NEEDs -> one DISPATCHED ack
    sync(client, [need_frame(origin=origin)[0], need_frame(origin=origin)[0]])
    client.post("/v1/tickets/101/approve")
    acks = decode_acks(sync(client, []).json())
    assert [a.status for a in acks].count(1) == 1
    assert [a.status for a in acks].count(0) == 2   # but each report got its own RECEIVED


def test_ack_cursor_paging_over_50(client):
    frames = [need_frame(origin=(i + 1).to_bytes(8, "big"), category=i % 7, lat=10 + i * 0.01)[0] for i in range(120)]
    assert sync(client, frames).json()["accepted"] == 120
    cursor, seen = 0, []
    pages = []
    while True:
        body = sync(client, [], cursor=cursor).json()
        if not body["acks"]:
            assert body["ack_cursor"] == cursor      # unchanged when nothing returned
            break
        pages.append(len(body["acks"]))
        seen += [a.ref_msg_id for a in decode_acks(body)]
        assert body["ack_cursor"] > cursor
        cursor = body["ack_cursor"]
    assert pages == [50, 50, 20]
    assert len(set(seen)) == 120


def test_round_trip_ack_text_in_requesters_language(client):
    for lang_idx, lang in enumerate(codec.LANG_CODES):
        origin = bytes([0x40 + lang_idx]) * 8
        f, need = need_frame(origin=origin, lang=lang_idx, lat=10.0 + lang_idx, lon=20.0)
        r = sync(client, [base64.b64decode(b64(f))]).json()
        ack = next(a for a in decode_acks(r) if a.target_node == origin)
        assert ack.ref_msg_id == need.msg_id and ack.status == 0 and ack.eta_min == 0xFFFF
        assert ack.text == templates.render("RECEIVED", lang, ack.ticket_no)
        assert str(ack.ticket_no) in ack.text


def test_priority_ordering_in_ticket_list(client):
    food, _ = need_frame(category=3, urgency=1, people=2, lat=11.0, item="rice")
    rescue, _ = need_frame(category=1, urgency=3, people=4, vuln_flags=0b10000, lat=12.0, item="boat")
    sync(client, [food, rescue])
    tickets = client.get("/v1/tickets").json()["tickets"]
    assert [t["category"] for t in tickets] == ["RESCUE", "FOOD"]
    assert tickets[0]["priority_score"] > tickets[1]["priority_score"]
    assert tickets[0]["priority_tier"] == "P1"


def test_ticket_json_shape_and_detail(client):
    f, need = need_frame(category=0, urgency=3, vuln_flags=1, lang=2, lat=20.29612, lon=85.82451,
                         item="insulin", landmark="school", summary="mother needs insulin", ttl=5)
    sync(client, [f])
    lst = client.get("/v1/tickets").json()["tickets"][0]
    assert set(lst) == {
        "ticket_no", "category", "item", "landmark", "summary", "lat", "lon", "urgency", "people",
        "vuln_flags", "lang", "report_count", "priority_score", "priority_tier", "priority_reason",
        "status", "dispatch_draft", "first_reported_at", "max_hops", "gateways", "msg_ids",
        "has_location", "loc_acc", "loc_acc_m"}
    assert (lst["ticket_no"], lst["category"], lst["lang"], lst["status"]) == (101, "MEDICAL", "or", "OPEN")
    assert (lst["lat"], lst["lon"], lst["vuln_flags"], lst["max_hops"]) == (20.29612, 85.82451, ["ELDERLY"], 2)
    assert (lst["has_location"], lst["loc_acc"], lst["loc_acc_m"]) == (True, 1, "<10 m")
    assert lst["gateways"] == [GATEWAY] and lst["msg_ids"] == [need.msg_id.hex()]
    assert lst["first_reported_at"] == T0
    assert set(lst["dispatch_draft"]) == {"en", "local"}
    assert lst["dispatch_draft"]["en"].startswith(f"[{lst['priority_tier']}] MEDICAL — insulin for 1 person (elderly). "
                                                  "Location: 20.29612, 85.82451 near school. Reports: 1.")
    assert lst["dispatch_draft"]["local"] == "[EN] " + lst["dispatch_draft"]["en"]
    det = client.get("/v1/tickets/101").json()
    assert det["packets"] == [{"msg_id": need.msg_id.hex(), "origin_node": need.origin_node.hex(),
                               "gateway_node": GATEWAY, "hops": 2, "loc_acc": 1, "received_at": T0 + 60,
                               "summary": "mother needs insulin"}]
    assert {k: v for k, v in det.items() if k != "packets"} == lst
    assert client.get("/v1/tickets/12345").status_code == 404


def test_stats_and_gateway_online_window(client, clock):
    sync(client, [need_frame(category=0)[0], need_frame(category=3, lat=1.0)[0]])
    s = client.get("/v1/stats").json()
    assert s["tickets"] == 2 and s["gateways_online"] == 1
    assert s["open_by_category"]["MEDICAL"] == 1 and s["open_by_category"]["FOOD"] == 1
    assert set(s) == {"open_by_category", "gateways_online", "tickets", "open", "p1_open", "oldest_open_at",
                      "teams_free", "teams_total"}
    assert (s["open"], s["oldest_open_at"], s["teams_free"], s["teams_total"]) == (2, T0, 6, 6)
    client.post("/v1/tickets/101/approve")
    assert client.get("/v1/stats").json()["open_by_category"]["MEDICAL"] == 0
    clock.t += 61
    assert client.get("/v1/stats").json()["gateways_online"] == 0


def test_hops_clamped_and_bad_device_clock_sanitised(client, clock):
    f, _ = need_frame(ttl=200, created_at=0)  # ttl above 7, created_at in 1970
    sync(client, [f])
    t = client.get("/v1/tickets/101").json()
    assert t["max_hops"] == 0
    assert t["first_reported_at"] == int(clock.t)


def test_dev_inject_three_needs(client):
    needs = [
        {"category": "MEDICAL", "urgency": 3, "people": 1, "vuln_flags": ["ELDERLY"], "lat": 20.29612,
         "lon": 85.82451, "lang": "or", "item": "insulin", "landmark": "school", "summary": "mother needs insulin"},
        {"category": "rescue", "urgency": 3, "people": 5, "vuln_flags": ["TRAPPED", "CHILD"], "lat": 11.2433,
         "lon": 125.0039, "lang": "war", "item": "boat", "landmark": "church", "summary": "roof, water rising"},
        {"category": "FOOD", "urgency": 1, "people": 12, "vuln_flags": [], "lat": 11.2441, "lon": 125.0050,
         "lang": "ceb", "item": "rice", "landmark": "", "summary": "no food 2 days", "origin_node": "00ff00ff00ff00ff"},
    ]
    r = client.post("/v1/dev/inject", json={"needs": needs})
    assert r.status_code == 200
    assert r.json()["accepted"] == 3 and r.json()["ticket_nos"] == [101, 102, 103]
    tickets = client.get("/v1/tickets").json()["tickets"]
    assert [t["category"] for t in tickets] == ["RESCUE", "MEDICAL", "FOOD"]
    assert {t["lang"] for t in tickets} == {"or", "war", "ceb"}
    # injected needs also get RECEIVED acks, the last to the requested origin
    acks = decode_acks(sync(client, []).json())
    assert len(acks) == 3 and acks[2].target_node.hex() == "00ff00ff00ff00ff"
    assert client.post("/v1/dev/inject", json={"needs": [{**needs[0], "category": "LOL"}]}).status_code == 422


def test_dev_inject_disabled_in_prod(make_client):
    c = make_client(env="prod")
    r = c.post("/v1/dev/inject", json={"needs": []})
    assert r.status_code == 404
    assert c.get("/healthz").status_code == 200


def test_cors_allows_dashboard_origin(client):
    r = client.get("/healthz", headers={"Origin": "http://localhost:5173"})
    assert r.headers["access-control-allow-origin"] == "http://localhost:5173"
    r = client.options("/v1/tickets", headers={"Origin": "http://127.0.0.1:5173", "Access-Control-Request-Method": "GET"})
    assert r.headers["access-control-allow-origin"] == "http://127.0.0.1:5173"
    r = client.get("/healthz", headers={"Origin": "http://evil.example"})
    assert "access-control-allow-origin" not in r.headers


def test_ticket_numbers_start_at_101_and_survive_reopen(tmp_path, clock):
    from fastapi.testclient import TestClient
    from app.main import create_app

    path = str(tmp_path / "persist.db")
    c1 = TestClient(create_app(db_path=path, clock=clock, env="dev"))
    sync(c1, [need_frame(lat=1.0)[0]])
    assert [t["ticket_no"] for t in c1.get("/v1/tickets").json()["tickets"]] == [101]
    c1.app.state.store.close()
    c2 = TestClient(create_app(db_path=path, clock=clock, env="dev"))
    sync(c2, [need_frame(lat=2.0)[0]])
    assert sorted(t["ticket_no"] for t in c2.get("/v1/tickets").json()["tickets"]) == [101, 102]
    c2.app.state.store.close()


def test_device_key_compare_is_constant_time(client, monkeypatch):
    import hmac

    calls = []
    real = hmac.compare_digest

    def spy(a, b):
        calls.append((type(a), type(b)))
        return real(a, b)

    monkeypatch.setattr("app.main.hmac.compare_digest", spy)
    f, _ = need_frame()
    assert sync(client, [f], headers={"X-Relay-Key": "dev-relay-key"}).status_code == 200
    assert sync(client, [f], headers={"X-Relay-Key": "dev-relay-kez"}).status_code == 401
    assert sync(client, [f], headers={"X-Relay-Key": "short"}).status_code == 401
    assert sync(client, [f], headers={"X-Relay-Key": "dev-relay-key-and-more"}).status_code == 401
    assert sync(client, [f], headers={"X-Relay-Key": b"\xff\xfekey"}).status_code == 401   # non-ASCII must not 500
    assert len(calls) == 5            # every attempt with a header goes through compare_digest


def test_healthz_never_touches_the_store(make_client):
    class Exploding:
        def __getattr__(self, name):
            raise AssertionError(f"store.{name} touched by /healthz")

    from fastapi.testclient import TestClient
    from app.main import create_app

    c = TestClient(create_app(store=Exploding(), pubsub_topic="", static_dir=""))
    assert c.get("/healthz").json() == {"ok": True}


def test_firestore_store_is_lazy_and_healthz_needs_no_credentials(monkeypatch):
    """Selecting RELAY_STORE=firestore must not build a Firestore client (or look for ADC)
    until the first data request."""
    from fastapi.testclient import TestClient
    from app.main import create_app

    monkeypatch.setenv("RELAY_STORE", "firestore")
    monkeypatch.setenv("RELAY_FIRESTORE_DB", "relay-abc123")
    monkeypatch.setenv("GOOGLE_CLOUD_PROJECT", "some-project")
    c = TestClient(create_app(pubsub_topic="", static_dir=""))
    assert c.get("/healthz").json() == {"ok": True}
    store = c.app.state.store
    assert type(store).__name__ == "FirestoreStore" and store._client is None
    assert (store._project, store._database) == ("some-project", "relay-abc123")


def test_firestore_client_uses_project_and_named_database(monkeypatch):
    from app.store import FirestoreStore
    import google.cloud.firestore as fs

    seen = {}

    class FakeClient:
        def __init__(self, project=None, database=None, **kw):
            seen.update(project=project, database=database)

    monkeypatch.setattr(fs, "Client", FakeClient)
    FirestoreStore(project="p1", database="relay-0a1b2c").db
    assert seen == {"project": "p1", "database": "relay-0a1b2c"}


def test_unknown_store_kind_is_rejected():
    import pytest as _pytest
    from app.store import open_store

    with _pytest.raises(ValueError):
        open_store("postgres")


def test_sqlite_default_path_falls_back_to_tmp_when_cwd_unwritable(monkeypatch, tmp_path):
    import os
    from app.store.sqlite import default_db_path

    monkeypatch.delenv("RELAY_DB", raising=False)
    monkeypatch.setattr(os, "access", lambda p, m: True)
    assert default_db_path() == "relay.db"
    monkeypatch.setattr(os, "access", lambda p, m: False)
    assert default_db_path().endswith("relay.db") and os.path.isabs(default_db_path())
    monkeypatch.setenv("RELAY_DB", str(tmp_path / "x.db"))
    assert default_db_path() == str(tmp_path / "x.db")


# ------------------------------------------------------------------ location quality (no GPS fix)

def test_no_fix_ticket_has_null_coordinates_and_says_so(client):
    f, _ = need_frame(category=1, lat=0.0, lon=0.0, loc_acc=0, landmark="temple", item="rescue",
                      summary="stuck on roof")
    sync(client, [f])
    t = client.get("/v1/tickets").json()["tickets"][0]
    assert (t["has_location"], t["loc_acc"], t["loc_acc_m"]) == (False, 0, None)
    assert t["lat"] is None and t["lon"] is None
    assert "Location: unknown (no GPS fix) — near temple. Reports: 1." in t["dispatch_draft"]["en"]
    assert "0.00000" not in t["dispatch_draft"]["en"]
    assert "no GPS fix — landmark only" in t["priority_reason"]
    det = client.get(f"/v1/tickets/{t['ticket_no']}").json()
    assert det["lat"] is None and det["has_location"] is False and det["packets"][0]["loc_acc"] == 0


def test_no_fix_ticket_without_landmark(client):
    f, _ = need_frame(lat=0.0, lon=0.0, loc_acc=0, landmark="")
    sync(client, [f])
    t = client.get("/v1/tickets").json()["tickets"][0]
    assert "Location: unknown (no GPS fix). Reports: 1." in t["dispatch_draft"]["en"]
    assert "no GPS fix — location unknown" in t["priority_reason"]


def test_fix_ticket_is_unchanged_and_reports_best_accuracy(client):
    a, _ = need_frame(loc_acc=4)
    b, _ = need_frame(loc_acc=2, lat=20.29615)   # merges into the same ticket; better fix
    c, _ = need_frame(loc_acc=5, lat=20.29618)
    sync(client, [a, b, c])
    (t,) = client.get("/v1/tickets").json()["tickets"]
    assert t["report_count"] == 3
    assert (t["has_location"], t["loc_acc"], t["loc_acc_m"]) == (True, 2, "<50 m")
    assert (t["lat"], t["lon"]) == (20.29612, 85.82451)
    assert "Location: 20.29612, 85.82451 near school." in t["dispatch_draft"]["en"]
    assert "no GPS fix" not in t["priority_reason"]


def test_no_fix_and_fix_tickets_coexist_and_never_merge(client):
    nofix, _ = need_frame(lat=0.0, lon=0.0, loc_acc=0)
    fix, _ = need_frame(lat=0.0, lon=0.0, loc_acc=3)   # a fix claiming 0,0 must not glue onto the no-fix ticket
    sync(client, [nofix, fix])
    ts = {t["ticket_no"]: t for t in client.get("/v1/tickets").json()["tickets"]}
    assert ts[101]["has_location"] is False and ts[102]["has_location"] is True
    assert ts[102]["loc_acc_m"] == "<200 m"


def test_loc_acc_label_buckets():
    from app.location import best_loc_acc, loc_acc_label
    assert [loc_acc_label(i) for i in range(6)] == [None, "<10 m", "<50 m", "<200 m", "<1 km", "≥1 km"]
    assert best_loc_acc([0, 5, 3]) == 3 and best_loc_acc([0, 0]) == 0 and best_loc_acc([]) == 0


def test_no_fix_ticket_stored_before_this_change_renders_without_zero_coordinates(client):
    """Rows triaged by older code have the 0,0 placeholder baked into their text."""
    f, _ = need_frame(lat=0.0, lon=0.0, loc_acc=0, landmark="school")
    sync(client, [f])
    old_en = "[P2] MEDICAL — insulin for 1 person. Location: 0.00000, 0.00000 near school. Reports: 1. Waiting: 0.1 h."
    old_reason = "P2 (score 10.5): category MEDICAL +5.0."
    assert client.app.state.store.update_ticket_if_unchanged(101, 1, {
        "dispatch_draft_en": old_en, "dispatch_draft_local": "[EN] " + old_en, "priority_reason": old_reason})
    t = client.get("/v1/tickets").json()["tickets"][0]
    assert t["dispatch_draft"]["en"] == ("[P2] MEDICAL — insulin for 1 person. "
                                         "Location: unknown (no GPS fix) — near school. Reports: 1. Waiting: 0.1 h.")
    assert t["dispatch_draft"]["local"].startswith("[EN] ") and "0.00000" not in t["dispatch_draft"]["local"]
    assert t["priority_reason"] == old_reason + " Note: no GPS fix — landmark only."
