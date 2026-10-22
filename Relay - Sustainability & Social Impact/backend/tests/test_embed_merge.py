"""Embedding-assisted duplicate merging: replaces only the item-text test, never the hard gates."""

import time

import pytest

from app import embed
from app.embed import cosine, decode_vec, embedder_from_env, encode_vec, threshold_from_env

from conftest import need_frame, sync

CAT = "MEDICAL"
VECS = {
    f"{CAT}: insulin": [1.0, 0.0, 0.0],
    f"{CAT}: इंसुलिन": [0.96, 0.28, 0.0],   # cos 0.96
    f"{CAT}: ଇନସୁଲିନ": [0.9, 0.0, 0.43],     # cos ~0.9
    f"{CAT}: bandage": [0.1, 1.0, 0.0],     # dissimilar
    "FOOD: insulin": [1.0, 0.0, 0.0],       # identical vector, other category
}


class FakeEmbedder:
    def __init__(self, vecs=None):
        self.vecs = VECS if vecs is None else vecs

    def embed(self, texts):
        # the wire format truncates long non-Latin items, so match on prefix
        return [next(v for k, v in self.vecs.items() if k.startswith(t)) for t in texts]


class Boom:
    def embed(self, texts):
        raise RuntimeError("model down")


class Slow:
    def embed(self, texts):
        time.sleep(1.0)
        return [[1.0]]


def tickets(c):
    return c.get("/v1/tickets").json()["tickets"]


def send(c, **kw):
    f, _ = need_frame(**kw)
    r = sync(c, [f])
    assert r.status_code == 200
    return r.json()


# ------------------------------------------------------------------ pure

def test_cosine_and_vec_round_trip():
    assert cosine([1, 0], [1, 0]) == pytest.approx(1.0)
    assert cosine([1, 0], [0, 1]) == pytest.approx(0.0)
    assert cosine([1, 0], [-1, 0]) == pytest.approx(-1.0)
    assert cosine([], []) == 0.0 and cosine([1], [1, 2]) == 0.0 and cosine([0, 0], [1, 0]) == 0.0
    v = [0.3, -0.5, 0.8, 0.1]
    back = decode_vec(encode_vec(v))
    assert cosine(v, back) > 0.9999
    assert sum(x * x for x in back) == pytest.approx(1.0, abs=1e-2)
    assert decode_vec(None) is None and decode_vec("") is None
    assert encode_vec([0.0, 0.0]) == ""


def test_threshold_env():
    assert threshold_from_env({}) == embed.SIMILARITY_THRESHOLD
    assert threshold_from_env({"RELAY_EMBED_THRESHOLD": "0.9"}) == 0.9
    assert threshold_from_env({"RELAY_EMBED_THRESHOLD": "junk"}) == embed.SIMILARITY_THRESHOLD
    assert threshold_from_env({"RELAY_EMBED_THRESHOLD": "7"}) == embed.SIMILARITY_THRESHOLD


def test_embedder_from_env_switches():
    assert embedder_from_env({}) is None
    assert embedder_from_env({"GEMINI_API_KEY": "k", "RELAY_EMBED_MERGE": "off"}) is None
    assert isinstance(embedder_from_env({"GEMINI_API_KEY": "k"}), embed.GenaiEmbedder)


def test_embed_item_failures_give_none():
    assert embed.embed_item(None, CAT, "insulin") is None
    assert embed.embed_item(FakeEmbedder(), CAT, "  ") is None
    assert embed.embed_item(Boom(), CAT, "insulin") is None
    assert embed.embed_item(Slow(), CAT, "insulin", timeout_s=0.05) is None


# ------------------------------------------------------------------ end to end

def test_multilingual_same_item_within_200m_merges(make_client):
    c = make_client(embedder=FakeEmbedder())
    send(c, item="insulin")
    send(c, item="इंसुलिन", lat=20.29625)
    send(c, item="ଇନସୁଲିନ", lat=20.29630)
    ts = tickets(c)
    assert len(ts) == 1 and ts[0]["report_count"] == 3
    assert "item_vec" not in ts[0]


def test_merged_report_still_gets_ack(make_client):
    c = make_client(embedder=FakeEmbedder())
    send(c, item="insulin")
    body = send(c, item="इंसुलिन", lat=20.29625)
    assert len(tickets(c)) == 1
    assert body["acks"]


def test_same_item_2km_apart_stays_separate(make_client):
    c = make_client(embedder=FakeEmbedder())
    send(c, item="insulin")
    send(c, item="इंसुलिन", lat=20.29612 + 0.018)
    assert len(tickets(c)) == 2


def test_different_category_never_merges(make_client):
    c = make_client(embedder=FakeEmbedder())
    send(c, category=0, item="insulin")
    send(c, category=3, item="insulin")
    assert len(tickets(c)) == 2


def test_dissimilar_items_do_not_merge(make_client):
    c = make_client(embedder=FakeEmbedder())
    send(c, item="insulin")
    send(c, item="bandage")
    assert len(tickets(c)) == 2


def test_no_fix_never_merges(make_client):
    c = make_client(embedder=FakeEmbedder())
    send(c, item="insulin")
    send(c, item="इंसुलिन", loc_acc=0)
    assert len(tickets(c)) == 2


@pytest.mark.parametrize("embedder", [Boom(), Slow()])
def test_embedder_failure_falls_back_to_rules(make_client, monkeypatch, embedder):
    monkeypatch.setattr(embed, "EMBED_TIMEOUT_S", 0.1)
    c = make_client(embedder=embedder)
    send(c, item="insulin")
    body = send(c, item="insulin", lat=20.29620)       # exact text still merges
    assert body["acks"]
    body = send(c, item="इंसुलिन", lat=20.29625)       # needs the model: falls back, own ticket
    assert body["accepted"] == 1 and body["acks"]
    assert len(tickets(c)) == 2


def test_ticket_without_vector_uses_rule_then_gets_one(make_client):
    c = make_client()                                    # no embedder in the environment
    c.app.state.pipeline.embedder = None
    send(c, item="insulin")                              # tickets created without vectors
    send(c, item="इंसुलिन", lat=20.29625)
    assert len(tickets(c)) == 2
    c.app.state.pipeline.embedder = FakeEmbedder()
    send(c, item="ଇନସୁଲିନ", lat=20.29626)                # old tickets have no vector: rule only
    assert len(tickets(c)) == 3
    send(c, item="insulin", lat=20.29613)                # equal text merges and back-fills the vector
    stored = [t for t, _ in c.app.state.store.list_tickets_with_links() if t["item"] == "insulin"][0]
    assert stored["item_vec"]
    send(c, item="इंसुलिन", lat=20.29612)                # nearest is ticket 1: now merges by similarity
    by_item = {t["item"]: t["report_count"] for t in tickets(c)}
    assert by_item["insulin"] == 3 and len(by_item) == 3


def test_empty_item_still_merges_by_rule(make_client):
    c = make_client(embedder=FakeEmbedder())
    send(c, item="insulin")
    send(c, item="")
    assert len(tickets(c)) == 1
