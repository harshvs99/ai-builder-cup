"""Intake answers (NEED payload v2): decoded at sync, stored on the ticket, served, merged first-report-wins.
Every API-level test here runs against both stores via the ``client`` fixture."""

import sqlite3

from app.store.sqlite import SqliteStore

from conftest import need_frame, sync


def _tickets(client):
    return client.get("/v1/tickets").json()["tickets"]


def test_v2_need_stores_and_serves_answers(client):
    f, _ = need_frame(answers=((1, 2), (2, 1)))  # conscious=no, road_reachable=yes
    assert sync(client, [f]).json()["accepted"] == 1
    (t,) = _tickets(client)
    assert t["answers"] == {"conscious": "no", "road_reachable": "yes"}
    assert client.get(f"/v1/tickets/{t['ticket_no']}").json()["answers"] == t["answers"]


def test_v1_need_has_no_answers_field(client):
    f, _ = need_frame()
    sync(client, [f])
    (t,) = _tickets(client)
    assert "answers" not in t


def test_merged_ticket_keeps_first_answers_and_later_reports_fill_empty_keys(client):
    f1, _ = need_frame(answers=((1, 2),))                  # conscious=no
    f2, _ = need_frame(answers=((1, 1), (2, 1)))           # conscious=yes (ignored), road_reachable=yes (fills)
    f3, _ = need_frame(answers=((2, 2), (7, 1)))           # road_reachable=no (ignored), house_damaged=yes (fills)
    for f in (f1, f2, f3):
        sync(client, [f])
    (t,) = _tickets(client)
    assert t["report_count"] == 3
    assert t["answers"] == {"conscious": "no", "road_reachable": "yes", "house_damaged": "yes"}


def test_later_report_with_answers_fills_a_ticket_that_had_none(client):
    sync(client, [need_frame()[0]])
    sync(client, [need_frame(answers=((1, 0),))[0]])       # "unknown" is a real answer
    (t,) = _tickets(client)
    assert t["report_count"] == 2 and t["answers"] == {"conscious": "unknown"}


def test_v1_report_merging_into_answered_ticket_changes_nothing(client):
    sync(client, [need_frame(answers=((1, 2),))[0]])
    sync(client, [need_frame()[0]])
    (t,) = _tickets(client)
    assert t["report_count"] == 2 and t["answers"] == {"conscious": "no"}


def test_dev_inject_accepts_answers(client):
    body = {"needs": [{"category": "MEDICAL", "urgency": 3, "lat": 20.29612, "lon": 85.82451, "item": "insulin",
                       "answers": {"conscious": "no", "road_reachable": "unknown"}}]}
    assert client.post("/v1/dev/inject", json=body).status_code == 200
    assert _tickets(client)[0]["answers"] == {"conscious": "no", "road_reachable": "unknown"}
    for bad in ({"conscious": "maybe"}, {"nope": "yes"}, {"conscious": "yes", "road_reachable": "yes", "water_rising": "yes",
                                                              "house_damaged": "yes"}):
        body["needs"][0]["answers"] = bad
        assert client.post("/v1/dev/inject", json=body).status_code == 422


def test_sqlite_database_created_before_answers_is_migrated(tmp_path):
    path = str(tmp_path / "old.db")
    store = SqliteStore(path)
    store._connect()
    store.close()
    conn = sqlite3.connect(path)
    conn.execute("ALTER TABLE tickets DROP COLUMN answers")  # what a pre-answers database looks like
    conn.commit()
    conn.close()
    reopened = SqliteStore(path)
    cols = {r["name"] for r in reopened._connect().execute("PRAGMA table_info(tickets)")}
    assert "answers" in cols
    reopened.close()
