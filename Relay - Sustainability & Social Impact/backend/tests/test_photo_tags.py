"""Photo tags (NEED payload v3): decoded at sync, counted on the ticket, served, never move the score.
Every API-level test here runs against both stores via the ``client`` fixture."""

import pytest

from app import codec
from app.codec import CodecError

from conftest import need_frame, sync

FLOOD, STRANDED, INJURED = 1 << 0, 1 << 5, 1 << 4


def _tickets(client):
    return client.get("/v1/tickets").json()["tickets"]


def test_v3_round_trip_with_and_without_answers():
    _, tags_only = need_frame(photo_tags=FLOOD | STRANDED)
    payload = codec.encode_need(tags_only)
    assert payload[0] == 3 and payload[-5:] == bytes([0, 0, 0, 0x00, 0x21])
    assert codec.decode_need(payload) == tags_only
    _, both = need_frame(answers=((1, 2),), photo_tags=FLOOD)
    assert codec.decode_need(codec.encode_need(both)) == both
    assert codec.encode_need(both)[0] == 3


def test_encoder_version_follows_content():
    _, n = need_frame(answers=((1, 2),), photo_tags=FLOOD)
    assert codec.encode_need(n)[0] == 3
    assert codec.encode_need(codec.Need(**{**n.__dict__, "photo_tags": 0}))[0] == 2
    assert codec.encode_need(codec.Need(**{**n.__dict__, "photo_tags": 0, "answers": ()}))[0] == 1


@pytest.mark.parametrize("mask", [0x8000, 0x8001, 0x10000, -1])
def test_encoder_rejects_reserved_bit_and_out_of_range(mask):
    _, n = need_frame(photo_tags=FLOOD)
    with pytest.raises(CodecError):
        codec.encode_need(codec.Need(**{**n.__dict__, "photo_tags": mask}))


def test_photo_tags_json_roundtrip_and_validation():
    assert codec.photo_tags_to_json(0x21) == ["floodwater", "people_stranded"]
    assert codec.photo_tags_to_json(0) == []
    assert codec.photo_tags_from_json(["people_stranded", "floodwater"]) == 0x21
    assert codec.photo_tags_from_json([]) == 0
    with pytest.raises(CodecError):
        codec.photo_tags_from_json(["selfie"])


def test_v3_need_stores_and_serves_tags(client):
    f, _ = need_frame(photo_tags=FLOOD | STRANDED)
    assert sync(client, [f]).json()["accepted"] == 1
    (t,) = _tickets(client)
    assert t["photo_tags"] == {"floodwater": 1, "people_stranded": 1}
    detail = client.get(f"/v1/tickets/{t['ticket_no']}").json()
    assert detail["photo_tags"] == t["photo_tags"]
    assert detail["packets"][0]["photo_tags"] == ["floodwater", "people_stranded"]


def test_ticket_without_tags_omits_the_field(client):
    sync(client, [need_frame(answers=((1, 2),))[0]])
    (t,) = _tickets(client)
    assert "photo_tags" not in t
    assert "photo_tags" not in client.get(f"/v1/tickets/{t['ticket_no']}").json()["packets"][0]


def test_merge_counts_reports_per_tag(client):
    for mask in (FLOOD | STRANDED, FLOOD, 0, INJURED):
        sync(client, [need_frame(photo_tags=mask)[0]])
    (t,) = _tickets(client)
    assert t["report_count"] == 4
    assert t["photo_tags"] == {"floodwater": 2, "people_stranded": 1, "person_injured": 1}


def test_tag_from_later_report_fills_a_ticket_that_had_none(client):
    sync(client, [need_frame()[0]])
    sync(client, [need_frame(photo_tags=FLOOD)[0]])
    (t,) = _tickets(client)
    assert t["report_count"] == 2 and t["photo_tags"] == {"floodwater": 1}


def test_tags_change_the_reason_note_only_never_the_score(client):
    sync(client, [need_frame(lat=20.1)[0]])
    sync(client, [need_frame(lat=20.5, photo_tags=STRANDED | FLOOD | INJURED)[0]])
    plain, tagged = sorted(_tickets(client), key=lambda t: t["lat"])
    assert plain["priority_score"] == tagged["priority_score"]
    assert plain["priority_tier"] == tagged["priority_tier"]
    assert "Photo shows" not in plain["priority_reason"]
    assert tagged["priority_reason"].startswith(plain["priority_reason"])
    assert tagged["priority_reason"].endswith("Photo shows: floodwater, person injured, people stranded.")


def test_dev_inject_accepts_photo_tags(client):
    body = {"needs": [{"category": "RESCUE", "urgency": 3, "lat": 20.29612, "lon": 85.82451, "item": "boat",
                       "photo_tags": ["floodwater", "boat_damaged"]}]}
    assert client.post("/v1/dev/inject", json=body).status_code == 200
    assert _tickets(client)[0]["photo_tags"] == {"floodwater": 1, "boat_damaged": 1}
    for bad in (["selfie"], ["Floodwater"], "floodwater"):
        body["needs"][0]["photo_tags"] = bad
        assert client.post("/v1/dev/inject", json=body).status_code == 422
