"""Relay wire format v1: NEED and ACK payloads.

The byte layout is specified in ``protocol/SPEC.md`` at the root of the Relay
folder. The Android app must stay byte-compatible with this module.
"""

from __future__ import annotations

import enum
import math
import secrets
import struct
from dataclasses import dataclass

VERSION = 1

ITEM_MAX = 16
LANDMARK_MAX = 24
SUMMARY_MAX = 40
ACK_TEXT_MAX = 160
NEED_MAX_BYTES = 128

_NEED_HEAD = struct.Struct(">BB8sIIBBBBiiBB")  # 32 bytes
_ACK_HEAD = struct.Struct(">BB8sIIBHI")  # 25 bytes


class PacketType(enum.IntEnum):
    NEED = 0x01
    ACK = 0x02


class Category(enum.IntEnum):
    OTHER = 0
    MEDICAL = 1
    RESCUE = 2
    WATER = 3
    FOOD = 4
    SHELTER = 5
    MISSING_PERSON = 6


class Lang(enum.IntEnum):
    UND = 0
    EN = 1
    HI = 2
    OR = 3
    TL = 4
    WAR = 5
    CEB = 6

    @property
    def code(self) -> str:
        return self.name.lower()

    @classmethod
    def from_code(cls, code: str) -> "Lang":
        try:
            return cls[code.upper()]
        except KeyError:
            return cls.UND


class Flag(enum.IntFlag):
    ELDERLY = 1 << 0
    CHILD = 1 << 1
    PREGNANT = 1 << 2
    INJURED = 1 << 3
    TRAPPED = 1 << 4
    DISABLED = 1 << 5


ALL_FLAGS = Flag(0b111111)


class AckStatus(enum.IntEnum):
    RECEIVED = 1
    DISPATCHED = 2
    ON_THE_WAY = 3
    RESOLVED = 4


class DecodeError(ValueError):
    pass


def new_msg_id() -> bytes:
    return secrets.token_bytes(8)


def truncate_utf8(text: str, max_bytes: int) -> bytes:
    """Encode ``text`` as UTF-8, cut at a character boundary to fit ``max_bytes``."""
    raw = text.encode("utf-8")
    if len(raw) <= max_bytes:
        return raw
    return raw[:max_bytes].decode("utf-8", errors="ignore").encode("utf-8")


def _clamp(value: int, lo: int, hi: int) -> int:
    return max(lo, min(hi, value))


def _acc_to_byte(acc_m: float | None) -> int:
    if acc_m is None:
        return 0
    return _clamp(math.ceil(max(acc_m, 1) / 5), 1, 255)


@dataclass(frozen=True)
class NeedPacket:
    msg_id: bytes
    origin_node: int
    created_at: int
    category: Category
    urgency: int = 0
    people: int = 0
    flags: Flag = Flag(0)
    lat: float | None = None
    lon: float | None = None
    loc_acc_m: int | None = None  # None when there is no GPS fix
    lang: Lang = Lang.UND
    item: str = ""
    landmark: str = ""
    summary: str = ""

    @property
    def has_location(self) -> bool:
        return self.lat is not None and self.lon is not None

    @property
    def msg_id_hex(self) -> str:
        return self.msg_id.hex()


@dataclass(frozen=True)
class AckPacket:
    ref_msg_id: bytes
    target_node: int
    ticket_no: int
    status: AckStatus
    created_at: int
    text: str = ""
    eta_min: int = 0


def encode_need(p: NeedPacket) -> bytes:
    if len(p.msg_id) != 8:
        raise ValueError("msg_id must be 8 bytes")
    if p.has_location:
        if not (-90 <= p.lat <= 90 and -180 <= p.lon <= 180):
            raise ValueError("lat/lon out of range")
        lat_e5, lon_e5 = round(p.lat * 1e5), round(p.lon * 1e5)
        acc = _acc_to_byte(p.loc_acc_m if p.loc_acc_m is not None else 50)
    else:
        lat_e5 = lon_e5 = acc = 0
    head = _NEED_HEAD.pack(
        VERSION,
        PacketType.NEED,
        p.msg_id,
        p.origin_node & 0xFFFFFFFF,
        _clamp(p.created_at, 0, 0xFFFFFFFF),
        int(p.category),
        _clamp(p.urgency, 0, 3),
        _clamp(p.people, 0, 255),
        int(p.flags) & int(ALL_FLAGS),
        lat_e5,
        lon_e5,
        acc,
        int(p.lang),
    )
    out = bytearray(head)
    for text, limit in ((p.item, ITEM_MAX), (p.landmark, LANDMARK_MAX), (p.summary, SUMMARY_MAX)):
        raw = truncate_utf8(text, limit)
        out.append(len(raw))
        out += raw
    return bytes(out)


def encode_ack(a: AckPacket) -> bytes:
    if len(a.ref_msg_id) != 8:
        raise ValueError("ref_msg_id must be 8 bytes")
    head = _ACK_HEAD.pack(
        VERSION,
        PacketType.ACK,
        a.ref_msg_id,
        a.target_node & 0xFFFFFFFF,
        _clamp(a.ticket_no, 0, 0xFFFFFFFF),
        int(a.status),
        _clamp(a.eta_min, 0, 0xFFFF),
        _clamp(a.created_at, 0, 0xFFFFFFFF),
    )
    raw = truncate_utf8(a.text, ACK_TEXT_MAX)
    return head + bytes([len(raw)]) + raw


class _Reader:
    def __init__(self, data: bytes, offset: int):
        self.data = data
        self.offset = offset

    def string(self, limit: int, name: str) -> str:
        if self.offset >= len(self.data):
            raise DecodeError(f"truncated before {name}")
        n = self.data[self.offset]
        if n > limit:
            raise DecodeError(f"{name} longer than {limit} bytes")
        start = self.offset + 1
        end = start + n
        if end > len(self.data):
            raise DecodeError(f"truncated {name}")
        self.offset = end
        return self.data[start:end].decode("utf-8", errors="replace")


def _check_header(data: bytes, expected: PacketType, head: struct.Struct) -> None:
    if len(data) < 2:
        raise DecodeError("packet too short")
    if data[0] != VERSION:
        raise DecodeError(f"unsupported version {data[0]}")
    if data[1] != expected:
        raise DecodeError(f"expected type {expected.name}, got 0x{data[1]:02x}")
    if len(data) < head.size:
        raise DecodeError("packet too short")


def decode_need(data: bytes) -> NeedPacket:
    _check_header(data, PacketType.NEED, _NEED_HEAD)
    (_, _, msg_id, origin, created, cat, urgency, people, flags, lat_e5, lon_e5, acc, lang) = (
        _NEED_HEAD.unpack_from(data)
    )
    r = _Reader(data, _NEED_HEAD.size)
    item = r.string(ITEM_MAX, "item")
    landmark = r.string(LANDMARK_MAX, "landmark")
    summary = r.string(SUMMARY_MAX, "summary")
    has_fix = acc != 0
    if has_fix and not (-9_000_000 <= lat_e5 <= 9_000_000 and -18_000_000 <= lon_e5 <= 18_000_000):
        raise DecodeError("lat/lon out of range")
    return NeedPacket(
        msg_id=msg_id,
        origin_node=origin,
        created_at=created,
        category=Category(cat) if cat in Category._value2member_map_ else Category.OTHER,
        urgency=min(urgency, 3),
        people=people,
        flags=Flag(flags & int(ALL_FLAGS)),
        lat=lat_e5 / 1e5 if has_fix else None,
        lon=lon_e5 / 1e5 if has_fix else None,
        loc_acc_m=acc * 5 if has_fix else None,
        lang=Lang(lang) if lang in Lang._value2member_map_ else Lang.UND,
        item=item.strip().lower(),
        landmark=landmark.strip(),
        summary=summary.strip(),
    )


def decode_ack(data: bytes) -> AckPacket:
    _check_header(data, PacketType.ACK, _ACK_HEAD)
    (_, _, ref, target, ticket_no, status, eta, created) = _ACK_HEAD.unpack_from(data)
    if status not in AckStatus._value2member_map_:
        raise DecodeError(f"unknown ack status {status}")
    text = _Reader(data, _ACK_HEAD.size).string(ACK_TEXT_MAX, "text")
    return AckPacket(
        ref_msg_id=ref,
        target_node=target,
        ticket_no=ticket_no,
        status=AckStatus(status),
        created_at=created,
        text=text,
        eta_min=eta,
    )


def packet_type(data: bytes) -> PacketType | None:
    if len(data) < 2 or data[0] != VERSION:
        return None
    try:
        return PacketType(data[1])
    except ValueError:
        return None


def node_hex(node: int) -> str:
    return f"{node & 0xFFFFFFFF:08x}"
