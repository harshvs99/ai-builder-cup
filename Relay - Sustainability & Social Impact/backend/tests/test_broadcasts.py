"""Control-room BROADCAST (SPEC 5.6): declared by an operator, handed to every gateway on every sync for 24 h."""

import base64

import pytest

from app import codec

from conftest import KEY, T0, need_frame, sync

OP = {"X-Operator-Key": "s3cret"}


def payloads(resp) -> list[codec.Broadcast]:
    return [codec.decode_broadcast(base64.b64decode(p)) for p in resp.json().get("broadcasts", [])]


def test_codec_round_trips_and_is_25_bytes():
    b = codec.Broadcast(bytes(range(8)), "disaster", "earthquake", T0, 2029612, 8582451, 40)
    raw = codec.encode_broadcast(b)
    assert len(raw) == 25 and raw[0] == 1
    assert codec.decode_broadcast(raw) == b
    edge = codec.Broadcast(bytes(8), "cleared", "landslide", 0xFFFFFFFF, -9_000_000, 18_000_000, 0xFFFF)
    assert codec.decode_broadcast(codec.encode_broadcast(edge)) == edge


@pytest.mark.parametrize("mutate", [
    lambda r: r[:-1], lambda r: r + b"\0", lambda r: bytes([2]) + r[1:],
    lambda r: r[:9] + bytes([0]) + r[10:], lambda r: r[:9] + bytes([3]) + r[10:],    # kind
    lambda r: r[:10] + bytes([4]) + r[11:],                                            # disaster
])
def test_decoder_drops_what_the_spec_says(mutate):
    good = codec.encode_broadcast(codec.Broadcast(bytes(8), "disaster", "flood", T0))
    with pytest.raises(codec.CodecError):
        codec.decode_broadcast(mutate(good))


def test_encoder_refuses_invalid_fields():
    for bad in (codec.Broadcast(bytes(7), "disaster", "flood", T0), codec.Broadcast(bytes(8), "x", "flood", T0),
                codec.Broadcast(bytes(8), "disaster", "fire", T0), codec.Broadcast(bytes(8), "disaster", "flood", -1),
                codec.Broadcast(bytes(8), "disaster", "flood", T0, radius_km=70000)):
        with pytest.raises(codec.CodecError):
            codec.encode_broadcast(bad)


def test_a_declaration_reaches_every_gateway_on_every_sync(client):
    r = client.post("/v1/broadcasts", json={"disaster": "flood"})
    assert r.status_code == 200
    body = r.json()
    assert body["kind"] == "disaster" and body["disaster"] == "flood" and body["expires_at"] - body["issued_at"] == 86400
    for gateway in ("a1b2c3d4e5f60718", "0011223344556677"):
        s = sync(client, [], gateway=gateway)
        got = payloads(s)
        assert [b.disaster for b in got] == ["flood"] and got[0].bcast_id.hex() == body["id"]
    assert len(payloads(sync(client, []))) == 1   # the same list again: gateways dedupe by id


def test_no_broadcast_means_no_key_so_older_gateways_see_no_change(client):
    assert "broadcasts" not in sync(client, []).json()


def test_they_stop_after_twenty_four_hours(client, clock):
    client.post("/v1/broadcasts", json={"disaster": "cyclone"})
    clock.t += 24 * 3600 - 120
    assert len(payloads(sync(client, []))) == 1
    clock.t += 300
    assert "broadcasts" not in sync(client, []).json()
    assert client.get("/v1/broadcasts").json()["broadcasts"] == []


def test_cleared_is_a_broadcast_too_and_an_area_needs_a_centre(client):
    assert client.post("/v1/broadcasts", json={"kind": "cleared", "disaster": "flood"}).status_code == 200
    assert payloads(sync(client, []))[0].kind == "cleared"
    assert client.post("/v1/broadcasts", json={"disaster": "flood", "radius_km": 30}).status_code == 422
    ok = client.post("/v1/broadcasts", json={"disaster": "flood", "lat": 20.29612, "lon": 85.82451, "radius_km": 30}).json()
    assert ok["area"] == {"lat": 20.29612, "lon": 85.82451, "radius_km": 30}
    b = [x for x in payloads(sync(client, [])) if x.radius_km][0]
    assert (b.lat_e5, b.lon_e5, b.radius_km) == (2029612, 8582451, 30)


@pytest.mark.parametrize("body", [{"disaster": "fire"}, {"kind": "alert", "disaster": "flood"}, {}, {"disaster": "flood", "lat": 91, "lon": 0, "radius_km": 5}])
def test_bad_requests_are_rejected(client, body):
    assert client.post("/v1/broadcasts", json=body).status_code == 422


def test_listing_and_declaring_need_the_operator_key_when_one_is_set(make_client):
    c = make_client(operator_key="s3cret")
    assert c.post("/v1/broadcasts", json={"disaster": "flood"}).status_code == 401
    assert c.post("/v1/broadcasts", json={"disaster": "flood"}, headers=KEY).status_code == 401
    assert c.get("/v1/broadcasts").status_code == 401
    assert c.post("/v1/broadcasts", json={"disaster": "flood"}, headers=OP).status_code == 200
    assert len(c.get("/v1/broadcasts", headers=OP).json()["broadcasts"]) == 1
    # gateways stay on the device key and still receive it
    assert len(payloads(sync(c, []))) == 1


def test_a_broadcast_does_not_disturb_need_traffic(client):
    client.post("/v1/broadcasts", json={"disaster": "flood"})
    r = sync(client, [need_frame()[0]])
    assert r.json()["accepted"] == 1 and len(payloads(r)) == 1


def test_an_ended_declaration_stops_being_handed_out_at_once(client, clock):
    client.post("/v1/broadcasts", json={"disaster": "flood"})
    client.post("/v1/broadcasts", json={"disaster": "cyclone"})
    clock.t += 60
    client.post("/v1/broadcasts", json={"kind": "cleared", "disaster": "flood"})
    got = [(b.kind, b.disaster) for b in payloads(sync(client, []))]
    assert got == [("disaster", "cyclone"), ("cleared", "flood")]
    clock.t += 60   # declared again later: on again, and the old all-clear does not touch it
    client.post("/v1/broadcasts", json={"disaster": "flood"})
    got = [(b.kind, b.disaster) for b in payloads(sync(client, []))]
    assert got == [("disaster", "cyclone"), ("cleared", "flood"), ("disaster", "flood")]


def test_without_superseded_keeps_order_and_skips_garbage():
    d = codec.encode_broadcast(codec.Broadcast(bytes(8), "disaster", "flood", T0))
    c = codec.encode_broadcast(codec.Broadcast(bytes([1]) * 8, "cleared", "flood", T0))   # same second: ends it
    assert codec.without_superseded([d, b"junk", c]) == [c]


def test_declaring_again_in_the_same_second_as_the_all_clear_still_counts(client):
    client.post("/v1/broadcasts", json={"disaster": "flood"})
    client.post("/v1/broadcasts", json={"kind": "cleared", "disaster": "flood"})
    again = client.post("/v1/broadcasts", json={"disaster": "flood"}).json()
    got = [(b.kind, b.issued_at) for b in payloads(sync(client, []))]
    assert got == [("cleared", T0 + 60), ("disaster", T0 + 61)] and again["issued_at"] == T0 + 61
