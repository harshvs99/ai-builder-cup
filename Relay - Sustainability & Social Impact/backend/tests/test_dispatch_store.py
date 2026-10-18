"""Store methods behind teams, dispatch records, ticket counts and sitreps.

Uses the parametrised ``client`` fixture, so every test runs on SQLite and (with the emulator up)
on Firestore.
"""

import pytest

from app import dispatch, teams
from app.store import TeamAtCapacity

from conftest import T0, need_frame, sync


def make_ack_for(ticket_no, eta=30):
    return lambda p: dispatch.build_dispatch_ack(
        ref_msg_id=p["msg_id"], target_node=p["origin_node"], ticket_no=ticket_no, lang="en", eta_min=eta
    )


def open_tickets(client, n, **kw):
    sync(client, [need_frame(lat=10.0 + i * 0.01, item=f"item{i}", **kw)[0] for i in range(n)])


def dispatch_info(team_id, now=T0):
    return {"team_id": team_id, "team_name": team_id.upper(), "eta_min": 30, "approved_at": now}


def test_seed_teams_is_create_if_absent(client):
    store = client.app.state.store
    assert store.list_teams() == []
    store.seed_teams(teams.load_seed())
    store.seed_teams(teams.load_seed())                    # idempotent
    listed = store.list_teams()
    assert [t["id"] for t in listed] == sorted(t["id"] for t in teams.load_seed()) and len(listed) == 6
    assert set(listed[0]) == {"id", "name", "kind", "capacity", "base_lat", "base_lon", "status"}
    assert store.set_team_status("sup-gop", "off") is True
    assert store.set_team_status("nope", "off") is False
    store.seed_teams(teams.load_seed())                    # does not undo an operator's change
    assert {t["id"]: t["status"] for t in store.list_teams()}["sup-gop"] == "off"


def test_proposals_roundtrip_and_clear(client):
    store = client.app.state.store
    assert store.dispatch_records() == {} and store.get_dispatch_record(101) is None
    p = {"team_id": "boat-puri", "team_name": "Puri Rescue Boat 1", "eta_min": 25, "order_en": "go",
         "order_local": None, "local_lang": None, "reason": "r", "proposed_at": T0}
    store.set_proposals({101: p, 102: p})
    assert store.get_dispatch_record(101) == {"proposal": p, "dispatch": None}
    assert set(store.dispatch_records()) == {101, 102}
    store.set_proposals({101: None})
    assert store.get_dispatch_record(101) == {"proposal": None, "dispatch": None}
    assert store.get_dispatch_record(102)["proposal"] == p
    assert store.team_loads() == {}                        # a proposal is not load


def test_approve_with_dispatch_records_load_and_keeps_the_proposal(client):
    store = client.app.state.store
    open_tickets(client, 2)
    store.set_proposals({101: {"team_id": "med-pipili", "eta_min": 5}})
    assert store.approve_ticket(101, T0, make_ack_for(101), dispatch=dispatch_info("med-pipili"), capacity=2) is True
    rec = store.get_dispatch_record(101)
    assert rec["dispatch"] == dispatch_info("med-pipili") and rec["proposal"] == {"team_id": "med-pipili", "eta_min": 5}
    assert store.team_loads() == {"med-pipili": 1}
    assert store.approve_ticket(101, T0, make_ack_for(101), dispatch=dispatch_info("med-pipili"), capacity=1) is False
    assert store.team_loads() == {"med-pipili": 1}         # second approval is a no-op, not a capacity error
    assert store.approve_ticket(999, T0, make_ack_for(999), dispatch=dispatch_info("med-pipili"), capacity=1) is None


def test_approve_over_capacity_raises_and_writes_nothing(client):
    store = client.app.state.store
    open_tickets(client, 3)
    acks = store.ack_count()
    for no in (101, 102):
        store.approve_ticket(no, T0, make_ack_for(no), dispatch=dispatch_info("boat-puri"), capacity=2)
    after_two = store.ack_count()
    assert after_two == acks + 2
    with pytest.raises(TeamAtCapacity):
        store.approve_ticket(103, T0, make_ack_for(103), dispatch=dispatch_info("boat-puri"), capacity=2)
    assert store.get_ticket(103)["status"] == "OPEN"
    assert store.get_dispatch_record(103) is None
    assert store.ack_count() == after_two and store.team_loads() == {"boat-puri": 2}
    # another team has room; and no team at all never checks capacity
    assert store.approve_ticket(103, T0, make_ack_for(103), dispatch=dispatch_info("boat-brahmagiri"), capacity=2) is True
    assert store.team_loads() == {"boat-puri": 2, "boat-brahmagiri": 1}


def test_approve_without_dispatch_leaves_no_record(client):
    store = client.app.state.store
    open_tickets(client, 1)
    assert store.approve_ticket(101, T0, make_ack_for(101)) is True
    assert store.get_dispatch_record(101) is None and store.team_loads() == {}


def test_release_dispatch_frees_the_team(client):
    store = client.app.state.store
    open_tickets(client, 2)
    for no in (101, 102):
        store.approve_ticket(no, T0, make_ack_for(no), dispatch=dispatch_info("sup-gop"), capacity=5)
    assert store.team_loads() == {"sup-gop": 2}
    store.release_dispatch(101)
    store.release_dispatch(555)                            # unknown ticket: harmless
    assert store.team_loads() == {"sup-gop": 1}
    assert store.get_dispatch_record(101)["dispatch"] == dispatch_info("sup-gop")   # history is kept


def test_ticket_counts(client):
    store = client.app.state.store
    empty = store.ticket_counts()
    assert (empty["open"], empty["p1_open"], empty["dispatched"], empty["resolved"]) == (0, 0, 0, 0)
    assert empty["oldest_open_no"] is None and empty["oldest_open_at"] is None and empty["p1_open_nos"] == []
    assert sum(empty["open_by_category"].values()) == 0 and "MEDICAL" in empty["open_by_category"]

    sync(client, [
        need_frame(category=0, urgency=3, people=9, vuln_flags=3, lat=10.0, item="a", created_at=T0 + 100)[0],  # P1
        need_frame(category=3, urgency=0, people=1, lat=11.0, item="b", created_at=T0 + 50)[0],                 # P4
        need_frame(category=1, urgency=3, people=9, vuln_flags=3, lat=12.0, item="c", created_at=T0 + 70)[0],   # P1
    ])
    store.approve_ticket(102, T0, make_ack_for(102))
    c = store.ticket_counts()
    tiers = {t["ticket_no"]: t["priority_tier"] for t in client.get("/v1/tickets").json()["tickets"]}
    expected_p1 = [n for n in (101, 103) if tiers[n] == "P1"]
    assert (c["open"], c["dispatched"], c["resolved"]) == (2, 1, 0)
    assert c["open_by_category"]["MEDICAL"] == 1 and c["open_by_category"]["RESCUE"] == 1
    assert c["open_by_category"]["FOOD"] == 0                      # dispatched, no longer open
    assert sorted(c["p1_open_nos"]) == sorted(expected_p1) and c["p1_open"] == len(expected_p1) >= 1
    assert (c["oldest_open_no"], c["oldest_open_at"]) == (103, T0 + 70)


def test_sitreps_get_sequential_ids_and_latest(client):
    store = client.app.state.store
    assert store.latest_sitrep() is None
    body = {"period_start": 1, "period_end": 2, "generated_at": 2, "counts": {"open": 1, "open_by_category": {"FOOD": 1}},
            "text_en": "a", "text_local": "b", "local_lang": "or", "ticket_nos": [101]}
    first = store.add_sitrep(body)
    second = store.add_sitrep({**body, "generated_at": 3, "text_en": "c"})
    assert (first["id"], second["id"]) == (1, 2)
    assert {k: v for k, v in first.items() if k != "id"} == body
    assert store.latest_sitrep() == second
