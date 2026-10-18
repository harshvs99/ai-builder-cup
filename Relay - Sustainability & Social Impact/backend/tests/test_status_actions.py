"""on-the-way / resolve: transition matrix, per-reporter ACKs, team load, counts.

Uses the parametrised ``client`` fixture: every test runs on SQLite and, with the emulator up, Firestore."""

import sqlite3

import pytest

from app import codec, templates
from app.agent import build_ack
from app.codec import LANG_CODES, STATUS_DISPATCHED, STATUS_ON_THE_WAY, STATUS_RESOLVED
from app.dispatch import build_status_ack
from app.store import SqliteStore

from conftest import T0, decode_acks, need_frame, sync


def one_ticket(client, n_origins=1, **kw):
    """One ticket (101) reported by ``n_origins`` reporters, origin i writing language i."""
    fs = [need_frame(origin=bytes([i]) * 8, lang=i % len(LANG_CODES), **kw)[0] for i in range(1, n_origins + 1)]
    sync(client, fs)


def status_of(client, no=101):
    return client.get(f"/v1/tickets/{no}").json()["status"]


def new_acks(client, cursor):
    return decode_acks(sync(client, [], cursor=cursor).json())


def to_state(client, state):
    if state in ("DISPATCHED", "ON_THE_WAY", "RESOLVED"):
        assert client.post("/v1/tickets/101/approve").status_code == 200
    if state in ("ON_THE_WAY",):
        assert client.post("/v1/tickets/101/on-the-way").status_code == 200
    if state == "RESOLVED":
        assert client.post("/v1/tickets/101/resolve").status_code == 200
    assert status_of(client) == state


@pytest.mark.parametrize("state,action,expected", [
    ("OPEN", "on-the-way", 409),
    ("OPEN", "resolve", 409),
    ("DISPATCHED", "on-the-way", 200),
    ("DISPATCHED", "resolve", 200),
    ("ON_THE_WAY", "on-the-way", 200),   # retry: idempotent
    ("ON_THE_WAY", "resolve", 200),
    ("RESOLVED", "on-the-way", 409),
    ("RESOLVED", "resolve", 200),        # retry: idempotent
])
def test_transition_matrix(client, state, action, expected):
    one_ticket(client)
    to_state(client, state)
    r = client.post(f"/v1/tickets/101/{action}")
    assert r.status_code == expected, r.text
    if expected == 200:
        assert r.json()["status"] == {"on-the-way": "ON_THE_WAY", "resolve": "RESOLVED"}[action]
    else:
        assert status_of(client) == state   # a refused action changes nothing


@pytest.mark.parametrize("action", ["on-the-way", "resolve"])
def test_unknown_ticket_is_404(client, action):
    assert client.post(f"/v1/tickets/999/{action}").status_code == 404


def test_on_the_way_acks_per_reporter_in_their_language_with_eta(client):
    one_ticket(client, 3)
    client.post("/v1/tickets/101/approve")
    cursor = sync(client, []).json()["ack_cursor"]
    r = client.post("/v1/tickets/101/on-the-way", json={"eta_min": 20})
    assert r.status_code == 200
    assert r.json()["dispatch"]["on_the_way_at"] == T0 + 60 and r.json()["dispatch"]["eta_min"] == 20
    client.post("/v1/tickets/101/on-the-way", json={"eta_min": 20})   # retry queues nothing more

    body = sync(client, [], cursor=cursor).json()
    acks = decode_acks(body)
    assert len(acks) == 3 and all(a.status == STATUS_ON_THE_WAY == 2 and a.eta_min == 20 for a in acks)
    assert {a.target_node for a in acks} == {bytes([i]) * 8 for i in range(1, 4)}
    for a in acks:
        assert a.text == templates.render("ON_THE_WAY", LANG_CODES[a.target_node[0]], 101, 20)
    assert sync(client, [], cursor=body["ack_cursor"]).json()["acks"] == []


def test_on_the_way_without_eta_has_unknown_eta_and_no_eta_wording(client):
    one_ticket(client, 2)
    client.post("/v1/tickets/101/approve")
    cursor = sync(client, []).json()["ack_cursor"]
    assert client.post("/v1/tickets/101/on-the-way").status_code == 200
    acks = new_acks(client, cursor)
    assert len(acks) == 2
    for a in acks:
        assert a.status == 2 and a.eta_min == codec.ETA_UNKNOWN
        assert a.text == templates.render("ON_THE_WAY_NO_ETA", LANG_CODES[a.target_node[0]], 101)
        assert "{" not in a.text and "None" not in a.text


def test_on_the_way_rejects_a_bad_eta(client):
    one_ticket(client)
    client.post("/v1/tickets/101/approve")
    assert client.post("/v1/tickets/101/on-the-way", json={"eta_min": -1}).status_code == 422
    assert client.post("/v1/tickets/101/on-the-way", json={"eta_min": 70000}).status_code == 422


@pytest.mark.parametrize("path", ["direct", "via_on_the_way"])
def test_resolve_acks_per_reporter_in_their_language(client, path):
    one_ticket(client, 3)
    client.post("/v1/tickets/101/approve")
    if path == "via_on_the_way":
        client.post("/v1/tickets/101/on-the-way")
    cursor = sync(client, []).json()["ack_cursor"]
    r = client.post("/v1/tickets/101/resolve")
    assert r.json()["dispatch"]["resolved_at"] == T0 + 60
    client.post("/v1/tickets/101/resolve")                            # retry
    acks = new_acks(client, cursor)
    assert len(acks) == 3 and all(a.status == STATUS_RESOLVED == 3 and a.eta_min == codec.ETA_UNKNOWN for a in acks)
    for a in acks:
        assert a.text == templates.render("RESOLVED", LANG_CODES[a.target_node[0]], 101)


def test_templates_exist_for_every_supported_language():
    for kind in ("ON_THE_WAY", "ON_THE_WAY_NO_ETA", "RESOLVED"):
        assert set(templates.TEMPLATES[kind]) | set(templates.CONTRIB_TEMPLATES[kind]) == set(codec.LANG_CODES)
    assert templates.status_text_and_eta(STATUS_ON_THE_WAY, "en", 7) == ("Help is on the way for request #7.", codec.ETA_UNKNOWN)
    assert templates.status_text_and_eta(STATUS_RESOLVED, "en", 7) == ("Request #7 is resolved. Stay safe.", codec.ETA_UNKNOWN)


def test_resolve_releases_the_team_but_on_the_way_does_not(client):
    one_ticket(client)
    team = client.get("/v1/teams").json()[0]["id"]
    assert client.post("/v1/tickets/101/approve", json={"team_id": team}).status_code == 200
    load = lambda: {t["id"]: t["load"] for t in client.get("/v1/teams").json()}[team]
    assert load() == 1
    client.post("/v1/tickets/101/on-the-way")
    assert load() == 1
    r = client.post("/v1/tickets/101/resolve")
    assert load() == 0
    d = r.json()["dispatch"]
    assert d["team_id"] == team and d["resolved_at"] and d["on_the_way_at"] and d["approved_at"]   # history kept


def test_resolve_after_a_plain_approval_creates_the_dispatch_record(client):
    one_ticket(client)
    client.post("/v1/tickets/101/approve")       # no body: no dispatch record yet
    d = client.post("/v1/tickets/101/resolve").json()["dispatch"]
    assert d["resolved_at"] == T0 + 60 and d["team_id"] is None


def test_sitrep_resolved_count_and_stats_exclude_resolved(client):
    sync(client, [
        need_frame(category=0, urgency=3, people=9, lat=10.0, item="a")[0],
        need_frame(category=3, urgency=0, lat=11.0, item="b")[0],
        need_frame(category=1, urgency=1, lat=12.0, item="c")[0],
    ])
    for no in (101, 102):
        client.post(f"/v1/tickets/{no}/approve")
    client.post("/v1/tickets/101/resolve")
    client.post("/v1/tickets/102/on-the-way")

    stats = client.get("/v1/stats").json()
    assert stats["open"] == 1 and stats["tickets"] == 3
    assert stats["open_by_category"]["MEDICAL"] == 0 and stats["open_by_category"]["FOOD"] == 0
    assert stats["open_by_category"]["RESCUE"] == 1

    counts = client.post("/v1/sitreps/run?district=puri").json()["counts"]
    assert counts["resolved"] == 1 and counts["open"] == 1
    assert counts["dispatched"] == 1                 # ON_THE_WAY still counts as in the field


def test_resolved_ticket_never_attracts_a_merge(client):
    one_ticket(client)
    client.post("/v1/tickets/101/approve")
    client.post("/v1/tickets/101/resolve")
    one_ticket(client)
    assert {t["ticket_no"] for t in client.get("/v1/tickets").json()["tickets"]} == {101, 102}


def test_store_advance_ticket_directly(client):
    store = client.app.state.store
    one_ticket(client)

    def mk(p):
        return build_status_ack(
            ref_msg_id=p["msg_id"], target_node=p["origin_node"], ticket_no=101, lang="en", status=STATUS_RESOLVED)

    assert store.advance_ticket(101, "RESOLVED", T0, mk) == "conflict"
    assert store.advance_ticket(999, "RESOLVED", T0, mk) is None
    store.approve_ticket(101, T0, lambda p: build_ack(
        ref_msg_id=p["msg_id"], target_node=p["origin_node"], ticket_no=101, status=STATUS_DISPATCHED, lang="en"))
    before = store.ack_count()
    assert store.advance_ticket(101, "RESOLVED", T0, mk, patch={"resolved_at": T0}) == "done"
    assert store.ack_count() == before + 1
    assert store.advance_ticket(101, "RESOLVED", T0, mk) == "noop" and store.ack_count() == before + 1


# ------------------------------------------------------------------ SQLite schema migration

def test_old_sqlite_database_gains_the_new_statuses(tmp_path):
    path = str(tmp_path / "old.db")
    s = SqliteStore(path)
    s.list_tickets()                                   # create the current schema
    s.close()
    # rewrite the table as the previous release created it (CHECK allows two statuses only)
    conn = sqlite3.connect(path)
    old_sql = conn.execute("SELECT sql FROM sqlite_master WHERE name = 'tickets'").fetchone()[0].replace(
        "'OPEN','DISPATCHED','ON_THE_WAY','RESOLVED'", "'OPEN','DISPATCHED'")
    conn.execute("DROP TABLE tickets")
    conn.execute(old_sql)
    conn.execute("INSERT INTO tickets(category, lat_e5, lon_e5, urgency, people, vuln_flags, lang, status, "
                 "first_reported_at, created_at, updated_at) VALUES ('FOOD', 1, 2, 1, 1, 0, 'en', 'DISPATCHED', 5, 5, 5)")
    conn.commit()
    with pytest.raises(sqlite3.IntegrityError):
        conn.execute("UPDATE tickets SET status = 'RESOLVED'")
    conn.close()

    s = SqliteStore(path)
    assert s.get_ticket(1)["status"] == "DISPATCHED"   # row survived the rebuild
    s.update_ticket(1, status="RESOLVED")
    assert s.get_ticket(1)["status"] == "RESOLVED"
    s.close()
    s = SqliteStore(path)                              # second open: no second rebuild
    assert s.get_ticket(1)["status"] == "RESOLVED"
    s.close()
