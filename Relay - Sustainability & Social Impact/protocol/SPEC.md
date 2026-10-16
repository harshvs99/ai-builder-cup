# Relay wire format, v1

This file defines the bytes that a Relay `NEED` or `ACK` occupies. The transport
(bitchat BLE mesh, Nearby, or anything else) carries these bytes unchanged as its
payload. The backend implementation is `backend/app/protocol.py`. The Android
implementation must produce byte-identical output for the same field values.

Conventions:
- All integers are big-endian.
- Strings are UTF-8, prefixed by a 1-byte length. Encoders truncate a string at a
  character boundary so it fits its maximum.
- Decoders **must ignore trailing bytes** after the last defined field. That lets
  v1 gain optional fields without a version bump.
- Decoders reject an unknown `version`. They map an unknown `category` to `OTHER`
  and an unknown `lang` to `und`, and drop unknown flag bits.

## NEED (type `0x01`): at most 128 bytes, typically 60–90

| Offset | Size | Field | Notes |
|---|---|---|---|
| 0 | 1 | `version` | `1` |
| 1 | 1 | `type` | `0x01` |
| 2 | 8 | `msg_id` | Random 64-bit value. Used for dedupe everywhere |
| 10 | 4 | `origin_node` | Random per install. ACKs are addressed to it |
| 14 | 4 | `created_at` | Unix seconds, from the phone's clock |
| 18 | 1 | `category` | See the table below |
| 19 | 1 | `urgency` | 0–3, where 3 means life-threatening now |
| 20 | 1 | `people` | People affected, 0–255 (0 = unknown) |
| 21 | 1 | `flags` | Bitfield, see below |
| 22 | 4 | `lat_e5` | Signed. Latitude × 100 000 |
| 26 | 4 | `lon_e5` | Signed. Longitude × 100 000 |
| 30 | 1 | `loc_acc` | 0 = no GPS fix; otherwise accuracy in 5 m steps (1 = ≤5 m … 255 = ≤1275 m) |
| 31 | 1 | `lang` | Requester language, see below |
| 32 | 1+n | `item` | ≤ 16 bytes, English, lowercase. For example `insulin` |
| … | 1+n | `landmark` | ≤ 24 bytes, English. For example `school` |
| … | 1+n | `summary` | ≤ 40 bytes, English. For example `mother needs insulin, diabetic` |

The fixed part is 32 bytes, so the maximum size is 32 + 3 + 16 + 24 + 40 = 115 bytes.

Hop count is **not** part of the payload. Stock bitchat relays can't modify the
payload, so the gateway works out the hop count from the transport TTL and reports
it beside the packet in `/v1/sync`.

## ACK (type `0x02`): at most 186 bytes

| Offset | Size | Field | Notes |
|---|---|---|---|
| 0 | 1 | `version` | `1` |
| 1 | 1 | `type` | `0x02` |
| 2 | 8 | `ref_msg_id` | The `msg_id` of the NEED this ACK answers |
| 10 | 4 | `target_node` | `origin_node` of that NEED. Only that phone displays the ACK |
| 14 | 4 | `ticket_no` | Control-room ticket number shown to the user |
| 18 | 1 | `status` | 1 RECEIVED · 2 DISPATCHED · 3 ON_THE_WAY · 4 RESOLVED |
| 19 | 2 | `eta_min` | 0 = unknown |
| 21 | 4 | `created_at` | Unix seconds, from the server clock |
| 25 | 1+n | `text` | ≤ 160 bytes UTF-8, already in the requester's language |

The ACK text limit is larger than the NEED limits because Odia and Devanagari use
3 bytes per character. 186 bytes is still well under bitchat's fragmentation
threshold of about 469 bytes.

## Enums

**category:** 0 OTHER · 1 MEDICAL · 2 RESCUE · 3 WATER · 4 FOOD · 5 SHELTER · 6 MISSING_PERSON

**flags:** bit 0 ELDERLY · bit 1 CHILD · bit 2 PREGNANT · bit 3 INJURED · bit 4 TRAPPED · bit 5 DISABLED

**lang:** 0 `und` · 1 `en` · 2 `hi` · 3 `or` (Odia) · 4 `tl` (Tagalog) · 5 `war` (Waray) · 6 `ceb` (Cebuano)

## Carrying these bytes over bitchat

Relay payloads ride in a bitchat broadcast packet with a Relay-specific bitchat
`type` byte. The value is still to be agreed with the bitchat maintainers.
Current stock clients relay unknown broadcast types. On re-sending a carried
packet, the bitchat header timestamp must be refreshed, because iOS clients drop
packets whose timestamp is more than 120 s off their own clock. `created_at` above
keeps the true creation time.
