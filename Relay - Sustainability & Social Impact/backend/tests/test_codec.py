import random

import pytest

from app import codec
from app.codec import CodecError

from conftest import VECTORS


def _unhex(d):
    return {k: (bytes.fromhex(v) if isinstance(v, str) and k in {
        "msg_id", "origin_node", "ack_id", "ref_msg_id", "target_node", "node_id"} else v) for k, v in d.items()}


def _need_from_fields(f) -> codec.Need:
    f = _unhex(f)
    return codec.Need.make(
        msg_id=f["msg_id"], origin_node=f["origin_node"], created_at=f["created_at"], category=f["category"],
        urgency=f["urgency"], people=f["people"], vuln_flags=f["vuln_flags"], lat=f["lat"], lon=f["lon"],
        loc_acc=f["loc_acc"], lang=f["lang"], item=f["item"], landmark=f["landmark"], summary=f["summary"],
    )


def _vec(name):
    return next(v for v in VECTORS["vectors"] if v["name"] == name)


def _frame_from(v, payload):
    fr = v["frame"]
    sig = bytes.fromhex(v["fields"]["signature"]) if "signature" in v["fields"] else None
    return codec.encode_frame(
        fr["type"], fr["ttl"], fr["timestamp_ms"], bytes.fromhex(fr["sender_id"]), payload,
        bytes.fromhex(fr["recipient_id"]) if fr["recipient_id"] else None, sig,
    )


@pytest.mark.parametrize("v", VECTORS["vectors"], ids=lambda v: v["name"])
def test_golden_vector_encode_decode(v):
    kind, f = v["kind"], v["fields"]
    payload = bytes.fromhex(v["payload_hex"])
    frame_bytes = bytes.fromhex(v["frame_hex"])

    # encode: fields -> exact hex
    if kind == "NEED":
        need = _need_from_fields(f)
        assert need.lat_e5 == f["lat_e5"] and need.lon_e5 == f["lon_e5"]
        assert codec.encode_need(need).hex() == v["payload_hex"]
    elif kind == "ACK":
        ack = codec.Ack(**_unhex({k: f[k] for k in ("ack_id", "ref_msg_id", "target_node", "ticket_no", "status", "eta_min", "text")}))
        assert codec.encode_ack(ack).hex() == v["payload_hex"]
    elif kind == "HELLO":
        assert codec.encode_hello(codec.Hello(**_unhex(f))).hex() == v["payload_hex"]
    elif kind == "INVENTORY":
        inv = codec.Inventory(f["chunk_index"], f["chunk_count"], tuple(bytes.fromhex(i) for i in f["ids"]))
        assert codec.encode_inventory(inv).hex() == v["payload_hex"]
    elif kind == "OPAQUE":
        fr = v["frame"]
        key = codec.opaque_key(fr["type"], bytes.fromhex(fr["sender_id"]), fr["timestamp_ms"], payload)
        assert key.hex() == f["opaque_key"]
    assert _frame_from(v, payload).hex() == v["frame_hex"]

    # decode: hex -> fields
    fr = codec.decode_frame(frame_bytes)
    assert fr.type == v["frame"]["type"] and fr.ttl == v["frame"]["ttl"]
    assert fr.timestamp_ms == v["frame"]["timestamp_ms"]
    assert fr.sender_id.hex() == v["frame"]["sender_id"]
    assert (fr.recipient_id.hex() if fr.recipient_id else None) == v["frame"]["recipient_id"]
    assert fr.payload == payload
    if kind == "NEED":
        n = codec.decode_need(fr.payload)
        assert n.msg_id.hex() == f["msg_id"] and n.origin_node.hex() == f["origin_node"]
        assert (n.created_at, n.category, n.urgency, n.people, n.vuln_flags, n.loc_acc, n.lang) == (
            f["created_at"], f["category"], f["urgency"], f["people"], f["vuln_flags"], f["loc_acc"], f["lang"])
        assert (n.lat_e5, n.lon_e5) == (f["lat_e5"], f["lon_e5"])
        assert n.item == f.get("item_decoded", f["item"])
        assert n.landmark == f.get("landmark_decoded", f["landmark"])
        assert n.summary == f.get("summary_decoded", f["summary"])
    elif kind == "ACK":
        a = codec.decode_ack(fr.payload)
        assert a.ack_id.hex() == f["ack_id"] and a.ref_msg_id.hex() == f["ref_msg_id"]
        assert a.target_node.hex() == f["target_node"] and a.target_node == fr.recipient_id
        assert (a.ticket_no, a.status, a.eta_min, a.text) == (f["ticket_no"], f["status"], f["eta_min"], f["text_decoded"])
    elif kind == "HELLO":
        h = codec.decode_hello(fr.payload)
        assert (h.node_id.hex(), h.caps, h.hops_to_gateway, h.app_version) == (
            f["node_id"], f["caps"], f["hops_to_gateway"], f["app_version"])
    elif kind == "INVENTORY":
        i = codec.decode_inventory(fr.payload)
        assert (i.chunk_index, i.chunk_count, [x.hex() for x in i.ids]) == (f["chunk_index"], f["chunk_count"], f["ids"])
    elif kind == "OPAQUE":
        assert fr.signature.hex() == f["signature"]


def test_reference_sizes():
    s = VECTORS["sizes"]
    assert len(bytes.fromhex(_vec("need_insulin_odisha")["payload_hex"])) == s["need1_payload"]
    assert len(bytes.fromhex(_vec("need_truncation_negative_coords")["payload_hex"])) == s["need2_payload"]
    assert len(bytes.fromhex(_vec("ack_received_directed")["payload_hex"])) == s["ack1_payload"]


@pytest.mark.parametrize("name,hexstr", list(VECTORS["must_reject_frames"].items()))
def test_must_reject_frames(name, hexstr):
    with pytest.raises(CodecError):
        codec.decode_frame(bytes.fromhex(hexstr))


def test_rounding_half_away_from_zero():
    assert codec.to_e5(-12.000005) == -1200001
    assert codec.to_e5(12.000005) == 1200001
    assert codec.to_e5(0.0) == 0


def test_truncate_on_code_point_boundary():
    s = "पानी और दवा चाहिए"
    for limit in range(0, 30):
        b = codec.truncate_utf8(s, limit)
        assert len(b) <= limit
        b.decode("utf-8")
    assert codec.truncate_utf8("é" * 10, 5) == "é".encode() * 2


def test_decode_need_validation():
    good = bytes.fromhex(_vec("need_insulin_odisha")["payload_hex"])

    def mutate(off, val):
        b = bytearray(good)
        b[off] = val
        return bytes(b)

    # offsets: category 21, urgency 22, loc_acc 33, lang 34 (SPEC 5.1)
    for off, val in ((21, 7), (22, 4), (33, 6), (34, 6), (0, 2)):
        with pytest.raises(CodecError):
            codec.decode_need(mutate(off, val))
    with pytest.raises(CodecError):
        codec.decode_need(good + b"\x00")
    with pytest.raises(CodecError):
        codec.decode_need(good[:-1])
    # item length byte over the 16-byte limit
    with pytest.raises(CodecError):
        codec.decode_need(mutate(35, 17))
    # invalid UTF-8 in item
    bad = bytearray(good)
    bad[36] = 0xFF
    with pytest.raises(CodecError):
        codec.decode_need(bytes(bad))


def test_encode_rejects_bad_input():
    with pytest.raises(CodecError):
        codec.encode_frame(0x70, 7, 0, b"short", b"")
    with pytest.raises(CodecError):
        codec.encode_frame(0x70, 300, 0, bytes(8), b"")


def test_fuzz_random_bytes_only_codec_errors():
    rng = random.Random(1234)
    decoders = (codec.decode_frame, codec.decode_need, codec.decode_ack, codec.decode_hello, codec.decode_inventory)
    for _ in range(5000):
        data = rng.randbytes(rng.randint(0, 200))
        for dec in decoders:
            try:
                dec(data)
            except CodecError:
                pass


def test_fuzz_mutated_valid_frames():
    rng = random.Random(99)
    seeds = [bytes.fromhex(v["frame_hex"]) for v in VECTORS["vectors"]]
    for _ in range(3000):
        b = bytearray(rng.choice(seeds))
        for _ in range(rng.randint(1, 4)):
            b[rng.randrange(len(b))] = rng.randrange(256)
        data = bytes(b[: rng.randint(len(b) - 3, len(b))]) if rng.random() < 0.3 else bytes(b)
        try:
            fr = codec.decode_frame(data)
            for dec in (codec.decode_need, codec.decode_ack, codec.decode_hello, codec.decode_inventory):
                try:
                    dec(fr.payload)
                except CodecError:
                    pass
        except CodecError:
            pass
