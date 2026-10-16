"""Pub/Sub mode: /v1/sync publishes, POST /internal/triage (push target) triages.

Runs against SQLite and, when the emulator is up, Firestore (the production combination).
Fake publisher and fake token verifier: nothing here touches Google Cloud.
"""

import base64
import json

import pytest

from app import pubsub
from app.agent import GeminiAgent

from conftest import GATEWAY, KEY, b64, decode_acks, need_frame, sync
from test_agent import GOOD, FakeClient

TOPIC = "projects/test-proj/topics/relay-packets"
AUDIENCE = "relay-triage"
SA = "relay-push@test-proj.iam.gserviceaccount.com"
GOOD_TOKEN = "good-token"


class FakePublisher:
    def __init__(self):
        self.sent: list[tuple[str, bytes]] = []
        self.fail = False

    def publish(self, topic, data):
        if self.fail:
            raise RuntimeError("pubsub down")
        self.sent.append((topic, data))


class FakeVerifier:
    """token -> claims; unknown tokens are invalid (ValueError, like google-auth)."""

    def __init__(self):
        self.tokens = {
            GOOD_TOKEN: {"email": SA, "email_verified": True, "aud": AUDIENCE},
            "other-sa": {"email": "attacker@evil.iam.gserviceaccount.com", "email_verified": True},
            "unverified": {"email": SA, "email_verified": False},
        }
        self.seen: list[str] = []

    def __call__(self, token):
        self.seen.append(token)
        if token not in self.tokens:
            raise ValueError("Could not verify token")
        return self.tokens[token]


@pytest.fixture
def ps(make_client):
    pub, ver = FakePublisher(), FakeVerifier()
    c = make_client(pubsub_topic=TOPIC, publisher=pub, token_verifier=ver, pubsub_audience=AUDIENCE, invoker_sa=SA)
    return c, pub, ver


def envelope(data: bytes, attempt: int = 1) -> dict:
    return {
        "message": {"data": base64.b64encode(data).decode(), "messageId": "123", "publishTime": "2026-01-01T00:00:00Z",
                    "attributes": {}},
        "subscription": "projects/test-proj/subscriptions/relay-packets-triage", "deliveryAttempt": attempt,
    }


def push(c, data: bytes, token: str | None = GOOD_TOKEN, extra_headers=None):
    headers = dict(extra_headers or {})
    if token is not None:
        headers["Authorization"] = f"Bearer {token}"
    return c.post("/internal/triage", json=envelope(data), headers=headers)


# ------------------------------------------------------------------ publish side

def test_sync_publishes_exact_contract_payload(ps):
    c, pub, _ = ps
    f1, _ = need_frame(lat=10.0)
    f2, _ = need_frame(lat=11.0)
    r = sync(c, [f1, f2], gateway=GATEWAY.upper())
    assert r.status_code == 200
    assert r.json() == {"accepted": 2, "duplicates": 0, "rejected": 0, "acks": [], "ack_cursor": 0}
    assert len(pub.sent) == 1                       # one message per accepted batch
    topic, data = pub.sent[0]
    assert topic == TOPIC
    # byte-exact contract: UTF-8 JSON, gateway id as sent, frames are the uploaded base64 strings
    assert json.loads(data.decode("utf-8")) == {"gateway_node": GATEWAY.upper(), "frames": [b64(f1), b64(f2)]}
    assert list(json.loads(data)) == ["gateway_node", "frames"]
    # triage has not run: nothing is a ticket yet
    assert c.get("/v1/tickets").json() == {"tickets": []}


def test_sync_publishes_only_frames_not_already_stored_and_skips_empty(ps):
    c, pub, _ = ps
    f, _ = need_frame()
    g, _ = need_frame(lat=12.0)
    sync(c, [f])
    push(c, pub.sent[-1][1])                        # f is now triaged ("done")
    pub.sent.clear()
    r = sync(c, [f, g, g]).json()
    assert (r["accepted"], r["duplicates"]) == (1, 2)
    assert [json.loads(d)["frames"] for _, d in pub.sent] == [[b64(g)]]
    pub.sent.clear()
    push_all = sync(c, [f]).json()                  # nothing new and nothing pending: no publish
    assert push_all["duplicates"] == 1 and pub.sent == []
    assert sync(c, []).json()["accepted"] == 0 and pub.sent == []
    # rejected frames are counted and never published
    r = sync(c, [], gateway=GATEWAY).json()
    bad = c.post("/v1/sync", headers=KEY, json={"gateway_node": GATEWAY, "frames": ["!!nope!!"]}).json()
    assert bad["rejected"] == 1 and pub.sent == []


def test_publish_failure_is_503_and_retry_republishes_stranded_packets(ps):
    c, pub, _ = ps
    f, _ = need_frame()
    pub.fail = True
    assert sync(c, [f]).status_code == 503          # gateway keeps the frame and retries
    pub.fail = False
    r = sync(c, [f]).json()
    assert (r["accepted"], r["duplicates"]) == (0, 1)   # stored by the failed attempt
    assert len(pub.sent) == 1 and json.loads(pub.sent[0][1])["frames"] == [b64(f)]
    assert push(c, pub.sent[0][1]).status_code == 200
    assert len(c.get("/v1/tickets").json()["tickets"]) == 1


def test_sync_still_requires_device_key_in_pubsub_mode(ps):
    c, pub, _ = ps
    f, _ = need_frame()
    assert sync(c, [f], headers={}).status_code == 401
    assert pub.sent == []


def test_build_messages_splits_only_when_oversized():
    frames = ["A" * 1000] * 10
    assert len(pubsub.build_messages(GATEWAY, frames)) == 1
    big = ["A" * (1024 * 1024)] * 12
    msgs = pubsub.build_messages(GATEWAY, big)
    assert len(msgs) > 1 and all(len(m) < pubsub.MAX_MESSAGE_BYTES + 200 for m in msgs)
    assert sum(len(json.loads(m)["frames"]) for m in msgs) == 12
    assert pubsub.build_messages(GATEWAY, []) == []


# ------------------------------------------------------------------ push side

def test_push_triages_published_batch_end_to_end(ps):
    c, pub, ver = ps
    frames = [need_frame(origin=bytes([i]) * 8, lang=i)[0] for i in range(1, 4)]   # 3 reporters, one cluster
    sync(c, frames)
    r = push(c, pub.sent[0][1])
    assert r.status_code == 200
    assert r.json() == {"accepted": 3, "duplicates": 0, "rejected": 0}
    assert ver.seen == [GOOD_TOKEN]
    tickets = c.get("/v1/tickets").json()["tickets"]
    assert len(tickets) == 1 and tickets[0]["ticket_no"] == 101 and tickets[0]["report_count"] == 3
    assert tickets[0]["gateways"] == [GATEWAY]
    # the next sync carries the RECEIVED acks home
    body = sync(c, []).json()
    acks = decode_acks(body)
    assert len(acks) == 3 and all(a.status == 0 and a.ticket_no == 101 for a in acks)
    assert {a.target_node for a in acks} == {bytes([i]) * 8 for i in range(1, 4)}


def test_push_replay_creates_no_duplicate_ticket_or_ack(ps):
    c, pub, _ = ps
    sync(c, [need_frame(lat=10.0)[0], need_frame(lat=20.0)[0]])
    data = pub.sent[0][1]
    assert push(c, data).json()["accepted"] == 2
    again = push(c, data)                           # at-least-once redelivery
    assert again.status_code == 200 and again.json() == {"accepted": 0, "duplicates": 2, "rejected": 0}
    assert push(c, data).status_code == 200
    assert sorted(t["ticket_no"] for t in c.get("/v1/tickets").json()["tickets"]) == [101, 102]
    assert c.app.state.store.ack_count() == 2
    assert len(decode_acks(sync(c, []).json())) == 2


def test_push_for_unrecorded_message_still_works(ps):
    """A message whose packets were never recorded by /v1/sync (e.g. replayed from the DLQ
    after a store wipe) is triaged from the message alone."""
    c, _, _ = ps
    f, _ = need_frame()
    data = pubsub.build_messages(GATEWAY, [b64(f)])[0]
    assert push(c, data).json()["accepted"] == 1
    assert len(c.get("/v1/tickets").json()["tickets"]) == 1


def test_push_requires_bearer_token(ps):
    c, pub, ver = ps
    sync(c, [need_frame()[0]])
    data = pub.sent[0][1]
    assert push(c, data, token=None).status_code == 401
    assert c.post("/internal/triage", json=envelope(data), headers={"Authorization": "Basic abc"}).status_code == 401
    assert c.post("/internal/triage", json=envelope(data), headers={"Authorization": "Bearer "}).status_code == 401
    assert push(c, data, token="forged-or-expired").status_code == 401
    assert ver.seen == ["forged-or-expired"]
    assert c.get("/v1/tickets").json() == {"tickets": []}


def test_push_wrong_or_unverified_email_is_403(ps):
    c, pub, _ = ps
    sync(c, [need_frame()[0]])
    data = pub.sent[0][1]
    assert push(c, data, token="other-sa").status_code == 403
    assert push(c, data, token="unverified").status_code == 403
    assert c.get("/v1/tickets").json() == {"tickets": []}


def test_push_does_not_accept_device_key_as_substitute(ps):
    c, pub, _ = ps
    sync(c, [need_frame()[0]])
    data = pub.sent[0][1]
    assert push(c, data, token=None, extra_headers=KEY).status_code == 401
    assert push(c, data, token="other-sa", extra_headers=KEY).status_code == 403   # key does not rescue a bad identity
    assert c.get("/v1/tickets").json() == {"tickets": []}


def test_push_auth_is_checked_before_the_body_is_parsed(ps):
    c, _, _ = ps
    assert c.post("/internal/triage", content=b"{{not json", headers={"content-type": "application/json"}).status_code == 401
    assert c.post("/internal/triage", content=b"{{not json", headers={"Authorization": "Bearer other-sa"}).status_code == 403


def test_push_bad_payloads_are_4xx_but_bad_frames_are_dropped(ps):
    c, pub, _ = ps
    auth = {"Authorization": f"Bearer {GOOD_TOKEN}"}
    assert c.post("/internal/triage", content=b"{{not json", headers=auth).status_code == 400
    assert c.post("/internal/triage", json={"message": {}}, headers=auth).status_code == 400
    assert c.post("/internal/triage", json={"message": {"data": "%%%"}}, headers=auth).status_code == 400
    for payload in (b"[]", b'{"gateway_node": "zz", "frames": []}', b'{"gateway_node": "%s", "frames": "x"}' % GATEWAY.encode()):
        assert push(c, payload).status_code == 400
    good, _ = need_frame()
    data = json.dumps({"gateway_node": GATEWAY, "frames": ["!!bad!!", b64(good), ""]}).encode()
    r = push(c, data)
    assert r.status_code == 200 and r.json() == {"accepted": 1, "duplicates": 0, "rejected": 2}


def test_push_unconfigured_fails_closed(make_client):
    c = make_client(pubsub_topic=TOPIC, publisher=FakePublisher(), pubsub_audience="", invoker_sa="")
    r = c.post("/internal/triage", json=envelope(b"{}"), headers={"Authorization": "Bearer anything"})
    assert r.status_code == 503
    assert c.post("/internal/triage", json=envelope(b"{}")).status_code == 401


def test_push_deadline_zero_budget_is_503_then_retry_ok(make_client):
    pub, ver = FakePublisher(), FakeVerifier()
    c = make_client(pubsub_topic=TOPIC, publisher=pub, token_verifier=ver, pubsub_audience=AUDIENCE,
                    invoker_sa=SA, triage_budget_s=0.0)
    sync(c, [need_frame(lat=10.0)[0]])
    r = push(c, pub.sent[0][1])
    assert r.status_code == 503
    assert c.get("/v1/tickets").json() == {"tickets": []}     # nothing half-done
    # redeliver to a fresh app (default 50 s budget) on the same store
    from app.main import create_app
    from fastapi.testclient import TestClient
    c2 = TestClient(create_app(store=c.app.state.store, clock=c.app.state.pipeline.clock, pubsub_topic=TOPIC,
                               publisher=pub, token_verifier=ver, pubsub_audience=AUDIENCE, invoker_sa=SA,
                               static_dir="", env="dev"))
    assert push(c2, pub.sent[0][1]).status_code == 200
    assert len(c2.get("/v1/tickets").json()["tickets"]) == 1


def test_push_runs_gemini_enrichment_inside_the_request(make_client):
    fake = FakeClient({**GOOD, "dispatch_en": "Insulin 20.29612, 85.82451 urgent", "tier_adjustment": 1})
    pub, ver = FakePublisher(), FakeVerifier()
    c = make_client(agent=GeminiAgent(fake), pubsub_topic=TOPIC, publisher=pub, token_verifier=ver,
                    pubsub_audience=AUDIENCE, invoker_sa=SA)
    f, _ = need_frame(category=0, urgency=2, vuln_flags=1, lat=20.29612, lon=85.82451, lang=2)
    sync(c, [f])
    assert fake.prompts == []                        # /v1/sync never waits on the model
    assert push(c, pub.sent[0][1]).status_code == 200
    assert len(fake.prompts) == 1
    t = c.get("/v1/tickets/101").json()
    assert t["priority_tier"] == "P1" and t["dispatch_draft"]["en"] == "[P1] Insulin 20.29612, 85.82451 urgent"
    push(c, pub.sent[0][1])                          # redelivery: no second model call
    assert len(fake.prompts) == 1


def test_in_process_mode_unchanged_when_topic_unset(make_client):
    pub = FakePublisher()
    c = make_client(pubsub_topic="", publisher=pub)
    r = sync(c, [need_frame()[0]]).json()
    assert r["accepted"] == 1 and len(r["acks"]) == 1 and pub.sent == []
    assert len(c.get("/v1/tickets").json()["tickets"]) == 1
