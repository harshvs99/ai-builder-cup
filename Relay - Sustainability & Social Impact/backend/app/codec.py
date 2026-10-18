"""Relay mesh wire codec (protocol/SPEC.md, sections 3 and 5; NEED payload v1, v2 and v3, LOC_HINT).

Strict by design: every decoder raises ``CodecError`` (and nothing else) on any input that
the spec says to drop. Callers at a trust boundary (``/v1/sync``) catch ``CodecError`` and
count the frame as rejected; nothing here can crash an endpoint.

Encoders mirror ``protocol/gen_vectors.py`` byte for byte.
"""

from __future__ import annotations

import hashlib
import math
import struct
from dataclasses import dataclass

PROTOCOL_VERSION = 1
NEED_VERSION_ANSWERS = 2  # NEED payload_version 2 = v1 layout + 3 answer bytes (SPEC 5.1)
NEED_VERSION_PHOTO = 3  # NEED payload_version 3 = v1 layout + 3 answer bytes + photo_tags u16 (SPEC 5.1)
ANSWER_SLOTS = 3

TYPE_NEED = 0x70
TYPE_ACK = 0x71
TYPE_HELLO = 0x72
TYPE_INVENTORY = 0x73
TYPE_LOC_HINT = 0x74
TYPE_BROADCAST = 0x75

FLAG_HAS_RECIPIENT = 0x01
FLAG_HAS_SIGNATURE = 0x02
FLAG_IS_COMPRESSED = 0x04
FLAG_HAS_ROUTE = 0x08
FLAG_IS_RSR = 0x10

HEADER_LEN = 14  # version..payload_length
SENDER_LEN = 8
RECIPIENT_LEN = 8
SIGNATURE_LEN = 64

INITIAL_TTL = 7

CATEGORY_NAMES = ("MEDICAL", "RESCUE", "WATER", "FOOD", "SHELTER", "MISSING_PERSON", "OTHER")
LANG_CODES = ("en", "hi", "or", "tl", "war", "ceb")
VULN_NAMES = ("ELDERLY", "CHILD", "PREGNANT", "INJURED", "TRAPPED")
ACK_STATUS_NAMES = ("RECEIVED", "DISPATCHED", "ON_THE_WAY", "RESOLVED")

STATUS_RECEIVED = 0
STATUS_DISPATCHED = 1
STATUS_ON_THE_WAY = 2
STATUS_RESOLVED = 3

ETA_UNKNOWN = 0xFFFF

MAX_ITEM = 16
MAX_LANDMARK = 24
MAX_SUMMARY = 40
MAX_ACK_TEXT = 112

# LOC_HINT (SPEC 5.5): the relay's own accuracy bucket -> upper bound in metres; bucket 0 is not valid.
HINT_ACC_MAX_M = {1: 10, 2: 25, 3: 50, 4: 100}
RSSI_UNKNOWN = -128

# Intake question bank (SPEC 5.1): id -> (key, {answer key: wire value}). Mirrors the Kotlin QuestionBank;
# both are checked against ``answer_bank`` in protocol/vectors.json. Ids and values are never renumbered.
_YNU = {"unknown": 0, "yes": 1, "no": 2}
QUESTION_BANK: dict[int, tuple[str, dict[str, int]]] = {
    1: ("conscious", _YNU),
    2: ("road_reachable", _YNU),
    3: ("water_rising", _YNU),
    4: ("roof_or_ground", {"unknown": 0, "roof": 1, "ground": 2}),
    5: ("children_or_elderly", _YNU),
    6: ("how_long", {"unknown": 0, "today": 1, "1-2 days": 2, "3+ days": 3}),
    7: ("house_damaged", _YNU),
    8: ("last_seen_today", _YNU),
}
_QUESTION_IDS = {key: qid for qid, (key, _) in QUESTION_BANK.items()}

# Photo tag table (SPEC 5.1): bit -> JSON key. The phone classifies a photo on-device into these and only the
# 16-bit mask travels, never pixels. Mirrors the Kotlin/Swift tables; checked against ``photo_tag_bank`` in
# protocol/vectors.json. Bits are never renumbered; bit 15 is reserved and must be 0.
PHOTO_TAG_NAMES = (
    "floodwater", "house_damaged", "house_collapsed", "roof_damaged", "person_injured", "people_stranded",
    "road_blocked", "tree_fallen", "power_line_down", "boat_damaged", "crops_damaged", "livestock_lost",
    "vehicle_damaged", "fire", "landslide",
)
PHOTO_TAGS_MASK = (1 << len(PHOTO_TAG_NAMES)) - 1  # every defined bit; bit 15 is reserved
_PHOTO_TAG_BITS = {k: i for i, k in enumerate(PHOTO_TAG_NAMES)}

_NEED_FIXED = struct.Struct(">B8s8sIBBBBiiBB")  # 35 bytes
_ACK_FIXED = struct.Struct(">B8s8s8sIBH")  # 32 bytes (+1 length byte for text)
_HELLO = struct.Struct(">B8sBBH")
_LOC_HINT = struct.Struct(">B8s8siiBbI")  # 31 bytes (SPEC 5.5)
_BROADCAST = struct.Struct(">B8sBBIiiH")  # 25 bytes (SPEC 5.6)
_FRAME_HEAD = struct.Struct(">BBBQBH")


class CodecError(ValueError):
    """Raised for any frame or payload that must be dropped."""


# --------------------------------------------------------------------------------------
# helpers
# --------------------------------------------------------------------------------------

def to_e5(degrees: float) -> int:
    """degrees * 1e5 rounded half away from zero (SPEC 5.1)."""
    v = degrees * 100000
    return int(math.floor(abs(v) + 0.5)) * (1 if v >= 0 else -1)


def truncate_utf8(s: str, limit: int) -> bytes:
    """UTF-8 encode ``s`` and cut to at most ``limit`` bytes on a code point boundary."""
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


def answers_to_json(answers: tuple[tuple[int, int], ...]) -> dict[str, str]:
    """(question id, value) pairs -> {question key: answer key}, e.g. {"conscious": "no"}."""
    out: dict[str, str] = {}
    for qid, value in answers:
        key, options = QUESTION_BANK[qid]
        out[key] = next(k for k, v in options.items() if v == value)
    return out


def answers_from_json(d: dict[str, str]) -> tuple[tuple[int, int], ...]:
    """Inverse of ``answers_to_json``; raises CodecError for a key or value outside the bank."""
    out = []
    for key, answer in d.items():
        qid = _QUESTION_IDS.get(key)
        if qid is None or answer not in QUESTION_BANK[qid][1]:
            raise CodecError(f"unknown answer {key}={answer!r}")
        out.append((qid, QUESTION_BANK[qid][1][answer]))
    return tuple(out)


def photo_tags_to_json(mask: int) -> list[str]:
    """Bitmask -> tag keys in bit order, e.g. 0x21 -> ["floodwater", "people_stranded"]."""
    return [k for i, k in enumerate(PHOTO_TAG_NAMES) if mask >> i & 1]


def photo_tags_from_json(keys: list[str]) -> int:
    """Inverse of ``photo_tags_to_json``; raises CodecError for a key outside the table."""
    mask = 0
    for key in keys:
        bit = _PHOTO_TAG_BITS.get(key)
        if bit is None:
            raise CodecError(f"unknown photo tag {key!r}")
        mask |= 1 << bit
    return mask


def _lp(s: str, limit: int) -> bytes:
    b = truncate_utf8(s, limit)
    return bytes([len(b)]) + b


def _read_lp(data: bytes, off: int, limit: int) -> tuple[str, int]:
    if off >= len(data):
        raise CodecError("missing string length byte")
    n = data[off]
    if n > limit:
        raise CodecError(f"string length {n} exceeds limit {limit}")
    end = off + 1 + n
    if end > len(data):
        raise CodecError("string runs past end of payload")
    try:
        return data[off + 1:end].decode("utf-8"), end
    except UnicodeDecodeError as e:
        raise CodecError("invalid UTF-8") from e


def _need_bytes(name: str, v: object, n: int) -> bytes:
    if not isinstance(v, (bytes, bytearray)) or len(v) != n:
        raise CodecError(f"{name} must be {n} bytes")
    return bytes(v)


def _u(name: str, v: object, bits: int) -> int:
    if isinstance(v, bool) or not isinstance(v, int) or not 0 <= v < (1 << bits):
        raise CodecError(f"{name} out of range for u{bits}")
    return v


def _check_bytes_input(data: object) -> bytes:
    if not isinstance(data, (bytes, bytearray, memoryview)):
        raise CodecError("input must be bytes")
    return bytes(data)


# --------------------------------------------------------------------------------------
# outer frame (SPEC 3)
# --------------------------------------------------------------------------------------

@dataclass(frozen=True)
class Frame:
    type: int
    ttl: int
    timestamp_ms: int
    flags: int
    sender_id: bytes
    recipient_id: bytes | None
    payload: bytes
    signature: bytes | None = None


def encode_frame(
    type_: int,
    ttl: int,
    timestamp_ms: int,
    sender_id: bytes,
    payload: bytes,
    recipient_id: bytes | None = None,
    signature: bytes | None = None,
) -> bytes:
    _u("type", type_, 8)
    _u("ttl", ttl, 8)
    _u("timestamp_ms", timestamp_ms, 64)
    sender = _need_bytes("sender_id", sender_id, SENDER_LEN)
    payload = _check_bytes_input(payload)
    if len(payload) > 0xFFFF:
        raise CodecError("payload too long")
    flags = 0
    if recipient_id is not None:
        recipient_id = _need_bytes("recipient_id", recipient_id, RECIPIENT_LEN)
        flags |= FLAG_HAS_RECIPIENT
    if signature is not None:
        signature = _need_bytes("signature", signature, SIGNATURE_LEN)
        flags |= FLAG_HAS_SIGNATURE
    out = _FRAME_HEAD.pack(PROTOCOL_VERSION, type_, ttl, timestamp_ms, flags, len(payload)) + sender
    if recipient_id is not None:
        out += recipient_id
    out += payload
    if signature is not None:
        out += signature
    return out


def decode_frame(data: bytes) -> Frame:
    """Decode an outer frame. Rejects per SPEC 3 "Relay decoder rules"."""
    data = _check_bytes_input(data)
    if len(data) < HEADER_LEN + SENDER_LEN:
        raise CodecError("frame shorter than header")
    version, type_, ttl, ts, flags, plen = _FRAME_HEAD.unpack_from(data, 0)
    if version != PROTOCOL_VERSION:
        raise CodecError(f"unsupported version {version}")
    if flags & (FLAG_IS_COMPRESSED | FLAG_HAS_ROUTE):
        raise CodecError("compressed/routed frames are not supported")
    has_recipient = bool(flags & FLAG_HAS_RECIPIENT)
    has_sig = bool(flags & FLAG_HAS_SIGNATURE)
    expected = HEADER_LEN + SENDER_LEN + (RECIPIENT_LEN if has_recipient else 0) + plen + (
        SIGNATURE_LEN if has_sig else 0
    )
    if len(data) != expected:
        raise CodecError(f"frame length {len(data)} != header-implied {expected}")
    off = HEADER_LEN
    sender = data[off:off + SENDER_LEN]
    off += SENDER_LEN
    recipient = None
    if has_recipient:
        recipient = data[off:off + RECIPIENT_LEN]
        off += RECIPIENT_LEN
    payload = data[off:off + plen]
    off += plen
    signature = data[off:off + SIGNATURE_LEN] if has_sig else None
    return Frame(type_, ttl, ts, flags, sender, recipient, payload, signature)


def opaque_key(type_: int, sender_id: bytes, timestamp_ms: int, payload: bytes) -> bytes:
    """Dedupe key for unknown types / payload versions (SPEC 5)."""
    return hashlib.sha256(
        bytes([type_]) + sender_id + struct.pack(">Q", timestamp_ms) + payload
    ).digest()[:8]


# --------------------------------------------------------------------------------------
# NEED (SPEC 5.1)
# --------------------------------------------------------------------------------------

@dataclass(frozen=True)
class Need:
    msg_id: bytes
    origin_node: bytes
    created_at: int
    category: int
    urgency: int
    people: int
    vuln_flags: int
    lat_e5: int
    lon_e5: int
    loc_acc: int
    lang: int
    item: str
    landmark: str
    summary: str
    # (question id, answer value) pairs, at most 3; empty = encoded as a v1 payload
    answers: tuple[tuple[int, int], ...] = ()
    # bitmask over PHOTO_TAG_NAMES; 0 = no photo (v1/v2 payload)
    photo_tags: int = 0

    @classmethod
    def make(cls, *, lat: float, lon: float, **kw) -> "Need":
        return cls(lat_e5=to_e5(lat), lon_e5=to_e5(lon), **kw)

    @property
    def lat(self) -> float:
        return self.lat_e5 / 100000

    @property
    def lon(self) -> float:
        return self.lon_e5 / 100000


def encode_need(n: Need) -> bytes:
    _need_bytes("msg_id", n.msg_id, 8)
    _need_bytes("origin_node", n.origin_node, 8)
    _u("created_at", n.created_at, 32)
    if not (isinstance(n.category, int) and 0 <= n.category <= 6):
        raise CodecError("category out of range")
    if not (isinstance(n.urgency, int) and 0 <= n.urgency <= 3):
        raise CodecError("urgency out of range")
    _u("people", n.people, 8)
    _u("vuln_flags", n.vuln_flags, 8)
    for name, v in (("lat_e5", n.lat_e5), ("lon_e5", n.lon_e5)):
        if isinstance(v, bool) or not isinstance(v, int) or not -(1 << 31) <= v < (1 << 31):
            raise CodecError(f"{name} out of range for i32")
    if not (isinstance(n.loc_acc, int) and 0 <= n.loc_acc <= 5):
        raise CodecError("loc_acc out of range")
    if not (isinstance(n.lang, int) and 0 <= n.lang <= 5):
        raise CodecError("lang out of range")
    answers = tuple(n.answers)
    if len(answers) > ANSWER_SLOTS:
        raise CodecError("too many answers")
    for qid, value in answers:
        if qid not in QUESTION_BANK or value not in QUESTION_BANK[qid][1].values():
            raise CodecError("unknown question id or answer value")
    if len({q for q, _ in answers}) != len(answers):
        raise CodecError("duplicate question id")
    _u("photo_tags", n.photo_tags, 16)
    if n.photo_tags & ~PHOTO_TAGS_MASK:
        raise CodecError("photo_tags uses a reserved bit")
    version = NEED_VERSION_PHOTO if n.photo_tags else NEED_VERSION_ANSWERS if answers else PROTOCOL_VERSION
    return (
        _NEED_FIXED.pack(
            version, n.msg_id, n.origin_node, n.created_at, n.category, n.urgency,
            n.people, n.vuln_flags, n.lat_e5, n.lon_e5, n.loc_acc, n.lang,
        )
        + _lp(n.item, MAX_ITEM)
        + _lp(n.landmark, MAX_LANDMARK)
        + _lp(n.summary, MAX_SUMMARY)
        + (bytes([(q << 3) | v for q, v in answers] + [0] * (ANSWER_SLOTS - len(answers))) if version > PROTOCOL_VERSION else b"")
        + (struct.pack(">H", n.photo_tags) if n.photo_tags else b"")
    )


def decode_need(payload: bytes) -> Need:
    payload = _check_bytes_input(payload)
    if len(payload) < _NEED_FIXED.size + 3:
        raise CodecError("NEED payload too short")
    (ver, msg_id, origin, created_at, category, urgency, people, vuln, lat_e5, lon_e5,
     loc_acc, lang) = _NEED_FIXED.unpack_from(payload, 0)
    if ver not in (PROTOCOL_VERSION, NEED_VERSION_ANSWERS, NEED_VERSION_PHOTO):
        raise CodecError("unsupported NEED payload version")
    if category > 6 or urgency > 3 or loc_acc > 5 or lang > 5:
        raise CodecError("enum field out of range")
    off = _NEED_FIXED.size
    item, off = _read_lp(payload, off, MAX_ITEM)
    landmark, off = _read_lp(payload, off, MAX_LANDMARK)
    summary, off = _read_lp(payload, off, MAX_SUMMARY)
    answers: list[tuple[int, int]] = []
    photo_tags = 0
    if ver != PROTOCOL_VERSION:
        tail = ANSWER_SLOTS + (2 if ver == NEED_VERSION_PHOTO else 0)
        if len(payload) - off != tail:
            raise CodecError(f"v{ver} NEED needs exactly {tail} bytes after the summary")
        empty = False
        for b in payload[off:off + ANSWER_SLOTS]:
            off += 1
            if b == 0:
                empty = True
                continue
            qid, value = b >> 3, b & 7
            if empty or qid not in QUESTION_BANK or value not in QUESTION_BANK[qid][1].values() \
                    or any(q == qid for q, _ in answers):
                raise CodecError("invalid answer byte")
            answers.append((qid, value))
        if not answers and ver == NEED_VERSION_ANSWERS:
            raise CodecError("v2 NEED without answers")
        if ver == NEED_VERSION_PHOTO:
            (photo_tags,) = struct.unpack_from(">H", payload, off)
            off += 2
            if photo_tags == 0 or photo_tags & ~PHOTO_TAGS_MASK:
                raise CodecError("v3 NEED needs photo tags and no reserved bit")
    if off != len(payload):
        raise CodecError("trailing bytes in NEED payload")
    return Need(msg_id, origin, created_at, category, urgency, people, vuln, lat_e5, lon_e5,
                loc_acc, lang, item, landmark, summary, tuple(answers), photo_tags)


# --------------------------------------------------------------------------------------
# ACK (SPEC 5.2)
# --------------------------------------------------------------------------------------

@dataclass(frozen=True)
class Ack:
    ack_id: bytes
    ref_msg_id: bytes
    target_node: bytes
    ticket_no: int
    status: int
    eta_min: int
    text: str


def encode_ack(a: Ack) -> bytes:
    _need_bytes("ack_id", a.ack_id, 8)
    _need_bytes("ref_msg_id", a.ref_msg_id, 8)
    _need_bytes("target_node", a.target_node, 8)
    _u("ticket_no", a.ticket_no, 32)
    if not (isinstance(a.status, int) and 0 <= a.status <= 3):
        raise CodecError("status out of range")
    _u("eta_min", a.eta_min, 16)
    return _ACK_FIXED.pack(
        PROTOCOL_VERSION, a.ack_id, a.ref_msg_id, a.target_node, a.ticket_no, a.status, a.eta_min
    ) + _lp(a.text, MAX_ACK_TEXT)


def decode_ack(payload: bytes) -> Ack:
    payload = _check_bytes_input(payload)
    if len(payload) < _ACK_FIXED.size + 1:
        raise CodecError("ACK payload too short")
    ver, ack_id, ref, target, ticket_no, status, eta = _ACK_FIXED.unpack_from(payload, 0)
    if ver != PROTOCOL_VERSION:
        raise CodecError("unsupported ACK payload version")
    if status > 3:
        raise CodecError("status out of range")
    text, off = _read_lp(payload, _ACK_FIXED.size, MAX_ACK_TEXT)
    if off != len(payload):
        raise CodecError("trailing bytes in ACK payload")
    return Ack(ack_id, ref, target, ticket_no, status, eta, text)


# --------------------------------------------------------------------------------------
# HELLO / INVENTORY (SPEC 5.3, 5.4) - not used by the backend, kept for vector parity
# --------------------------------------------------------------------------------------

@dataclass(frozen=True)
class Hello:
    node_id: bytes
    caps: int
    hops_to_gateway: int
    app_version: int


def encode_hello(h: Hello) -> bytes:
    _need_bytes("node_id", h.node_id, 8)
    _u("caps", h.caps, 8)
    _u("hops_to_gateway", h.hops_to_gateway, 8)
    _u("app_version", h.app_version, 16)
    return _HELLO.pack(PROTOCOL_VERSION, h.node_id, h.caps, h.hops_to_gateway, h.app_version)


def decode_hello(payload: bytes) -> Hello:
    payload = _check_bytes_input(payload)
    if len(payload) != _HELLO.size:
        raise CodecError("HELLO payload must be 13 bytes")
    ver, node_id, caps, hops, app_version = _HELLO.unpack(payload)
    if ver != PROTOCOL_VERSION:
        raise CodecError("unsupported HELLO payload version")
    return Hello(node_id, caps, hops, app_version)


@dataclass(frozen=True)
class Inventory:
    chunk_index: int
    chunk_count: int
    ids: tuple[bytes, ...]


def encode_inventory(inv: Inventory) -> bytes:
    if len(inv.ids) > 19:
        raise CodecError("too many ids in chunk")
    _u("chunk_index", inv.chunk_index, 8)
    if not (isinstance(inv.chunk_count, int) and 1 <= inv.chunk_count <= 255):
        raise CodecError("chunk_count out of range")
    for i in inv.ids:
        _need_bytes("id", i, 8)
    return struct.pack(">BBBB", PROTOCOL_VERSION, inv.chunk_index, inv.chunk_count, len(inv.ids)) + b"".join(inv.ids)


def decode_inventory(payload: bytes) -> Inventory:
    payload = _check_bytes_input(payload)
    if len(payload) < 4:
        raise CodecError("INVENTORY payload too short")
    ver, idx, count, n = struct.unpack_from(">BBBB", payload, 0)
    if ver != PROTOCOL_VERSION:
        raise CodecError("unsupported INVENTORY payload version")
    if count < 1 or n > 19 or len(payload) != 4 + 8 * n:
        raise CodecError("malformed INVENTORY payload")
    return Inventory(idx, count, tuple(payload[4 + 8 * i:12 + 8 * i] for i in range(n)))


# --------------------------------------------------------------------------------------
# LOC_HINT (SPEC 5.5)
# --------------------------------------------------------------------------------------

@dataclass(frozen=True)
class LocHint:
    ref_msg_id: bytes
    relay_node: bytes  # also the frame sender_id
    lat_e5: int
    lon_e5: int
    acc: int  # 1..4, see HINT_ACC_MAX_M
    rssi_dbm: int  # RSSI_UNKNOWN (-128) when the relay could not read it
    observed_at: int  # unix seconds

    @property
    def lat(self) -> float:
        return self.lat_e5 / 100000

    @property
    def lon(self) -> float:
        return self.lon_e5 / 100000

    @property
    def accuracy_max_m(self) -> int:
        return HINT_ACC_MAX_M[self.acc]

    @property
    def key(self) -> bytes:
        return loc_hint_key(self.ref_msg_id, self.relay_node)


def loc_hint_key(ref_msg_id: bytes, relay_node: bytes) -> bytes:
    """Dedupe key: first 8 bytes of SHA-256(0x74 || ref_msg_id || relay_node). One hint per relay per NEED."""
    return hashlib.sha256(bytes([TYPE_LOC_HINT]) + ref_msg_id + relay_node).digest()[:8]


def _coords_ok(lat_e5: int, lon_e5: int) -> bool:
    return abs(lat_e5) <= 9_000_000 and abs(lon_e5) <= 18_000_000


def encode_loc_hint(h: LocHint) -> bytes:
    _need_bytes("ref_msg_id", h.ref_msg_id, 8)
    _need_bytes("relay_node", h.relay_node, 8)
    if not (isinstance(h.acc, int) and not isinstance(h.acc, bool) and h.acc in HINT_ACC_MAX_M):
        raise CodecError("acc out of range")
    if isinstance(h.rssi_dbm, bool) or not isinstance(h.rssi_dbm, int) or not -128 <= h.rssi_dbm <= 127:
        raise CodecError("rssi_dbm out of range for i8")
    _u("observed_at", h.observed_at, 32)
    for name, v in (("lat_e5", h.lat_e5), ("lon_e5", h.lon_e5)):
        if isinstance(v, bool) or not isinstance(v, int):
            raise CodecError(f"{name} must be an int")
    if not _coords_ok(h.lat_e5, h.lon_e5):
        raise CodecError("coordinates out of range")
    return _LOC_HINT.pack(
        PROTOCOL_VERSION, h.ref_msg_id, h.relay_node, h.lat_e5, h.lon_e5, h.acc, h.rssi_dbm, h.observed_at
    )


def decode_loc_hint(payload: bytes) -> LocHint:
    payload = _check_bytes_input(payload)
    if len(payload) != _LOC_HINT.size:
        raise CodecError("LOC_HINT payload must be 31 bytes")
    ver, ref, relay, lat_e5, lon_e5, acc, rssi, observed_at = _LOC_HINT.unpack(payload)
    if ver != PROTOCOL_VERSION:
        raise CodecError("unsupported LOC_HINT payload version")
    if acc not in HINT_ACC_MAX_M:
        raise CodecError("acc out of range")
    if not _coords_ok(lat_e5, lon_e5):
        raise CodecError("coordinates out of range")
    return LocHint(ref, relay, lat_e5, lon_e5, acc, rssi, observed_at)


# --------------------------------------------------------------------------------------
# BROADCAST (SPEC 5.6): the control room speaking to every phone
# --------------------------------------------------------------------------------------

BROADCAST_TTL_S = 24 * 3600  # a broadcast lives 24 h from issued_at
BROADCAST_KINDS = {1: "disaster", 2: "cleared"}
DISASTER_CODES = {0: "flood", 1: "cyclone", 2: "earthquake", 3: "landslide"}
_BROADCAST_KIND_CODES = {v: k for k, v in BROADCAST_KINDS.items()}
_DISASTER_NAME_CODES = {v: k for k, v in DISASTER_CODES.items()}


@dataclass(frozen=True)
class Broadcast:
    bcast_id: bytes
    kind: str  # "disaster" | "cleared"
    disaster: str  # "flood" | "cyclone" | "earthquake" | "landslide"
    issued_at: int  # unix seconds
    lat_e5: int = 0
    lon_e5: int = 0
    radius_km: int = 0  # 0 = the whole control-room area

    @property
    def key(self) -> bytes:
        return self.bcast_id

    def is_live(self, now: float) -> bool:
        return 0 <= now - self.issued_at <= BROADCAST_TTL_S


def encode_broadcast(b: Broadcast) -> bytes:
    _need_bytes("bcast_id", b.bcast_id, 8)
    if b.kind not in _BROADCAST_KIND_CODES:
        raise CodecError("unknown broadcast kind")
    if b.disaster not in _DISASTER_NAME_CODES:
        raise CodecError("unknown disaster")
    _u("issued_at", b.issued_at, 32)
    _u("radius_km", b.radius_km, 16)
    for name, v in (("lat_e5", b.lat_e5), ("lon_e5", b.lon_e5)):
        if isinstance(v, bool) or not isinstance(v, int):
            raise CodecError(f"{name} must be an int")
    if not _coords_ok(b.lat_e5, b.lon_e5):
        raise CodecError("coordinates out of range")
    return _BROADCAST.pack(
        PROTOCOL_VERSION, b.bcast_id, _BROADCAST_KIND_CODES[b.kind], _DISASTER_NAME_CODES[b.disaster],
        b.issued_at, b.lat_e5, b.lon_e5, b.radius_km,
    )


def decode_broadcast(payload: bytes) -> Broadcast:
    payload = _check_bytes_input(payload)
    if len(payload) != _BROADCAST.size:
        raise CodecError("BROADCAST payload must be 25 bytes")
    ver, bid, kind, disaster, issued_at, lat_e5, lon_e5, radius = _BROADCAST.unpack(payload)
    if ver != PROTOCOL_VERSION:
        raise CodecError("unsupported BROADCAST payload version")
    if kind not in BROADCAST_KINDS or disaster not in DISASTER_CODES:
        raise CodecError("unknown broadcast kind or disaster")
    if not _coords_ok(lat_e5, lon_e5):
        raise CodecError("coordinates out of range")
    return Broadcast(bid, BROADCAST_KINDS[kind], DISASTER_CODES[disaster], issued_at, lat_e5, lon_e5, radius)
