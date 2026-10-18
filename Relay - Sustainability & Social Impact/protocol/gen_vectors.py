#!/usr/bin/env python3
"""Reference encoder for protocol/SPEC.md. Prints protocol/vectors.json to stdout.

Deliberately dependency-free and literal so it can be checked against SPEC.md by eye.
Usage: python3 protocol/gen_vectors.py > protocol/vectors.json
"""
import hashlib
import json
import math
import struct

NEED, ACK, HELLO, INVENTORY, LOC_HINT, BROADCAST = 0x70, 0x71, 0x72, 0x73, 0x74, 0x75
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


# Photo tag table (SPEC 5.1): bit -> JSON key. The phone classifies a photo on-device into these; only the
# 16-bit mask travels, never pixels. Bits are never renumbered; bit 15 is reserved and must be 0.
PHOTO_TAGS = (
    "floodwater", "house_damaged", "house_collapsed", "roof_damaged", "person_injured", "people_stranded",
    "road_blocked", "tree_fallen", "power_line_down", "boat_damaged", "crops_damaged", "livestock_lost",
    "vehicle_damaged", "fire", "landslide",
)


def tags_mask(keys):
    return sum(1 << PHOTO_TAGS.index(k) for k in keys)


def need_payload(n):
    answers = n.get("answers") or []
    tags = tags_mask(n.get("photo_tags") or [])
    return (
        struct.pack(
            ">B8s8sIBBBBiiBB",
            3 if tags else 2 if answers else 1, n["msg_id"], n["origin_node"], n["created_at"], n["category"], n["urgency"],
            n["people"], n["vuln_flags"], e5(n["lat"]), e5(n["lon"]), n["loc_acc"], n["lang"],
        )
        + lp(n["item"], 16) + lp(n["landmark"], 24) + lp(n["summary"], 40)
        + (answers_bytes(answers) if answers or tags else b"")
        + (struct.pack(">H", tags) if tags else b"")
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


HINT_ACC_MAX_M = {1: 10, 2: 25, 3: 50, 4: 100}  # LOC_HINT accuracy buckets (SPEC 5.5): upper bound in metres
RSSI_UNKNOWN = -128


def loc_hint_payload(h):
    return struct.pack(
        ">B8s8siiBbI", 1, h["ref_msg_id"], h["relay_node"], e5(h["lat"]), e5(h["lon"]), h["acc"],
        h["rssi_dbm"], h["observed_at"],
    )


def loc_hint_key(ref_msg_id, relay_node):
    """Dedupe key (SPEC 5.5): first 8 bytes of SHA-256(type || ref_msg_id || relay_node)."""
    return hashlib.sha256(bytes([LOC_HINT]) + ref_msg_id + relay_node).digest()[:8]


BROADCAST_KINDS = {"disaster": 1, "cleared": 2}
DISASTERS = {"flood": 0, "cyclone": 1, "earthquake": 2, "landslide": 3}
BROADCAST_TTL_S = 24 * 3600  # a BROADCAST lives 24 h from issued_at (SPEC 5.6)


def broadcast_payload(b):
    return struct.pack(
        ">B8sBBIiiH", 1, b["bcast_id"], BROADCAST_KINDS[b["kind"]], DISASTERS[b["disaster"]], b["issued_at"],
        e5(b["lat"]), e5(b["lon"]), b["radius_km"],
    )


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

# NEED payload_version 3: v1 layout + 3 answer bytes (all-empty allowed) + photo_tags u16 BE. Appended after the v2 vectors.
def v3_vector(name, need, ttl, ts, sender):
    pl = need_payload(need)
    vectors.append({
        "name": name, "kind": "NEED",
        "fields": {**{k: (H(v) if isinstance(v, bytes) else v) for k, v in need.items() if k not in ("answers", "photo_tags")},
                   "lat_e5": e5(need["lat"]), "lon_e5": e5(need["lon"]),
                   "answers": answers_json(need.get("answers") or []),
                   "photo_tags": list(need["photo_tags"]), "photo_tags_mask": tags_mask(need["photo_tags"])},
        "frame": {"type": NEED, "ttl": ttl, "timestamp_ms": ts, "sender_id": H(sender), "recipient_id": None},
        "payload_hex": H(pl), "frame_hex": H(frame(NEED, ttl, ts, sender, pl)),
    })
    return pl


# Tags only: three empty answer slots, then mask 0x0021 (floodwater + people_stranded).
need6 = dict(
    msg_id=bytes.fromhex("1112131415161718"), origin_node=A, created_at=1_790_000_300,
    category=1, urgency=2, people=4, vuln_flags=0b10000, lat=20.296123, lon=85.824509,
    loc_acc=2, lang=0, item="boat", landmark="temple", summary="water in house, stuck on roof",
    photo_tags=["floodwater", "people_stranded"],
)
p6 = v3_vector("need_v3_tags_only", need6, 7, TS + 45, A)

# Answers and tags together, non-ASCII strings; mask 0x400A (house_damaged + roof_damaged + landslide).
need7 = dict(
    msg_id=bytes.fromhex("2122232425262728"), origin_node=B, created_at=1_790_000_400,
    category=4, urgency=2, people=6, vuln_flags=0b00110, lat=-0.000005, lon=179.999995,
    loc_acc=3, lang=2, item="ଆଶ୍ରୟ", landmark="ସ୍କୁଲ", summary="roof gone",
    answers=[(7, 1), (6, 2)], photo_tags=["house_damaged", "roof_damaged", "landslide"],
)
v3_vector("need_v3_answers_and_tags", need7, 5, TS + 46, B)

# Largest v3 NEED: every string at its byte limit, 3 answers, every defined tag. 118 + 3 + 2 = 123 byte payload, 145 byte frame.
need8 = dict(
    msg_id=bytes.fromhex("ffffffffffffffff"), origin_node=B, created_at=0xFFFFFFFF,
    category=6, urgency=3, people=255, vuln_flags=0xFF, lat=21.0, lon=86.0, loc_acc=5, lang=5,
    item="i" * 16, landmark="l" * 24, summary="s" * 40, answers=[(8, 1), (7, 2), (1, 0)],
    photo_tags=list(PHOTO_TAGS),
)
p8 = v3_vector("need_v3_max_size", need8, 7, TS + 47, B)
assert len(p8) == 123 and tags_mask(PHOTO_TAGS) == 0x7FFF

# LOC_HINT (SPEC 5.5): a relay with a fix vouches that it heard a no-fix NEED directly from its origin.
def hint_vector(name, h, ttl, ts):
    pl = loc_hint_payload(h)
    vectors.append({
        "name": name, "kind": "LOC_HINT",
        "fields": {**{k: (H(v) if isinstance(v, bytes) else v) for k, v in h.items()},
                   "lat_e5": e5(h["lat"]), "lon_e5": e5(h["lon"]),
                   "acc_max_m": HINT_ACC_MAX_M[h["acc"]],
                   "key": H(loc_hint_key(h["ref_msg_id"], h["relay_node"]))},
        "frame": {"type": LOC_HINT, "ttl": ttl, "timestamp_ms": ts, "sender_id": H(h["relay_node"]), "recipient_id": None},
        "payload_hex": H(pl), "frame_hex": H(frame(LOC_HINT, ttl, ts, h["relay_node"], pl)),
    })
    return pl


hint1 = dict(
    ref_msg_id=bytes.fromhex("0011223344556677"), relay_node=D, lat=20.296013, lon=85.824329,
    acc=2, rssi_dbm=-67, observed_at=1_790_000_005,
)
ph1 = hint_vector("loc_hint_basic", hint1, 7, TS + 50)

# Southern/western hemisphere, worst accepted accuracy, RSSI unknown (-128), relayed (ttl 4).
hint2 = dict(
    ref_msg_id=bytes.fromhex("ffeeddccbbaa9988"), relay_node=B, lat=-12.000005, lon=-77.123455,
    acc=4, rssi_dbm=RSSI_UNKNOWN, observed_at=1_790_003_700,
)
hint_vector("loc_hint_unknown_rssi_southwest", hint2, 4, TS + 60)

# BROADCAST (SPEC 5.6): the control room speaks to every phone. Kept in its own list so decoders that predate it,
# and test code that walks `vectors`, are unaffected.
broadcast_vectors = []


def broadcast_vector(name, b, ttl, ts, sender):
    pl = broadcast_payload(b)
    broadcast_vectors.append({
        "name": name, "kind": "BROADCAST",
        "fields": {**{k: (H(v) if isinstance(v, bytes) else v) for k, v in b.items()},
                   "lat_e5": e5(b["lat"]), "lon_e5": e5(b["lon"]), "expires_at": b["issued_at"] + BROADCAST_TTL_S},
        "frame": {"type": BROADCAST, "ttl": ttl, "timestamp_ms": ts, "sender_id": H(sender), "recipient_id": None},
        "payload_hex": H(pl), "frame_hex": H(frame(BROADCAST, ttl, ts, sender, pl)),
    })
    return pl


pb1 = broadcast_vector("broadcast_flood_whole_area", dict(
    bcast_id=bytes.fromhex("a1a2a3a4a5a6a7a8"), kind="disaster", disaster="flood", issued_at=1_790_000_000,
    lat=0.0, lon=0.0, radius_km=0), 7, TS + 70, D)
broadcast_vector("broadcast_earthquake_with_area", dict(
    bcast_id=bytes.fromhex("0102030405060708"), kind="disaster", disaster="earthquake", issued_at=1_790_003_600,
    lat=20.29612, lon=85.82451, radius_km=40), 5, TS + 80, B)
broadcast_vector("broadcast_cleared_southwest", dict(
    bcast_id=bytes.fromhex("ffffffffffffff00"), kind="cleared", disaster="landslide", issued_at=0xFFFFFFFF,
    lat=-12.000005, lon=-77.123455, radius_km=65535), 7, TS + 90, A)

# BROADCAST payloads every decoder must reject.
bad_broadcast = {
    "version_2": bytes([2]) + pb1[1:],
    "too_short": pb1[:-1],
    "trailing_byte": pb1 + b"\x00",
    "kind_zero": pb1[:9] + bytes([0]) + pb1[10:],
    "kind_three": pb1[:9] + bytes([3]) + pb1[10:],
    "disaster_four": pb1[:10] + bytes([4]) + pb1[11:],
    "lat_out_of_range": pb1[:15] + struct.pack(">i", 9_000_001) + pb1[19:],
    "lon_out_of_range": pb1[:19] + struct.pack(">i", -18_000_001) + pb1[23:],
}

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
    "version_3": bytes([3]) + p3[1:],  # a v2 body labelled v3: the photo_tags u16 is missing
    "version_4": bytes([4]) + p6[1:],
    "v3_photo_tags_zero": p6[:-2] + b"\x00\x00",
    "v3_photo_tag_bit15": p6[:-2] + struct.pack(">H", 0x8000 | tags_mask(["floodwater"])),
    "v3_missing_tag_byte": p6[:-1],
    "v3_extra_trailing_byte": p6 + b"\x00",
    "v3_no_tag_bytes": p6[:-2],
    "v2_with_photo_tags": bytes([2]) + p6[1:],
    "v1_with_photo_tags": bytes([1]) + p6[1:],
}

# LOC_HINT payloads (a frame's payload bytes) every decoder must reject.
hb = ph1
bad_hint = {
    "version_2": bytes([2]) + hb[1:],
    "too_short": hb[:-1],
    "trailing_byte": hb + b"\x00",
    "acc_zero": hb[:25] + bytes([0]) + hb[26:],
    "acc_five": hb[:25] + bytes([5]) + hb[26:],
    "lat_out_of_range": hb[:17] + struct.pack(">i", 9_000_001) + hb[21:],
    "lon_out_of_range": hb[:21] + struct.pack(">i", -18_000_001) + hb[25:],
}

print(json.dumps({
    "spec": "protocol/SPEC.md v1",
    "vectors": vectors,
    "must_reject_frames": {k: H(v) for k, v in bad.items()},
    "sizes": {"need1_payload": len(p), "need2_payload": len(p2), "ack1_payload": len(pa),
              "need_v2_max_payload": len(p5), "need_v3_max_payload": len(p8),"loc_hint_payload": len(ph1), "broadcast_payload": len(pb1)},
    "answer_bank": [
        {"id": qid, "key": key, "options": [{"value": v, "key": k} for k, v in opts.items()]}
        for qid, (key, opts) in BANK.items()
    ],
    "photo_tag_bank": [{"bit": i, "key": k} for i, k in enumerate(PHOTO_TAGS)],
    "must_reject_need_payloads": {k: H(v) for k, v in bad_need.items()},
    "must_reject_loc_hint_payloads": {k: H(v) for k, v in bad_hint.items()},
    "loc_hint_acc_max_m": {str(k): v for k, v in HINT_ACC_MAX_M.items()},
    "broadcast_vectors": broadcast_vectors,
    "must_reject_broadcast_payloads": {k: H(v) for k, v in bad_broadcast.items()},
    "broadcast_ttl_s": BROADCAST_TTL_S,
}, indent=2, ensure_ascii=False))
