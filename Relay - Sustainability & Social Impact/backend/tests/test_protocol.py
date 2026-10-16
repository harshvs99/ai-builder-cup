import pytest

from app.protocol import (
    ACK_TEXT_MAX,
    NEED_MAX_BYTES,
    AckPacket,
    AckStatus,
    Category,
    DecodeError,
    Flag,
    Lang,
    NeedPacket,
    decode_ack,
    decode_need,
    encode_ack,
    encode_need,
    truncate_utf8,
)

from .conftest import make_need

GOLDEN_NEED = NeedPacket(
    msg_id=bytes.fromhex("0102030405060708"),
    origin_node=0xA1B2C3D4,
    created_at=1_790_000_000,
    category=Category.MEDICAL,
    urgency=3,
    people=1,
    flags=Flag.ELDERLY,
    lat=19.81352,
    lon=85.83119,
    loc_acc_m=15,
    lang=Lang.OR,
    item="insulin",
    landmark="school",
    summary="mother needs insulin",
)
# The Android encoder must produce exactly these bytes for GOLDEN_NEED.
GOLDEN_NEED_HEX = (
    "0101" "0102030405060708" "a1b2c3d4" "6ab13b80" "01" "03" "01" "01"
    "001e3ba8" "0082f7cf" "03" "03"
    "07" + b"insulin".hex() + "06" + b"school".hex() + "14" + b"mother needs insulin".hex()
)


def test_golden_need_bytes():
    assert encode_need(GOLDEN_NEED).hex() == GOLDEN_NEED_HEX
    assert decode_need(bytes.fromhex(GOLDEN_NEED_HEX)) == GOLDEN_NEED


def test_need_roundtrip_and_size():
    need = make_need()
    raw = encode_need(need)
    assert len(raw) <= NEED_MAX_BYTES
    assert decode_need(raw) == need


def test_maximal_need_fits_budget():
    need = make_need(item="x" * 50, landmark="y" * 50, summary="z" * 80)
    raw = encode_need(need)
    assert len(raw) == 32 + 3 + 16 + 24 + 40 <= NEED_MAX_BYTES
    decoded = decode_need(raw)
    assert (len(decoded.item), len(decoded.landmark), len(decoded.summary)) == (16, 24, 40)


def test_truncate_utf8_respects_character_boundaries():
    odia = "ଅନୁରୋଧ"  # 3 bytes per code point
    cut = truncate_utf8(odia, 7)
    assert len(cut) == 6
    assert cut.decode("utf-8") == odia[:2]


def test_no_gps_fix():
    need = make_need(lat=None, lon=None, loc_acc_m=None)
    decoded = decode_need(encode_need(need))
    assert decoded.lat is None and decoded.lon is None and decoded.loc_acc_m is None
    assert not decoded.has_location


def test_unknown_enum_values_degrade_gracefully():
    raw = bytearray(encode_need(make_need()))
    raw[18] = 99  # category
    raw[21] = 0xFF  # flags, including undefined bits
    raw[31] = 42  # lang
    decoded = decode_need(bytes(raw))
    assert decoded.category is Category.OTHER
    assert int(decoded.flags) == 0b111111
    assert decoded.lang is Lang.UND


def test_trailing_bytes_are_ignored():
    need = make_need()
    assert decode_need(encode_need(need) + b"\x00future") == need


@pytest.mark.parametrize(
    "mutate, message",
    [
        (lambda b: bytes([2]) + b[1:], "unsupported version"),
        (lambda b: b[:1] + bytes([0x02]) + b[2:], "expected type"),
        (lambda b: b[:20], "too short"),
        (lambda b: b[:-3], "truncated"),
        (lambda b: b[:32] + bytes([200]) + b[33:], "longer than"),
    ],
)
def test_malformed_needs_are_rejected(mutate, message):
    with pytest.raises(DecodeError, match=message):
        decode_need(mutate(encode_need(make_need())))


def test_ack_roundtrip_with_long_odia_text():
    ack = AckPacket(
        ref_msg_id=bytes(8),
        target_node=0x0A0A0A0A,
        ticket_no=142,
        status=AckStatus.DISPATCHED,
        created_at=1_790_000_000,
        text="ଅନୁରୋଧ #142: ସହାୟତା ଦଳ ପଠାଯାଇଛି। " * 5,
        eta_min=45,
    )
    raw = encode_ack(ack)
    assert len(raw) <= 25 + 1 + ACK_TEXT_MAX
    decoded = decode_ack(raw)
    assert decoded.text and ack.text.startswith(decoded.text)
    assert (decoded.ticket_no, decoded.status, decoded.eta_min) == (142, AckStatus.DISPATCHED, 45)
