"""RELAY_OPERATOR_KEY is opt-in: unset = control-room routes open; set = X-Operator-Key required."""

import pytest
from fastapi.testclient import TestClient

from app.main import create_app

from conftest import KEY, need_frame, sync

OP = {"X-Operator-Key": "s3cret"}

CONTROL_ROOM = [
    ("GET", "/v1/tickets"),
    ("GET", "/v1/tickets/101"),
    ("POST", "/v1/tickets/101/approve"),
    ("POST", "/v1/tickets/101/on-the-way"),
    ("POST", "/v1/tickets/101/resolve"),
    ("GET", "/v1/teams"),
    ("POST", "/v1/dispatch/propose"),
    ("POST", "/v1/sitreps/run"),
    ("GET", "/v1/sitreps/latest"),
    ("GET", "/v1/stats"),
]


def test_unset_leaves_every_route_open(client, monkeypatch):
    monkeypatch.delenv("RELAY_OPERATOR_KEY", raising=False)
    sync(client, [need_frame()[0]])
    for method, path in CONTROL_ROOM:
        assert client.request(method, path).status_code != 401, (method, path)


@pytest.mark.parametrize("method,path", CONTROL_ROOM)
def test_set_requires_the_header(make_client, method, path):
    c = make_client(operator_key="s3cret")
    assert c.request(method, path).status_code == 401
    assert c.request(method, path, headers={"X-Operator-Key": "wrong"}).status_code == 401
    assert c.request(method, path, headers=KEY).status_code == 401           # the device key is not the operator key
    assert c.request(method, path, headers=OP).status_code != 401


def test_set_with_header_works_end_to_end(make_client):
    c = make_client(operator_key="s3cret")
    sync(c, [need_frame()[0]])
    assert len(c.get("/v1/tickets", headers=OP).json()["tickets"]) == 1
    assert c.post("/v1/tickets/101/approve", headers=OP).json()["status"] == "DISPATCHED"
    assert c.post("/v1/tickets/101/on-the-way", headers=OP).json()["status"] == "ON_THE_WAY"
    assert c.post("/v1/tickets/101/resolve", headers=OP).json()["status"] == "RESOLVED"
    assert c.get("/v1/stats", headers=OP).status_code == 200


def test_sync_health_and_cors_are_unaffected(make_client):
    c = make_client(operator_key="s3cret")
    assert sync(c, [need_frame()[0]]).status_code == 200                    # device key only
    assert sync(c, [], headers={}).status_code == 401                       # still the device key's job
    assert c.get("/healthz").status_code == 200 and c.get("/v1/health").status_code == 200
    r = c.options("/v1/tickets", headers={
        "Origin": "http://localhost:5173", "Access-Control-Request-Method": "GET",
        "Access-Control-Request-Headers": "x-operator-key"})
    assert r.status_code == 200 and "x-operator-key" in r.headers["access-control-allow-headers"].lower()


def test_operator_key_comes_from_the_environment(monkeypatch, tmp_path):
    monkeypatch.setenv("RELAY_OPERATOR_KEY", "s3cret")
    c = TestClient(create_app(db_path=str(tmp_path / "e.db"), pubsub_topic="", static_dir="", env="dev"))
    assert c.get("/v1/stats").status_code == 401
    assert c.get("/v1/stats", headers=OP).status_code == 200
    c.app.state.store.close()
