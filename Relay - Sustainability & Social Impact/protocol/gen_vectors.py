#!/usr/bin/env python3
"""Reference encoder for protocol/SPEC.md. Prints protocol/vectors.json to stdout.

Deliberately dependency-free and literal so it can be checked against SPEC.md by eye.
Usage: python3 protocol/gen_vectors.py > protocol/vectors.json
"""
import hashlib
import json
import math
import struct

NEED, ACK, HELLO, INVENTORY = 0x70, 0x71, 0x72, 0x73
F_HAS_RECIPIENT, F_HAS_SIGNATURE = 0x01, 0x02


def frame(type_, ttl, ts_ms, sender, payload, recipient=None, signature=None):
    flags = (F_HAS_RECIPIENT if recipient else 0) | (F_HAS_SIGNATURE if signature else 0)
    out = struct.pack(">BBBQBH", 1, type_, ttl, ts_ms, flags, len(payload)) + sender
    if recipient:
        out += recipient
    out += payload
    if signature:
        out += signature
    return out


def truncate_utf8(s, limit):
    b = s.encode("utf-8")
    if len(b) <= limit:
        return b
    b = b[:limit]
    while True:
        try:
            b.decode("utf-8")
            return b
        except UnicodeDecodeError:
            b = b[:-1]


def lp(s, limit):
    b = truncate_utf8(s, limit)
    return bytes([len(b)]) + b


def e5(deg):
    v = deg * 100000
    return int(math.floor(abs(v) + 0.5)) * (1 if v >= 0 else -1)


def need_payload(n):
    return (
        struct.pack(
            ">B8s8sIBBBBiiBB",
            1, n["msg_id"], n["origin_node"], n["created_at"], n["category"], n["urgency"],
            n["people"], n["vuln_flags"], e5(n["lat"]), e5(n["lon"]), n["loc_acc"], n["lang"],
        )
        + lp(n["item"], 16) + lp(n["landmark"], 24) + lp(n["summary"], 40)
    )


def ack_payload(a):
    return struct.pack(
        ">B8s8s8sIBH", 1, a["ack_id"], a["ref_msg_id"], a["target_node"], a["ticket_no"],
        a["status"], a["eta_min"],
    ) + lp(a["text"], 112)


def hello_payload(h):
    return struct.pack(">B8sBBH", 1, h["node_id"], h["caps"], h["hops_to_gateway"], h["app_version"])


def inventory_payload(chunk_index, chunk_count, ids):
    assert len(ids) <= 19
    return struct.pack(">BBBB", 1, chunk_index, chunk_count, len(ids)) + b"".join(ids)


def opaque_key(type_, sender, ts_ms, payload):
    return hashlib.sha256(bytes([type_]) + sender + struct.pack(">Q", ts_ms) + payload).digest()[:8]


def H(b):
    return b.hex()


A = bytes.fromhex("a1a1a1a1a1a1a1a1")
B = bytes.fromhex("b2b2b2b2b2b2b2b2")
D = bytes.fromhex("d4d4d4d4d4d4d4d4")
TS = 1_790_000_000_123

vectors = []

need1 = dict(
    msg_id=bytes.fromhex("0011223344556677"), origin_node=A, created_at=1_790_000_000,
    category=0, urgency=3, people=1, vuln_flags=0b00001, lat=20.296123, lon=85.824509,
    loc_acc=1, lang=2, item="insulin", landmark="school", summary="mother needs insulin, diabetic",
)
p = need_payload(need1)
vectors.append({
    "name": "need_insulin_odisha", "kind": "NEED",
    "fields": {**{k: (H(v) if isinstance(v, bytes) else v) for k, v in need1.items()},
               "lat_e5": e5(need1["lat"]), "lon_e5": e5(need1["lon"])},
    "frame": {"type": NEED, "ttl": 7, "timestamp_ms": TS, "sender_id": H(A), "recipient_id": None},
    "payload_hex": H(p), "frame_hex": H(frame(NEED, 7, TS, A, p)),
})

# Negative coordinates, rounding half away from zero, and string truncation on a UTF-8
# character boundary (Devanagari is 3 bytes per code point).
need2 = dict(
    msg_id=bytes.fromhex("ffeeddccbbaa9988"), origin_node=B, created_at=1_790_003_600,
    category=1, urgency=2, people=6, vuln_flags=0b10110, lat=-12.000005, lon=-77.123455,
    loc_acc=0, lang=1, item="पानी और दवा चाहिए",
    landmark="near the old banyan tree by the river ghat",
    summary="six people trapped on roof, child injured, water rising fast",
)
p2 = need_payload(need2)
vectors.append({
    "name": "need_truncation_negative_coords", "kind": "NEED",
    "fields": {**{k: (H(v) if isinstance(v, bytes) else v) for k, v in need2.items()},
               "lat_e5": e5(need2["lat"]), "lon_e5": e5(need2["lon"]),
               "item_decoded": truncate_utf8(need2["item"], 16).decode(),
               "landmark_decoded": truncate_utf8(need2["landmark"], 24).decode(),
               "summary_decoded": truncate_utf8(need2["summary"], 40).decode()},
    "frame": {"type": NEED, "ttl": 3, "timestamp_ms": TS + 5, "sender_id": H(B), "recipient_id": None},
    "payload_hex": H(p2), "frame_hex": H(frame(NEED, 3, TS + 5, B, p2)),
})

ack1 = dict(
    ack_id=bytes.fromhex("1234567890abcdef"), ref_msg_id=need1["msg_id"], target_node=A,
    ticket_no=142, status=0, eta_min=0xFFFF,
    text="ଅନୁରୋଧ #142 ନିୟନ୍ତ୍ରଣ କକ୍ଷରେ ପହଞ୍ଚିଛି।",
)
pa = ack_payload(ack1)
vectors.append({
    "name": "ack_received_directed", "kind": "ACK",
    "fields": {**{k: (H(v) if isinstance(v, bytes) else v) for k, v in ack1.items()},
               "text_decoded": truncate_utf8(ack1["text"], 112).decode()},
    "frame": {"type": ACK, "ttl": 7, "timestamp_ms": TS + 9, "sender_id": H(D), "recipient_id": H(A)},
    "payload_hex": H(pa), "frame_hex": H(frame(ACK, 7, TS + 9, D, pa, recipient=A)),
})

hello = dict(node_id=D, caps=1, hops_to_gateway=0, app_version=1)
ph = hello_payload(hello)
vectors.append({
    "name": "hello_gateway", "kind": "HELLO",
    "fields": {**{k: (H(v) if isinstance(v, bytes) else v) for k, v in hello.items()}},
    "frame": {"type": HELLO, "ttl": 1, "timestamp_ms": TS, "sender_id": H(D), "recipient_id": None},
    "payload_hex": H(ph), "frame_hex": H(frame(HELLO, 1, TS, D, ph)),
})

ids = [need1["msg_id"], ack1["ack_id"]]
pi = inventory_payload(0, 1, ids)
vectors.append({
    "name": "inventory_two_ids", "kind": "INVENTORY",
    "fields": {"chunk_index": 0, "chunk_count": 1, "ids": [H(i) for i in ids]},
    "frame": {"type": INVENTORY, "ttl": 1, "timestamp_ms": TS, "sender_id": H(B), "recipient_id": None},
    "payload_hex": H(pi), "frame_hex": H(frame(INVENTORY, 1, TS, B, pi)),
})

pu = bytes.fromhex("deadbeef")
sig = bytes(range(64))
vectors.append({
    "name": "unknown_type_with_signature", "kind": "OPAQUE",
    "fields": {"opaque_key": H(opaque_key(0x7E, A, TS, pu)), "signature": H(sig)},
    "frame": {"type": 0x7E, "ttl": 5, "timestamp_ms": TS, "sender_id": H(A), "recipient_id": None},
    "payload_hex": H(pu), "frame_hex": H(frame(0x7E, 5, TS, A, pu, signature=sig)),
})

# Frames every decoder must reject.
good = frame(NEED, 7, TS, A, p)
bad = {
    "version_2": bytes([2]) + good[1:],
    "compressed_flag": good[:11] + bytes([0x04]) + good[12:],
    "trailing_byte": good + b"\x00",
    "truncated": good[:-1],
    "padded_to_256": good + bytes([256 - len(good)]) * (256 - len(good)),
}

print(json.dumps({
    "spec": "protocol/SPEC.md v1",
    "vectors": vectors,
    "must_reject_frames": {k: H(v) for k, v in bad.items()},
    "sizes": {"need1_payload": len(p), "need2_payload": len(p2), "ack1_payload": len(pa)},
}, indent=2, ensure_ascii=False))
