import base64

from fastapi.testclient import TestClient

from app.api import create_app
from app.config import Settings
from app.protocol import AckPacket, AckStatus, decode_ack, encode_ack
from app.store import Store

from .conftest import FakeLLM, b64, make_need


def sync(client, packets=(), cursor=None, headers=None):
    body = {"gateway_node": "d0d0d0d0", "packets": list(packets)}
    if cursor is not None:
        body["ack_cursor"] = cursor
    resp = client.post("/v1/sync", json=body, headers=headers or {})
    assert resp.status_code == 200, resp.text
    return resp.json()


def test_sync_creates_ticket_and_returns_ack(client):
    need = make_need()
    out = sync(client, [{"data": b64(need), "hops": 2}])
    assert out["created"] == 1 and out["rejected"] == []
    [ack] = [decode_ack(base64.b64decode(a)) for a in out["acks"]]
    assert ack.target_node == need.origin_node and ack.status is AckStatus.RECEIVED

    tickets = client.get("/v1/tickets").json()["tickets"]
    assert len(tickets) == 1
    t = tickets[0]
    assert t["report_count"] == 1 and t["max_hops"] == 2 and t["tier"] in (1, 2)


def test_resync_is_idempotent_and_cursor_only_returns_new_acks(client):
    packet = {"data": b64(make_need())}
    first = sync(client, [packet])
    again = sync(client, [packet], cursor=first["ack_cursor"])
    assert again["duplicates"] == 1 and again["acks"] == []
    assert again["ack_cursor"] == first["ack_cursor"]


def test_fresh_gateway_receives_recent_acks(client):
    sync(client, [{"data": b64(make_need())}])
    out = client.post("/v1/sync", json={"gateway_node": "beef"}).json()
    assert len(out["acks"]) == 1


def test_bad_packets_are_rejected_individually(client):
    ack = AckPacket(ref_msg_id=bytes(8), target_node=1, ticket_no=1, status=AckStatus.RECEIVED, created_at=0)
    out = sync(
        client,
        [
            {"data": "not base64!"},
            {"data": base64.b64encode(b"\x01\x01short").decode()},
            {"data": base64.b64encode(encode_ack(ack)).decode()},
            {"data": b64(make_need())},
        ],
    )
    assert [r["index"] for r in out["rejected"]] == [0, 1]
    assert out["ignored"] == 1 and out["created"] == 1


def test_operator_approval_flows_back_to_gateway(client):
    need = make_need()
    first = sync(client, [{"data": b64(need)}])
    ticket_id = client.get("/v1/tickets").json()["tickets"][0]["id"]
    resp = client.post(f"/v1/tickets/{ticket_id}/approve", json={"eta_min": 30})
    assert resp.status_code == 200 and resp.json()["acks_queued"] == 1
    out = sync(client, cursor=first["ack_cursor"])
    [ack] = [decode_ack(base64.b64decode(a)) for a in out["acks"]]
    assert (ack.status, ack.eta_min, ack.target_node) == (AckStatus.DISPATCHED, 30, need.origin_node)
    assert client.get("/v1/tickets", params={"status": "DISPATCHED"}).json()["tickets"][0]["id"] == ticket_id


def test_unknown_ticket_is_404(client):
    assert client.post("/v1/tickets/999/resolve").status_code == 404
    assert client.get("/v1/tickets/999").status_code == 404


def test_changes_feed_tracks_revisions(client):
    assert client.get("/v1/changes").json() == {"rev": 0, "tickets": []}
    sync(client, [{"data": b64(make_need())}])
    feed = client.get("/v1/changes").json()
    assert feed["rev"] > 0 and len(feed["tickets"]) == 1
    assert client.get("/v1/changes", params={"since": feed["rev"]}).json()["tickets"] == []


def test_background_enrichment_runs_after_sync(settings, clock):
    llm = FakeLLM({"PriorityReview": {"tier_adjust": 0, "reason": "Elderly diabetic without insulin."}})
    app = create_app(settings=settings, store=Store(":memory:"), llm=llm, clock=clock)
    with TestClient(app) as c:
        sync(c, [{"data": b64(make_need())}])
        ticket = c.get("/v1/tickets").json()["tickets"][0]
    assert ticket["enriched_by"] == "gemini"
    assert ticket["priority_reason"] == "Elderly diabetic without insulin."


def test_keys_are_enforced_when_configured(fake_llm, clock):
    settings = Settings(db_path=":memory:", device_key="dev-secret", operator_key="op-secret")
    app = create_app(settings=settings, store=Store(":memory:"), llm=fake_llm, clock=clock)
    with TestClient(app) as c:
        assert c.post("/v1/sync", json={"gateway_node": "1"}).status_code == 401
        assert c.post("/v1/sync", json={"gateway_node": "1"}, headers={"X-Relay-Key": "dev-secret"}).status_code == 200
        assert c.get("/v1/tickets").status_code == 401
        assert c.get("/v1/tickets", headers={"X-Operator-Key": "op-secret"}).status_code == 200
        assert c.get("/v1/changes", params={"key": "op-secret"}).status_code == 200
        assert c.get("/healthz").status_code == 200


def test_dev_inject(client):
    resp = client.post(
        "/v1/dev/inject",
        json={"category": "rescue", "urgency": 3, "people": 4, "flags": ["trapped", "child"],
              "lat": 11.2433, "lon": 125.0048, "lang": "war", "item": "boat", "summary": "family on roof"},
    )
    assert resp.status_code == 200, resp.text
    body = resp.json()
    assert body["outcome"] == "created" and body["packet_bytes"] <= 128
    ticket = client.get(f"/v1/tickets/{body['ticket_id']}").json()
    assert ticket["flags"] == ["CHILD", "TRAPPED"] and ticket["tier"] == 1
    assert client.post("/v1/dev/inject", json={"category": "unicorns"}).status_code == 422


def test_dev_inject_disabled_outside_dev_mode(fake_llm, clock):
    app = create_app(settings=Settings(db_path=":memory:"), store=Store(":memory:"), llm=fake_llm, clock=clock)
    with TestClient(app) as c:
        assert c.post("/v1/dev/inject", json={}).status_code == 404
