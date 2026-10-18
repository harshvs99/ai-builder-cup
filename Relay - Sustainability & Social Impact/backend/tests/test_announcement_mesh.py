"""ANNOUNCEMENT mesh delivery (SPEC 5.7): the frame codec, ids and mesh text assigned at approval, /v1/sync."""

import base64
import json

import pytest

from app import announcements, codec, districts

from conftest import KEY, VECTORS, sync

AID = bytes.fromhex("5a5b5c5d5e5f6061")


def vector_fields(v):
    f = v["fields"]
    return codec.Announcement(
        bytes.fromhex(f["announcement_id"]), codec.LANG_CODES.index(f["lang"]), codec.DISTRICT_CODES.index(f["district"]),
        {"active": 0, "withdrawn": 1}[f["status"]], f["approved_at"], f["expires_at"], f["text"],
    )


@pytest.mark.parametrize("v", VECTORS["announcement_vectors"], ids=lambda v: v["name"])
def test_announcement_vectors_match_byte_for_byte(v):
    a = vector_fields(v)
    assert codec.encode_announcement(a).hex() == v["payload_hex"]
    assert codec.decode_announcement(bytes.fromhex(v["payload_hex"])) == a
    assert a.key.hex() == v["fields"]["key"]
    fr = codec.decode_frame(bytes.fromhex(v["frame_hex"]))
    assert fr.type == codec.TYPE_ANNOUNCEMENT == 0x76 and fr.recipient_id is None
    assert len(bytes.fromhex(v["frame_hex"])) <= 182


@pytest.mark.parametrize("name,hexstr", list(VECTORS["must_reject_announcement_payloads"].items()))
def test_must_reject_announcement_payloads(name, hexstr):
    with pytest.raises(codec.CodecError):
        codec.decode_announcement(bytes.fromhex(hexstr))


def test_tables_match_the_vectors_and_the_district_registry():
    assert VECTORS["announcement_lang_codes"] == {c: i for i, c in enumerate(codec.LANG_CODES)}
    assert VECTORS["announcement_district_codes"] == {c: i for i, c in enumerate(codec.DISTRICT_CODES)}
    assert tuple(d.id for d in districts.DISTRICTS) == codec.DISTRICT_CODES[:len(districts.DISTRICTS)]
    assert VECTORS["sizes"]["announcement_max_payload"] == 160 and 22 + 160 <= 182


def test_a_withdrawn_frame_has_a_different_key_so_it_supersedes():
    act = codec.Announcement(AID, 0, 0, 0, 1, 0, "x")
    wd = codec.Announcement(AID, 0, 0, 1, 1, 0, "x")
    hi = codec.Announcement(AID, 1, 0, 0, 1, 0, "x")
    assert len({act.key, wd.key, hi.key}) == 3 and len(act.key) == 8


def test_encoder_refuses_invalid_fields_and_cuts_long_text():
    for bad in (codec.Announcement(bytes(7), 0, 0, 0, 1, 0, "x"), codec.Announcement(AID, 6, 0, 0, 1, 0, "x"),
                codec.Announcement(AID, 0, 256, 0, 1, 0, "x"), codec.Announcement(AID, 0, 0, 2, 1, 0, "x"),
                codec.Announcement(AID, 0, 0, 0, -1, 0, "x")):
        with pytest.raises(codec.CodecError):
            codec.encode_announcement(bad)
    long = codec.encode_announcement(codec.Announcement(AID, 1, 0, 0, 1, 0, "अ" * 100))
    assert len(long) == 20 + 1 + 138  # cut on a character boundary


def test_an_unknown_district_index_still_decodes_so_the_phone_can_relay_and_ignore_it():
    a = codec.Announcement(AID, 0, 200, 0, 1, 0, "x")
    assert codec.decode_announcement(codec.encode_announcement(a)) == a


# ---- mesh_text
def test_mesh_text_keeps_short_text_and_collapses_whitespace():
    assert announcements.mesh_text("a  b\n c") == "a b c"


@pytest.mark.parametrize("text", ["word " * 80, "शरण स्थल खुले हैं। " * 30, "x" * 500, "é" * 300])
def test_mesh_text_fits_139_bytes_ends_in_ellipsis_and_is_valid_utf8(text):
    out = announcements.mesh_text(text)
    assert len(out.encode()) <= 139 and out.endswith("…")
    out.encode().decode("utf-8")


def test_mesh_text_is_exact_at_the_limit():
    assert announcements.mesh_text("a" * 139) == "a" * 139
    assert announcements.mesh_text("a" * 140) == "a" * 136 + "…"


# ---- API
class Gen:
    def generate_json(self, prompt, schema):
        if schema.__name__ == "Translations":
            return json.dumps({"hi": "शरण स्थल खुले हैं 14:00"})
        return json.dumps({"hi": {"back_translation": "b", "faithful": True, "issues": ""}})


SRC = "Shelters are open 14:00"


def announce(c, text=SRC, district="puri", langs=None):
    r = c.post("/v1/announcements", json={"district": district, "text": text})
    assert r.status_code == 200, r.text
    body = {} if langs is None else {"langs": langs}
    return c.post(f"/v1/announcements/{r.json()['id']}/approve", json=body).json()


def mesh(c, **req):
    body = {"gateway_node": "a1b2c3d4e5f60718", "frames": [], **req}
    return c.post("/v1/sync", json=body, headers=KEY).json()


def decoded(body):
    return [codec.decode_announcement(base64.b64decode(p)) for p in body["announcements"]]


@pytest.fixture
def client(make_client):
    return make_client(announcement_textgen=Gen())


def test_approval_assigns_one_id_and_a_mesh_text_per_language(client):
    row = announce(client)
    assert len(bytes.fromhex(row["announcement_id"])) == 8
    assert row["mesh_texts"] == {"en": SRC, "hi": "शरण स्थल खुले हैं 14:00"}
    assert row["approved_seq"] > 0
    other = announce(client)
    assert other["announcement_id"] != row["announcement_id"] and other["approved_seq"] > row["approved_seq"]


def test_a_long_text_is_shortened_for_the_mesh_but_whole_on_http(client):
    long = "Shelters are open " + "and safe " * 40 + "14:00"
    row = announce(client, long, langs=["en"])
    assert len(row["mesh_texts"]["en"].encode()) <= 139 and row["mesh_texts"]["en"].endswith("…")
    full = client.get("/v1/announcements/active?district=puri", headers=KEY).json()["announcements"][0]
    assert full["texts"]["en"] == long and full["announcement_id"] == row["announcement_id"]


def test_sync_without_the_new_fields_is_unchanged(client):
    announce(client)
    body = mesh(client)
    assert "announcements" not in body and "announcement_cursor" not in body and "broadcasts" not in body


def test_sync_returns_one_frame_per_language_then_nothing_past_the_cursor(client):
    row = announce(client)
    body = mesh(client, announcement_cursor=0)
    got = decoded(body)
    assert [(a.lang, a.status, a.text) for a in got] == [(0, 0, SRC), (1, 0, "शरण स्थल खुले हैं 14:00")]
    assert all(a.announcement_id.hex() == row["announcement_id"] and a.district == 0 for a in got)
    assert all(a.approved_at == row["approved_at"] for a in got)
    assert body["announcement_cursor"] == row["approved_seq"]
    again = mesh(client, announcement_cursor=body["announcement_cursor"])
    assert again["announcements"] == [] and again["announcement_cursor"] == body["announcement_cursor"]


def test_district_filters_and_districts_without_cursor_work(client):
    announce(client, district="puri")
    announce(client, district="delhi")
    assert {a.district for a in decoded(mesh(client, district="delhi"))} == {1}
    assert {a.district for a in decoded(mesh(client, district="puri"))} == {0}
    assert {a.district for a in decoded(mesh(client, announcement_cursor=0))} == {0, 1}
    assert {a.district for a in decoded(mesh(client, district="nowhere"))} == {0, 1}  # unknown = no filter


def test_withdrawal_is_a_new_event_that_supersedes(client):
    row = announce(client)
    first = mesh(client, announcement_cursor=0)
    assert client.post(f"/v1/announcements/{row['id']}/withdraw").status_code == 200
    second = mesh(client, announcement_cursor=first["announcement_cursor"])
    got = decoded(second)
    assert {(a.lang, a.status) for a in got} == {(0, 1), (1, 1)}
    assert {a.key for a in got}.isdisjoint({a.key for a in decoded(first)})
    assert second["announcement_cursor"] > first["announcement_cursor"]
    # a gateway that never saw the approval gets only the withdrawal
    assert {a.status for a in decoded(mesh(client, announcement_cursor=0))} == {1}


def test_withdrawing_a_draft_puts_nothing_on_the_wire(client):
    d = client.post("/v1/announcements", json={"district": "puri", "text": SRC}).json()
    client.post(f"/v1/announcements/{d['id']}/withdraw")
    assert mesh(client, announcement_cursor=0)["announcements"] == []


def test_an_approved_set_of_one_language_sends_only_that_language(client):
    announce(client, langs=["hi"])
    assert [a.lang for a in decoded(mesh(client, announcement_cursor=0))] == [1]


def test_events_older_than_72_h_are_not_sent(make_client, clock):
    c = make_client(announcement_textgen=Gen())
    announce(c)
    clock.t += 72 * 3600 + 1
    assert mesh(c, announcement_cursor=0)["announcements"] == []


def test_a_cursor_beyond_the_newest_means_the_store_was_reset(client):
    announce(client)
    assert len(decoded(mesh(client, announcement_cursor=10**15))) == 2


def test_sequence_numbers_never_repeat_even_within_one_millisecond():
    rows = [{"approved_seq": 500}]
    assert announcements.next_seq(rows, 100) == 501 and announcements.next_seq(rows, 900) == 900


def test_a_page_never_splits_one_events_languages_and_the_cursor_resumes():
    def row(i, seq):
        return {"announcement_id": f"{i:016x}", "district": "puri", "status": "approved", "approved_at": 1_790_000_000,
                "approved_seq": seq, "approved_langs": ["en", "hi"], "mesh_texts": {"en": "a", "hi": "b"}}
    rows = [row(i, i) for i in range(1, 4)]
    page, cur = announcements.sync_payloads(rows, 0, 1_790_000_100, limit=3)
    assert len(page) == 2 and cur == 1
    page, cur = announcements.sync_payloads(rows, cur, 1_790_000_100, limit=3)
    assert len(page) == 2 and cur == 2
    page, cur = announcements.sync_payloads(rows, cur, 1_790_000_100, limit=3)
    assert len(page) == 2 and cur == 3


def test_rows_approved_before_mesh_delivery_existed_are_skipped():
    old = {"district": "puri", "status": "approved", "approved_at": 1_790_000_000, "approved_langs": ["en"]}
    assert announcements.sync_payloads([old], 0, 1_790_000_100) == ([], 0)
