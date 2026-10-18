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


# Intake answer bank (SPEC 5.1): id -> (key, {answer key: wire value}). Ids and values are wire values.
YNU = {"unknown": 0, "yes": 1, "no": 2}
BANK = {
    1: ("conscious", YNU),
    2: ("road_reachable", YNU),
    3: ("water_rising", YNU),
    4: ("roof_or_ground", {"unknown": 0, "roof": 1, "ground": 2}),
    5: ("children_or_elderly", YNU),
    6: ("how_long", {"unknown": 0, "today": 1, "1-2 days": 2, "3+ days": 3}),
    7: ("house_damaged", YNU),
    8: ("last_seen_today", YNU),
}


def answer_byte(qid, value):
    """question id in the high 5 bits, answer value in the low 3; 0x00 is an empty slot"""
    assert 1 <= qid <= 31 and 0 <= value <= 7
    return (qid << 3) | value


def answers_bytes(answers):
    """exactly 3 bytes, occupied slots first"""
    assert len(answers) <= 3
    return bytes([answer_byte(q, v) for q, v in answers] + [0] * (3 - len(answers)))


def need_payload(n):
    answers = n.get("answers") or []
    return (
        struct.pack(
            ">B8s8sIBBBBiiBB",
            2 if answers else 1, n["msg_id"], n["origin_node"], n["created_at"], n["category"], n["urgency"],
            n["people"], n["vuln_flags"], e5(n["lat"]), e5(n["lon"]), n["loc_acc"], n["lang"],
        )
        + lp(n["item"], 16) + lp(n["landmark"], 24) + lp(n["summary"], 40)
        + (answers_bytes(answers) if answers else b"")
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

# NEED payload_version 2: v1 layout + exactly 3 answer bytes. Appended after the v1 vectors, which are unchanged.
def answers_json(answers):
    return [{"id": q, "question": BANK[q][0], "value": v,
             "answer": next(k for k, x in BANK[q][1].items() if x == v)} for q, v in answers]


def v2_vector(name, need, ttl, ts, sender):
    pl = need_payload(need)
    vectors.append({
        "name": name, "kind": "NEED",
        "fields": {**{k: (H(v) if isinstance(v, bytes) else v) for k, v in need.items() if k != "answers"},
                   "lat_e5": e5(need["lat"]), "lon_e5": e5(need["lon"]),
                   "answers": answers_json(need["answers"])},
        "frame": {"type": NEED, "ttl": ttl, "timestamp_ms": ts, "sender_id": H(sender), "recipient_id": None},
        "payload_hex": H(pl), "frame_hex": H(frame(NEED, ttl, ts, sender, pl)),
    })
    return pl


need3 = dict(
    msg_id=bytes.fromhex("0102030405060708"), origin_node=A, created_at=1_790_000_100,
    category=0, urgency=3, people=2, vuln_flags=0b01000, lat=20.296123, lon=85.824509,
    loc_acc=1, lang=1, item="doctor", landmark="school", summary="father unconscious, needs doctor",
    answers=[(1, 2), (2, 1)],  # conscious=no, road_reachable=yes, third slot empty
)
p3 = v2_vector("need_v2_two_answers", need3, 7, TS + 20, A)

# All three slots used (answers from three different categories' questions), non-ASCII strings.
need4 = dict(
    msg_id=bytes.fromhex("a0a1a2a3a4a5a6a7"), origin_node=B, created_at=1_790_000_200,
    category=2, urgency=1, people=12, vuln_flags=0b00011, lat=-0.000005, lon=179.999995,
    loc_acc=3, lang=2, item="ପାଣି", landmark="ସ୍କୁଲ", summary="no drinking water",
    answers=[(5, 1), (6, 3), (7, 2)],
)
v2_vector("need_v2_three_answers", need4, 5, TS + 30, B)

# Largest v2 NEED: every string at its byte limit, 3 answers. 118 + 3 = 121 byte payload, 143 byte frame.
need5 = dict(
    msg_id=bytes.fromhex("ffffffffffffffff"), origin_node=B, created_at=0xFFFFFFFF,
    category=6, urgency=3, people=255, vuln_flags=0xFF, lat=21.0, lon=86.0, loc_acc=5, lang=5,
    item="i" * 16, landmark="l" * 24, summary="s" * 40, answers=[(8, 1), (7, 2), (1, 0)],
)
p5 = v2_vector("need_v2_max_size", need5, 7, TS + 40, B)
assert len(p5) == 121

# Frames every decoder must reject.
good = frame(NEED, 7, TS, A, p)
bad = {
    "version_2": bytes([2]) + good[1:],
    "compressed_flag": good[:11] + bytes([0x04]) + good[12:],
    "trailing_byte": good + b"\x00",
    "truncated": good[:-1],
    "padded_to_256": good + bytes([256 - len(good)]) * (256 - len(good)),
}

# NEED payloads (a frame's payload bytes) every decoder must reject. Built from need3's v2 payload.
v1_body = p3[1:-3]  # layout after the version byte, before the answer bytes
bad_need = {
    "v2_unknown_question_id": bytes([2]) + v1_body + bytes([(31 << 3) | 1, 0, 0]),
    "v2_value_out_of_range_for_question": bytes([2]) + v1_body + bytes([answer_byte(1, 3), 0, 0]),
    "v2_duplicate_question": bytes([2]) + v1_body + bytes([answer_byte(1, 1), answer_byte(1, 2), 0]),
    "v2_answer_after_empty_slot": bytes([2]) + v1_body + bytes([0, answer_byte(1, 1), 0]),
    "v2_no_answers": bytes([2]) + v1_body + bytes([0, 0, 0]),
    "v2_missing_answer_byte": p3[:-1],
    "v2_four_answer_bytes": p3 + b"\x00",
    "v1_with_answer_bytes": bytes([1]) + v1_body + answers_bytes([(1, 2)]),
    "version_3": bytes([3]) + p3[1:],
}

print(json.dumps({
    "spec": "protocol/SPEC.md v1",
    "vectors": vectors,
    "must_reject_frames": {k: H(v) for k, v in bad.items()},
    "sizes": {"need1_payload": len(p), "need2_payload": len(p2), "ack1_payload": len(pa),
              "need_v2_max_payload": len(p5)},
    "answer_bank": [
        {"id": qid, "key": key, "options": [{"value": v, "key": k} for k, v in opts.items()]}
        for qid, (key, opts) in BANK.items()
    ],
    "must_reject_need_payloads": {k: H(v) for k, v in bad_need.items()},
}, indent=2, ensure_ascii=False))
