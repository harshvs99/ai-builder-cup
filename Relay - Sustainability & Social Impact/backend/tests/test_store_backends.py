"""Behaviour every Store backend must share (SQLite always; Firestore when the emulator is up),
plus Firestore-only concurrency tests that use two independent stores as two Cloud Run instances."""

import concurrent.futures as cf
import threading

import pytest
from fastapi.testclient import TestClient

from app.agent import IncomingNeed
from app.codec import STATUS_DISPATCHED, decode_frame, decode_need
from app.main import create_app

from conftest import EMULATOR_PROJECT, GATEWAY, T0, _clear_emulator, _emulator_host, b64, decode_acks, need_frame, sync


# ------------------------------------------------------------------ ack cursor robustness

def test_cursor_beyond_newest_ack_is_treated_as_zero(client):
    """The store was reset (SQLite file wiped, Firestore database recreated) but the gateway
    still holds its old cursor: it must get the pending ACKs again, not silence."""
    f, need = need_frame()
    first = sync(client, [f]).json()
    assert len(first["acks"]) == 1 and first["ack_cursor"] == 1

    r = sync(client, [], cursor=500).json()           # 500 > newest ack seq (1)
    assert r["acks"] == first["acks"]                 # same ACK again; the mesh dedupes on ack_id
    assert r["ack_cursor"] == 1                       # and the gateway's cursor is repaired

    assert len(sync(client, [], cursor=2).json()["acks"]) == 1       # 2 > 1: also a reset signal
    # a valid cursor is untouched
    assert sync(client, [], cursor=1).json() == {"accepted": 0, "duplicates": 0, "rejected": 0, "acks": [], "ack_cursor": 1}


def test_cursor_on_an_empty_store_is_reset_to_zero(client):
    r = sync(client, [], cursor=17).json()
    assert r["acks"] == [] and r["ack_cursor"] == 0
    # ...and the ACK that appears afterwards is delivered from cursor 0
    f, _ = need_frame()
    r2 = sync(client, [f], cursor=r["ack_cursor"]).json()
    assert len(r2["acks"]) == 1 and r2["ack_cursor"] == 1


def test_ack_ids_dedupe_across_a_reset_resend(client):
    f, _ = need_frame()
    a = decode_acks(sync(client, [f]).json())[0]
    b = decode_acks(sync(client, [], cursor=99).json())[0]
    assert a.ack_id == b.ack_id and a.ref_msg_id == b.ref_msg_id


# ------------------------------------------------------------------ store contract

def test_record_then_ingest_lifecycle(client):
    store = client.app.state.store
    pipeline = client.app.state.pipeline
    f, need = need_frame()
    it = IncomingNeed(f, need, 7, b64(f))
    pkt = pipeline.to_packet(it, T0 + 60)
    assert store.record_packets([pkt], GATEWAY, T0 + 60) == ["new"]
    assert store.record_packets([pkt], GATEWAY, T0 + 60) == ["pending"]
    res = pipeline.process_batch(GATEWAY, [it])
    assert (res.accepted, res.duplicates) == (1, 0) and res.created == [101]   # "resumed" counts as accepted
    assert store.record_packets([pkt], GATEWAY, T0 + 60) == ["done"]
    assert pipeline.process_batch(GATEWAY, [it]).duplicates == 1
    assert store.ack_count() == 1
    assert [p["msg_id"] for p in store.ticket_packets(101)] == [need.msg_id.hex()]


def test_ticket_packets_keep_link_order_and_origins_are_distinct(client):
    frames = []
    for i in range(1, 5):
        frames.append(need_frame(origin=bytes([1 + i % 2]) * 8, summary=f"s{i}")[0])   # origins alternate
    sync(client, frames)
    store = client.app.state.store
    got = [p["summary"] for p in store.ticket_packets(101)]
    assert got == ["s1", "s2", "s3", "s4"]
    assert [p["summary"] for p in store.origins_for_ticket(101)] == ["s1", "s2"]


def test_enrichment_compare_and_set(client):
    store = client.app.state.store
    sync(client, [need_frame()[0]])
    assert store.update_ticket_if_unchanged(101, 2, {"priority_reason": "stale"}) is False   # report_count moved on
    assert store.update_ticket_if_unchanged(101, 1, {"priority_reason": "fresh"}) is True
    assert client.get("/v1/tickets/101").json()["priority_reason"] == "fresh"
    client.post("/v1/tickets/101/approve")
    assert store.update_ticket_if_unchanged(101, 1, {"priority_reason": "after approve"}) is False


def test_approved_ticket_no_longer_attracts_merges(client):
    sync(client, [need_frame()[0]])
    client.post("/v1/tickets/101/approve")
    sync(client, [need_frame()[0]])                     # same place/category/item, but 101 is dispatched
    assert sorted(t["ticket_no"] for t in client.get("/v1/tickets").json()["tickets"]) == [101, 102]


def test_state_survives_a_new_app_on_the_same_backing_store(make_client):
    c1 = make_client()
    sync(c1, [need_frame(lat=1.0)[0]])
    store = c1.app.state.store
    from app.store import FirestoreStore, SqliteStore

    if isinstance(store, FirestoreStore):
        store2 = FirestoreStore(project=EMULATOR_PROJECT, database=None)
    else:
        store.close()
        store2 = SqliteStore(store.path)
    c2 = TestClient(create_app(store=store2, clock=c1.app.state.pipeline.clock, env="dev", pubsub_topic="", static_dir=""))
    sync(c2, [need_frame(lat=2.0)[0]])
    assert sorted(t["ticket_no"] for t in c2.get("/v1/tickets").json()["tickets"]) == [101, 102]
    assert len(sync(c2, [], cursor=0).json()["acks"]) == 2
    store2.close()


# ------------------------------------------------------------------ Firestore concurrency
# Two independent FirestoreStore objects stand in for two Cloud Run instances handling
# redelivered Pub/Sub messages at the same moment (the in-process lock does not help them).

@pytest.fixture
def two_instances(clock):
    host = _emulator_host()
    if host is None:
        pytest.skip("Firestore emulator not reachable (set FIRESTORE_EMULATOR_HOST)")
    from app.store import FirestoreStore

    _clear_emulator(host)
    apps = [
        TestClient(create_app(store=FirestoreStore(project=EMULATOR_PROJECT, database=None), clock=clock,
                              env="dev", pubsub_topic="", static_dir="", device_key="dev-relay-key"))
        for _ in range(2)
    ]
    yield apps
    for a in apps:
        a.app.state.store.close()


def _items(frames):
    out = []
    for f in frames:
        fr = decode_frame(f)
        out.append(IncomingNeed(f, decode_need(fr.payload), fr.ttl, b64(f)))
    return out


@pytest.mark.firestore
def test_concurrent_redelivery_of_one_message_creates_one_ticket_and_one_ack(two_instances):
    a, b = two_instances
    f, _ = need_frame()
    items = _items([f])
    barrier = threading.Barrier(8)

    def go(i):
        barrier.wait()
        pipeline = (a, b)[i % 2].app.state.pipeline
        return pipeline.process_batch(GATEWAY, items)

    with cf.ThreadPoolExecutor(8) as pool:
        results = list(pool.map(go, range(8)))
    assert sum(r.accepted for r in results) == 1 and sum(r.duplicates for r in results) == 7
    assert [t["ticket_no"] for t in a.get("/v1/tickets").json()["tickets"]] == [101]
    assert a.app.state.store.ack_count() == 1
    assert len(decode_acks(sync(a, [], cursor=0).json())) == 1


@pytest.mark.firestore
def test_concurrent_distinct_packets_get_unique_ticket_numbers_and_ack_sequence(two_instances):
    a, b = two_instances
    frames = [need_frame(origin=(i + 1).to_bytes(8, "big"), lat=10 + i * 0.01)[0] for i in range(6)]   # far apart: 6 tickets
    barrier = threading.Barrier(len(frames))

    def go(i):
        barrier.wait()
        return (a, b)[i % 2].app.state.pipeline.process_batch(GATEWAY, _items([frames[i]]))

    with cf.ThreadPoolExecutor(len(frames)) as pool:
        results = list(pool.map(go, range(len(frames))))
    assert sum(r.accepted for r in results) == 6
    assert sorted(t["ticket_no"] for t in a.get("/v1/tickets").json()["tickets"]) == list(range(101, 107))
    body = sync(a, [], cursor=0).json()
    assert len(body["acks"]) == 6 and body["ack_cursor"] == 6


@pytest.mark.firestore
def test_concurrent_approve_queues_each_dispatched_ack_once(two_instances):
    a, b = two_instances
    sync(a, [need_frame(origin=bytes([i]) * 8)[0] for i in range(1, 4)])
    barrier = threading.Barrier(6)

    def go(i):
        barrier.wait()
        return (a, b)[i % 2].post("/v1/tickets/101/approve").status_code

    with cf.ThreadPoolExecutor(6) as pool:
        assert set(pool.map(go, range(6))) == {200}
    acks = decode_acks(sync(a, [], cursor=0).json())
    assert [x.status for x in acks].count(STATUS_DISPATCHED) == 3
    assert a.app.state.store.ack_count() == 6


@pytest.mark.firestore
def test_concurrent_near_duplicates_from_two_instances_cluster_into_one_ticket(two_instances):
    a, b = two_instances
    frames = [need_frame(origin=(i + 1).to_bytes(8, "big"))[0] for i in range(4)]   # same spot, category, item
    barrier = threading.Barrier(len(frames))

    def go(i):
        barrier.wait()
        return (a, b)[i % 2].app.state.pipeline.process_batch(GATEWAY, _items([frames[i]]))

    with cf.ThreadPoolExecutor(len(frames)) as pool:
        list(pool.map(go, range(len(frames))))
    tickets = a.get("/v1/tickets").json()["tickets"]
    assert len(tickets) == 1 and tickets[0]["report_count"] == 4 and len(tickets[0]["msg_ids"]) == 4


def test_tickets_written_before_loc_acc_was_denormalised_still_render(client):
    """Firestore ticket docs created by older code have links without ``loc_acc``; the list
    must derive it from the packet docs. (SQLite always derives it from the packets table.)"""
    fix, _ = need_frame(loc_acc=2, lat=11.0, item="a")
    nofix, _ = need_frame(loc_acc=0, lat=0.0, lon=0.0, item="b")
    sync(client, [fix, nofix])
    store = client.app.state.store
    if type(store).__name__ == "FirestoreStore":
        col = store._col("tickets")
        for s in col.stream():
            col.document(s.id).update({"links": [{k: v for k, v in lk.items() if k != "loc_acc"} for lk in s.get("links")]})
        store._loc_acc_cache.clear()
    ts = {t["ticket_no"]: t for t in client.get("/v1/tickets").json()["tickets"]}
    assert (ts[101]["has_location"], ts[101]["loc_acc"], ts[101]["lat"]) == (True, 2, 11.0)
    assert (ts[102]["has_location"], ts[102]["loc_acc"], ts[102]["lat"]) == (False, 0, None)
