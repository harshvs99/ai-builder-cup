"""Shared fixtures and builders. No network anywhere in the suite."""

from __future__ import annotations

import base64
import itertools
import json
import os
import socket
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app import codec
from app.main import create_app

VECTORS = json.loads((Path(__file__).resolve().parents[2] / "protocol" / "vectors.json").read_text())

KEY = {"X-Relay-Key": "dev-relay-key"}
GATEWAY = "a1b2c3d4e5f60718"
T0 = 1_790_000_000


class FakeClock:
    def __init__(self, t: float = T0 + 60):
        self.t = t

    def __call__(self) -> float:
        return self.t


@pytest.fixture
def clock() -> FakeClock:
    return FakeClock()


EMULATOR_PROJECT = "relay-test"


def _emulator_host() -> str | None:
    """The Firestore emulator address, only if FIRESTORE_EMULATOR_HOST is set AND it accepts
    connections. The Firestore tests never run (and never construct a client) otherwise, so
    they can not reach a real project by accident."""
    host = os.environ.get("FIRESTORE_EMULATOR_HOST")
    if not host or ":" not in host:
        return None
    name, _, port = host.rpartition(":")
    try:
        with socket.create_connection((name, int(port)), timeout=0.5):
            return host
    except (OSError, ValueError):
        return None


def _clear_emulator(host: str) -> None:
    import requests

    r = requests.delete(f"http://{host}/emulator/v1/projects/{EMULATOR_PROJECT}/databases/(default)/documents", timeout=10)
    r.raise_for_status()


@pytest.fixture(params=[
    "sqlite",
    pytest.param("firestore", marks=pytest.mark.firestore),
])
def make_client(request, tmp_path, clock):
    """Factory: make_client(agent=None, **create_app kwargs) -> TestClient with a fresh store.

    Parametrised: every API-level test runs against SQLite and against the Firestore emulator
    (skipped when the emulator is not reachable; see scripts/test-firestore.sh)."""
    if request.param == "firestore":
        host = _emulator_host()
        if host is None:
            pytest.skip("Firestore emulator not reachable (set FIRESTORE_EMULATOR_HOST)")
    counter = itertools.count()
    clients: list[TestClient] = []

    def _make(**kw):
        path = str(tmp_path / f"relay{next(counter)}.db")
        if request.param == "firestore":
            from app.store import FirestoreStore

            _clear_emulator(host)
            kw.setdefault("store", FirestoreStore(project=EMULATOR_PROJECT, database=None))
        else:
            kw.setdefault("db_path", path)
        kw.setdefault("clock", clock)
        kw.setdefault("device_key", "dev-relay-key")
        kw.setdefault("env", "dev")
        kw.setdefault("pubsub_topic", "")  # in-process triage unless a test opts in
        kw.setdefault("static_dir", "")
        c = TestClient(create_app(**kw))
        clients.append(c)
        return c

    yield _make
    for c in clients:
        c.app.state.store.close()


@pytest.fixture
def client(make_client) -> TestClient:
    return make_client()


_msg_counter = itertools.count(1)


def need_frame(
    *,
    origin: bytes | None = None,
    msg_id: bytes | None = None,
    category: int = 0,
    urgency: int = 2,
    people: int = 1,
    vuln_flags: int = 0,
    lat: float = 20.29612,
    lon: float = 85.82451,
    loc_acc: int = 1,
    lang: int = 0,
    item: str = "insulin",
    landmark: str = "school",
    summary: str = "needs insulin",
    created_at: int = T0,
    ttl: int = 7,
) -> tuple[bytes, codec.Need]:
    n = next(_msg_counter)
    origin = origin if origin is not None else n.to_bytes(8, "big")
    msg_id = msg_id if msg_id is not None else (0xAB00_0000_0000_0000 | n).to_bytes(8, "big")
    need = codec.Need.make(
        msg_id=msg_id, origin_node=origin, created_at=created_at, category=category, urgency=urgency,
        people=people, vuln_flags=vuln_flags, lat=lat, lon=lon, loc_acc=loc_acc, lang=lang,
        item=item, landmark=landmark, summary=summary,
    )
    frame = codec.encode_frame(codec.TYPE_NEED, ttl, (created_at + 1) * 1000, origin, codec.encode_need(need))
    return frame, need


def b64(frame: bytes) -> str:
    return base64.b64encode(frame).decode()


def sync(client: TestClient, frames: list[bytes], cursor: int = 0, gateway: str = GATEWAY, headers=KEY):
    return client.post(
        "/v1/sync",
        json={"gateway_node": gateway, "frames": [b64(f) for f in frames], "ack_cursor": cursor},
        headers=headers,
    )


def decode_acks(body: dict) -> list[codec.Ack]:
    return [codec.decode_ack(base64.b64decode(a)) for a in body["acks"]]
