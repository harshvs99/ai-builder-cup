# Relay mesh protocol, v1

This is the single source of truth for every byte Relay puts on the air or sends to the
backend. The Kotlin codec (`android/core-protocol`), the Python codec (`backend/app/codec.py`)
and the golden vectors (`protocol/vectors.json`) must all agree with this file. If code and
this file disagree, this file wins and the code is a bug.

All multi-byte integers are **big-endian** (network order). Strings are UTF-8 with a 1-byte
length prefix and are truncated on a Unicode code point boundary so the byte length never exceeds the
stated limit.

---

## 1. Lineage and licence

The outer frame is byte-compatible with the **bitchat v1 packet** (permissionlesstech/bitchat,
`BinaryProtocol.swift`, public domain under the Unlicense). Relay re-implements it from the
public-domain sources and this document. **No code may be copied from bitchat-android
(GPL-3.0).** Protocol facts (field layouts, UUIDs) are not copyrightable; code is.

Relay runs on **its own GATT service UUID**, so Relay phones do not join the bitchat network
by default. A later "bitchat bridge" mode can additionally host bitchat's service; because the
frame format is identical, bridging needs no translation.

---

## 2. GATT transport

| Item | Value |
|---|---|
| Service UUID | `5b9b5c02-f1b0-4ea1-9e8a-e08da2708c44` |
| Characteristic UUID | `77175a01-40b4-447c-b66e-0cae864e64d5` |
| Characteristic properties | `WRITE`, `WRITE_NO_RESPONSE`, `NOTIFY` (with CCCD `00002902-0000-1000-8000-00805f9b34fb`) |
| Advertisement | Legacy advertising, connectable, containing only the 128-bit service UUID. The scan response carries manufacturer-specific data, company id `0xFFFF`, payload = the advertiser's 8-byte `node_id` (a connection hint, see below) |
| Requested ATT MTU | 517 |
| Minimum usable ATT MTU | 185. A link that negotiates less is closed. |

Every node is **both** a peripheral (GATT server, advertising) and a central (scanning with a
`ScanFilter` on the service UUID, connecting as a GATT client).

- **Central → peripheral:** the central writes frames to the characteristic.
- **Peripheral → central:** the peripheral sends frames as notifications to subscribed centrals.
- **One whole frame per write or notification.** Frames are at most `MTU − 3` bytes. Relay
  never fragments at the link layer in v1; every v1 frame fits in 185 − 3 = 182 bytes except
  `INVENTORY`, which is chunked (§5.4) to fit.
- **Node-id hint:** advertising addresses are resolvable private addresses that change when advertising
  restarts, so a scanner cannot recognise a peer by MAC. Before connecting, a scanner reads the node id from the
  scan response and skips an advertiser that is itself, that it already has a link to, or that its allowlist
  rejects. If both nodes would connect, the one with the higher `node_id` waits 3 s so the lower one initiates.
  A scanner that does not see the hint (older build, scan response dropped) connects as before; the HELLO-based
  duplicate rule below remains the safety net.
- **Duplicate links:** two phones may each connect to the other as central. After both
  `HELLO`s are exchanged, if two links reach the same `node_id`, keep the link where the node
  with the numerically lower `node_id` (unsigned, big-endian compare) is the **central**, and
  close the other.
- **Link cap:** at most 4 central links plus whatever peripheral links arrive (cap total at 7).

---

## 3. Outer frame (bitchat v1 compatible)

```
offset size field
0      1    version          = 0x01
1      1    type             (§4)
2      1    ttl              hops remaining
3      8    timestamp_ms     unix epoch milliseconds, u64
11     1    flags            bit0 HAS_RECIPIENT, bit1 HAS_SIGNATURE, bit2 IS_COMPRESSED,
                             bit3 HAS_ROUTE, bit4 IS_RSR; other bits 0
12     2    payload_length   u16
14     8    sender_id        node_id of the node that ORIGINATED this frame
22     8    recipient_id     present iff HAS_RECIPIENT
..     n    payload          payload_length bytes
..     64   signature        present iff HAS_SIGNATURE (ignored in v1; kept when relaying)
```

Header is 14 bytes; with sender it is 22. Broadcast frames omit the recipient. A recipient of
eight `0xFF` bytes also means broadcast.

**Relay encoder rules:** version 1 only, no padding, `IS_COMPRESSED`, `HAS_ROUTE` and `IS_RSR`
always 0.

**Relay decoder rules:** reject if `version != 1`; reject if `IS_COMPRESSED` or `HAS_ROUTE` is
set; reject if the byte count does not exactly match the header (no trailing bytes, no
padding); ignore `IS_RSR`. A rejected frame is dropped silently and never relayed.

**Relaying:** a relay decrements `ttl` and otherwise forwards the frame byte-for-byte
(timestamp, sender, recipient, payload and signature unchanged). Frames with `ttl <= 1` on
arrival are stored but not flooded onward.

**Initial TTL:** 7 for `NEED`, `ACK` and `LOC_HINT`; 1 for `HELLO` and `INVENTORY` (never relayed).

---

## 4. Packet types

Relay allocates the `0x70–0x7F` block. bitchat allocates its own types upward from `0x01`
and has used up to `0x2C`; we will ask its maintainers to reserve `0x70–0x7F`.

| Type | Name | Recipient | TTL | Relayed | Dedupe key |
|---|---|---|---|---|---|
| `0x70` | `NEED` | none (broadcast) | 7 | yes | `msg_id` |
| `0x71` | `ACK` | `target_node` | 7 | yes (every node relays directed frames) | `ack_id` |
| `0x72` | `HELLO` | none | 1 | no | n/a |
| `0x73` | `INVENTORY` | none | 1 | no | n/a |
| `0x74` | `LOC_HINT` | none (broadcast) | 7 | yes | `hint_key` (§5.5) |
| `0x75` | `BROADCAST` | none (broadcast) | 7 | yes | `bcast_id` (§5.6) |

Any other type (including `LOC_HINT` or `BROADCAST` on a build that predates it): decoded, not processed, deduplicated by its opaque key (§5), and relayed if
`ttl > 1` (forward compatibility). Not stored.

---

## 5. Payloads

Every payload starts with a 1-byte `payload_version`: `0x01` for every type, plus `0x02` and `0x03` for `NEED`
(§5.1, intake answers; photo tags). A `NEED` or `ACK` with any other `payload_version` is treated like an unknown type (§4):
relayed if `ttl > 1`, never stored or shown.

**Upgrade consequence of NEED v2:** a build that predates v2 sees `payload_version = 2` as unknown, so it
**relays** the frame (opaque key, at most once per node) but does **not** show, store or upload it; a gateway on an
old build therefore never uploads a v2 NEED. Encoders emit v1 whenever a NEED has no answers, so v1 traffic is
unaffected; only a NEED that carries answers needs a v2-aware gateway somewhere on its path to reach the backend.
The same holds for **NEED v3** (photo tags): a build that predates v3 relays it but does not show, store or upload
it, so a NEED that carries photo tags needs a v3-aware gateway somewhere on its path. Encoders emit v1 / v2 whenever
there are no tags, so earlier traffic is unaffected.

**Opaque key** (for unknown types and unknown payload versions): the first 8 bytes of
SHA-256 over `type ‖ sender_id ‖ timestamp_ms (8 bytes BE) ‖ payload`. It goes in the same
seen-set, so opaque frames are relayed at most once per node.

### 5.1 `NEED` (type `0x70`), ≤ 128 bytes

```
size field
1    payload_version   = 0x01 (no answers), 0x02 (v1 layout + 3 answer bytes, below) or 0x03 (v2 layout + photo tags)
8    msg_id            random 64-bit, end-to-end identity and dedupe key
8    origin_node       node_id of the requester (ACKs are addressed here)
4    created_at        unix seconds, u32
1    category          0 MEDICAL, 1 RESCUE, 2 WATER, 3 FOOD, 4 SHELTER, 5 MISSING_PERSON, 6 OTHER
1    urgency           0..3 (3 = life-threatening)
1    people            1..255 (0 = unknown)
1    vuln_flags        bit0 ELDERLY, bit1 CHILD, bit2 PREGNANT, bit3 INJURED, bit4 TRAPPED
4    lat_e5            i32, degrees × 100000, rounded half away from zero
4    lon_e5            i32, degrees × 100000
1    loc_acc           0 no fix, 1 <10 m, 2 <50 m, 3 <200 m, 4 <1 km, 5 ≥1 km
1    lang              0 en, 1 hi, 2 or (Odia), 3 tl (Tagalog), 4 war (Waray), 5 ceb (Cebuano)
1+n  item              ≤ 16 bytes
1+n  landmark          ≤ 24 bytes
1+n  summary           ≤ 40 bytes
```

Fixed part 35 bytes, plus 3 length bytes, plus at most 80 string bytes: **max 118 bytes** (v1).
A v1 NEED frame is at most 22 + 118 = 140 bytes.

**`payload_version = 0x02`** is exactly the v1 layout (with the version byte set to 2) followed by **3 answer
bytes** after `summary`: answers to the fixed follow-up questions a dispatcher would ask. "How many people" is not
an answer; it is the `people` field. Max v2 payload 118 + 3 = **121 bytes**, frame 22 + 121 = **143 bytes**
(≤ 182 = MTU 185 − 3; the vector `need_v2_max_size` pins it).

```
answer byte   bit7..bit3  question id, 1..31 (0 = empty slot, whole byte is 0x00)
              bit2..bit0  answer value, 0..7 (0 = unknown)
              e.g. conscious=no  -> id 1, value 2 -> 0x0A
```
Occupied slots come first, then empty (`0x00`) slots; a question id appears at most once. The question bank
(ids, JSON keys, answer values) is fixed data, never renumbered; `protocol/vectors.json` carries it as
`answer_bank` and both codecs' tests check their tables against it:

| id | key | asked for | values (0 is always unknown) |
|---|---|---|---|
| 1 | `conscious` | MEDICAL | 1 yes, 2 no |
| 2 | `road_reachable` | MEDICAL | 1 yes, 2 no |
| 3 | `water_rising` | RESCUE | 1 yes, 2 no |
| 4 | `roof_or_ground` | RESCUE | 1 roof, 2 ground |
| 5 | `children_or_elderly` | WATER, FOOD | 1 yes, 2 no |
| 6 | `how_long` | WATER, FOOD | 1 today, 2 `1-2 days`, 3 `3+ days` |
| 7 | `house_damaged` | SHELTER | 1 yes, 2 no |
| 8 | `last_seen_today` | MISSING_PERSON | 1 yes, 2 no |

OTHER has no questions. Question text per language (en, hi, or, tl, war) lives in the app's core as data, so a
model or the rules only ever choose a question id. A new question needs a new id, and a new answer value on an
existing question needs a new `payload_version` (decoders reject what they do not know).

**`payload_version = 0x03`** is exactly the v1 layout (with the version byte set to 3), then the **3 answer bytes**
(same rules as v2, except that all three may be empty), then **`photo_tags`**, a `u16` big-endian bitmask. Max v3
payload 118 + 3 + 2 = **123 bytes**, frame 22 + 123 = **145 bytes** (≤ 182; the vector `need_v3_max_size` pins it).

```
bit  key                bit  key                bit  key
0    floodwater         6    road_blocked       12   vehicle_damaged
1    house_damaged      7    tree_fallen        13   fire
2    house_collapsed    8    power_line_down    14   landslide
3    roof_damaged       9    boat_damaged       15   reserved, must be 0
4    person_injured     10   crops_damaged
5    people_stranded    11   livestock_lost
```
The tag table is fixed data, never renumbered; `protocol/vectors.json` carries it as `photo_tag_bank` and every
codec's tests check their table against it. A new tag needs a new bit, and bit 15 stays reserved until a new
`payload_version` defines it.

**Privacy: tags only, never pixels.** The photo is classified **on the phone** into this vocabulary and discarded;
the image is never sent over the mesh or to the backend, so "no names, no numbers, no pixels" holds. A tag is what the
phone's classifier saw, an observation and not a verified assessment: the backend counts the reports that carried a
tag and shows it to the operator as context, and never lets it change a priority score.

Encoders emit **v1 when there are no answers and no tags, v2 when there is at least one answer and no tags, and v3
when there is any tag (with or without answers)**. Decoders (Kotlin, Swift and Python) accept all three. Validation on decode: `category ≤ 6`, `urgency ≤ 3`, `loc_acc ≤ 5`, `lang ≤ 5`, string lengths
within limits, valid UTF-8, no trailing bytes; for v2 additionally exactly 3 answer bytes, every non-empty byte a
known question id with a value that question defines, no duplicate id, no occupied slot after an empty one, and at
least one answer (all-empty v2 is invalid). For v3: exactly 3 answer bytes (same checks, but all-empty is valid) and
exactly 2 `photo_tags` bytes, nonzero (`photo_tags == 0` is invalid; encoders send v1 / v2 instead) with bit 15 clear.
Invalid → drop.

### 5.2 `ACK` (type `0x71`), ≤ 145 bytes

```
size field
1    payload_version   = 0x01
8    ack_id            random 64-bit, dedupe key
8    ref_msg_id        the NEED this acknowledges
8    target_node       = origin_node of that NEED (also the frame recipient_id)
4    ticket_no         u32
1    status            0 RECEIVED, 1 DISPATCHED, 2 ON_THE_WAY, 3 RESOLVED
2    eta_min           u16, 0xFFFF = unknown
1+n  text              ≤ 112 bytes, already in the requester's language
```

Max 33 + 112 = 145 bytes; frame max 22 + 8 + 145 = 175 bytes. (112 bytes fits about 37 Odia or Devanagari characters.)

The ACK frame's `sender_id` is the gateway that injected it into the mesh; `recipient_id` is
`target_node`.

### 5.3 `HELLO` (type `0x72`), 13 bytes

```
size field
1    payload_version   = 0x01
8    node_id           must equal the frame sender_id
1    caps              bit0 IS_GATEWAY
1    hops_to_gateway   0 if gateway, 0xFF if unknown, else min(neighbour value)+1 over neighbours below 0xFE (0xFE is the cap and is ignored, so a gateway-less loop decays to 0xFF)
2    app_version       u16
```

Sent first on every new link, again whenever `caps` or `hops_to_gateway` changes, and with every 30 s resync (§6.6).

### 5.4 `INVENTORY` (type `0x73`)

```
size field
1    payload_version   = 0x01
1    chunk_index       0-based
1    chunk_count       ≥ 1
1    id_count          0..19
8×n  ids               msg_id / ack_id values the sender currently stores
```

At most 19 ids per chunk, so a chunk frame is at most 22 + 4 + 152 = 178 bytes. A node
holding no packets sends one chunk with `chunk_count = 1, id_count = 0`.

### 5.5 `LOC_HINT` (type `0x74`), 31 bytes

A relay's statement "I heard this no-fix NEED directly from its requester, and this is where I am". The control
room intersects the hints for one NEED into an approximate position for a requester whose phone had no GPS fix
(§7, `geo` with `source: "mesh"`). A hint is evidence of proximity, not a fix: the requester is within radio range
of the relay, which is why the backend treats the relay's position, widened by 100 m, as a disc the requester is in.

```
size field
1    payload_version   = 0x01
8    ref_msg_id        the NEED this hint is about (a NEED with loc_acc = 0)
8    relay_node        node_id of the relay; must equal the frame sender_id
4    lat_e5            i32, the relay's own position, degrees × 100000, rounded half away from zero
4    lon_e5            i32
1    acc               the relay's own accuracy bucket: 1 ≤10 m, 2 ≤25 m, 3 ≤50 m, 4 ≤100 m
1    rssi_dbm          i8, signal strength of the relay's link to the requester, in dBm; -128 (0x80) = unknown
4    observed_at       unix seconds, u32: when the relay received the NEED from the requester
```

Frame: broadcast (no recipient), initial TTL 7, `sender_id` = the relay. The accuracy buckets are the hint's own
(they are not the NEED `loc_acc` buckets): a relay with worse than 100 m accuracy has no bucket and never emits.

**Dedupe key:** the first 8 bytes of `SHA-256(0x74 ‖ ref_msg_id ‖ relay_node)`. It is derived, not random, so a relay
can never put more than one hint for the same NEED into the mesh, whatever it forgets or re-sends.

**Validation on decode:** `payload_version = 1`, exactly 31 bytes, `acc` in 1..4, `|lat_e5| ≤ 9 000 000`,
`|lon_e5| ≤ 18 000 000`; anything else is dropped. A frame whose `relay_node` differs from its `sender_id` is dropped
(like `HELLO`). Node-level plausibility (for example a position of exactly 0, 0) is the consumer's job.

**When a relay emits a hint.** All of the following, and nothing else:

1. the user has "Help locate neighbours" on (§5.5.1);
2. it received the NEED, for the first time, with `loc_acc = 0`;
3. **directly from its origin**: the link the NEED arrived on had a `HELLO` whose `node_id` equals the NEED's
   `origin_node` (a NEED that merely names an origin, or reached the relay through other phones, never counts);
4. it has a position fix of its own taken within the last 120 s whose accuracy is ≤ 100 m.

The relay takes `rssi_dbm` and `observed_at` when the NEED arrives. If it has no good fix at that moment it may ask
the platform for one and emit within 30 s of the NEED, otherwise it gives up for that NEED. Gossip then treats the hint
like an `ACK` (§6): stored, listed in `INVENTORY`, flooded with TTL 7, expired after 72 h from first storage, and a
gateway uploads it with the NEEDs (§7).

**Upgrade consequence:** a build that predates `LOC_HINT` relays it once as an unknown type (§4) but does not
store it, list it in `INVENTORY` or upload it, so a hint only reaches the control room over a path whose gateway
understands it.

#### 5.5.1 Privacy and the setting

A hint publishes the relay user's position (to metre precision) to every phone that carries it and to the control
room. It is therefore **opt-in per phone**: Settings has a switch "Help locate neighbours". Builds choose its
default: **on in demo builds, off in village builds** (Gradle property `relay.helpLocateDefault`, default `true`). A phone with the switch off
never emits a hint, though it still relays other phones' hints like any other frame.

### 5.6 `BROADCAST` (type `0x75`), 25 bytes

The control room speaking to every phone. Today it carries one thing: **a disaster declaration** ("flood", "earthquake") or
the end of one. Phones use it to decide which disaster-specific follow-up questions they may ask a person in need;
a phone never infers a disaster from what a person says, so without a live declaration it asks
none.

```
size field
1    payload_version   = 0x01
8    bcast_id          random, unique per broadcast; the dedupe key
1    kind              1 DISASTER (declared), 2 DISASTER_CLEARED (over)
1    disaster          0 flood, 1 cyclone, 2 earthquake, 3 landslide
4    issued_at         unix seconds, u32: when the control room issued it
4    lat_e5            i32, centre of the area, degrees × 100000 (0 when radius_km = 0)
4    lon_e5            i32
2    radius_km         u16; 0 = the whole control-room area, otherwise the declaration applies within this distance of the centre
```

Frame: broadcast (no recipient), initial TTL 7, `sender_id` = the gateway that put it into the mesh (any node id; it is not
checked against the payload).

**Life: 24 hours from `issued_at`** (not from when a phone received it, so a phone that meets a carrier late holds it for
what is left, and nothing circulates for ever). A node drops a `BROADCAST` that is more than 24 h old, or whose
`issued_at` is more than 10 minutes in the future, without storing or relaying it; it deletes a stored one at
`issued_at + 24 h`. This differs from the 72 h of the other stored types.

**Delivery, end to end.**

1. An operator declares a disaster at the backend (§7 `POST /v1/broadcasts`); the backend stores the payload.
2. **Every `POST /v1/sync` response carries every live broadcast** in `broadcasts` (base64 payloads), whichever
   gateway asks. A gateway originates each one it does not already hold into the mesh (same rule as originating an `ACK`);
   originating one already held is a no-op, so repeating the list on every sync is free. Every gateway therefore catches it,
   whenever it comes online.
3. Phones store it, list it in `INVENTORY`, flood it with TTL 7 and push it to any peer that lacks it (§6), so phones
   that never reach a gateway still **carry** it from one encounter to the next until it expires. It is pushed ahead of
   other ACKs and NEEDs: it is about 50 bytes on the wire.
4. A phone that holds a live `DISASTER` of type T (and, when `radius_km > 0`, knows it is inside the area) treats T as
   declared. A phone that does not know where it is treats an area-limited declaration as applying to it. A `DISASTER_CLEARED`
   for T ends every declaration of T issued at or before its `issued_at`. Between 12 h and 24 h after issue a
   declaration still counts but is marked *aging*, so the phone confirms with the person before relying on it.

**Validation on decode:** `payload_version = 1`, exactly 25 bytes, `kind` in {1, 2}, `disaster` in 0..3, `|lat_e5| ≤ 9 000 000`,
`|lon_e5| ≤ 18 000 000`; anything else is dropped.

**Upgrade consequence:** a build that predates `BROADCAST` relays it once as an unknown type (§4) but does not store it
or act on it; the people it serves simply get the questions they got before.

**Authenticity (known gap).** A broadcast is not signed in v1. A forged one can only switch extra questions on or off; it
cannot remove a question that is always asked (consciousness, breathing, bleeding) or any first-aid advice. Signing it
with a control-room key is the planned hardening.

---

## 6. Gossip engine (behaviour every node implements)

State per node: `node_id` (random 8 bytes per install), a **store** of frames keyed by dedupe
key, a **seen-set** (bounded LRU of 10,000 keys, includes everything ever stored), and a set of
live links.

1. **Link up:** send `HELLO`, then the full `INVENTORY` (all chunks).
2. **On `INVENTORY` from link L:** accumulate chunks until all `chunk_count` arrived (a new
   `chunk_index = 0` restarts accumulation). Then, for every stored NEED/ACK whose key is not
   in the peer's set, send its stored frame to L only, **with `timestamp_ms` set to now** and
   TTL unchanged. (Re-stamping keeps bitchat iPhones' 120 s skew guard happy in bridge mode.)
3. **On `NEED`/`ACK` from link L:**
   - If its key is in the seen-set: drop.
   - Else: add to seen-set, store the frame (with TTL as received), deliver it to the app
     layer, and if `ttl > 1`, forward a copy with `ttl − 1` to every link except L after a
     random jitter of 10–220 ms.
4. **Origination:** a locally created NEED or ACK goes through step 3 as if received from no
   link (stored, delivered, flooded to all links with the initial TTL; no decrement).
5. **Expiry:** NEED and ACK frames older than 72 h (by `created_at` for NEED, by first-stored
   time for ACK) are deleted from the store; their keys stay in the seen-set until LRU
   eviction.
6. **Periodic resync:** every 30 s each node re-sends its full `INVENTORY` on every live link.
   Flooding only reaches TTL hops (7); frames stored at `ttl ≤ 1` advance one more hop per
   resync round, and lost frames are repaired. Without this, delivery stalls at the TTL radius
   (verified in the simulator: a 10-node chain delivers 0/3 by flooding alone, 3/3 after 4
   rounds).
7. **No clock-skew rejection** between Relay nodes. A frame whose `timestamp_ms` is more than
   24 h in the future is dropped.

ACK delivery: every node stores and relays ACKs; only the node whose `node_id == target_node`
shows it to the user.

`LOC_HINT` frames (§5.5) are stored, inventoried, flooded and expired exactly like ACKs, except that no node
shows them to the user; the gateway uploads them (§7). `BROADCAST` frames (§5.6) are stored, inventoried and flooded
like ACKs but expire 24 h after `issued_at`, are pushed to a peer before other ACKs and NEEDs, and are never uploaded: they
only travel down from the backend. A node that is not the NEED's requester never needs the
hint's content for anything but forwarding it.

---

## 7. Gateway ↔ backend API

Base URL is configurable. All bodies are JSON. Binary values are standard base64 (with
padding). Node ids in JSON are 16-char lowercase hex.

### `POST /v1/sync`

Header `X-Relay-Key: <device key>` (dev default `dev-relay-key`).

Request:
```json
{ "gateway_node": "a1b2c3d4e5f60718", "frames": ["<base64 NEED frame>", "..."], "ack_cursor": 0 }
```
Response:
```json
{ "accepted": 3, "duplicates": 1, "rejected": 0, "acks": ["<base64 ACK payload>", "..."], "ack_cursor": 17 }
```
- `frames` are full outer frames as received over BLE (so the backend sees TTL and computes
  `hops = 7 − ttl`). A frame that is neither a NEED nor a `LOC_HINT`, or that does not decode, counts as `rejected`.
- Uploads are idempotent on `msg_id` (NEED) and on the hint's dedupe key (`LOC_HINT`).
- `LOC_HINT` frames (§5.5) are counted apart: when the upload holds at least one, the response also carries
  `"hints": {"accepted": n, "duplicates": n, "rejected": n}` (absent otherwise, so older gateways see no change). A
  hint is `rejected` when it does not decode, when its `relay_node` is not the frame's `sender_id`, when its position is
  exactly 0, 0, or when its `observed_at` is more than 24 h in the future or older than 72 h. A hint for a NEED the
  backend has not seen yet is kept (`accepted`) and used when that NEED arrives.
- `broadcasts` (optional, omitted when empty so older gateways see no change) are the **BROADCAST payloads** (§5.6) that are
  live now (issued within the last 24 h), oldest first, in every response whichever gateway asks. The gateway decodes
  each and originates the ones it does not hold into the mesh.
- `acks` are **ACK payloads** (not frames) with id greater than `ack_cursor`, oldest first,
  at most 50. The gateway wraps each in a frame (`sender_id` = gateway, `recipient_id` =
  `target_node`, TTL 7) and originates it into the mesh.

### Dashboard endpoints (no auth in v1)
- `GET /v1/tickets` → `{ "tickets": [Ticket...] }`, ordered by `priority_score` desc.
- `GET /v1/tickets/{ticket_no}` → `Ticket` with `packets: [...]`.
- `POST /v1/tickets/{ticket_no}/approve` → queues a `DISPATCHED` ACK for every linked NEED's
  origin, returns the updated `Ticket`.
- `POST /v1/tickets/{ticket_no}/locate` → estimates the position of a ticket with no GPS fix from its landmark now
  (gazetteer, then the Maps geocoder), stores it as `geo` and returns the updated `Ticket`. `404` unknown ticket; `409` the
  ticket has a GPS fix or a `mesh` `geo` (never replaced); `503` no Maps geocoder is configured and the gazetteer has no
  match; `422` the landmark is empty or nothing confident was found.
- `POST /v1/broadcasts` → body `{ "kind": "disaster" | "cleared" (default "disaster"), "disaster": "flood" | "cyclone" | "earthquake" | "landslide",
  "lat", "lon", "radius_km" }` (area optional; `radius_km > 0` needs `lat` and `lon`) → `{ "id", "kind", "disaster", "issued_at",
  "expires_at", "area"? }`. Stored and served to gateways for 24 h (§5.6).
- `GET /v1/broadcasts` → `{ "broadcasts": [ ...the same objects, live only ] }`.
- `GET /v1/stats` → `{ "open_by_category": {...}, "gateways_online": n, "tickets": n }`
  (a gateway is online if it synced in the last 60 s).
- `POST /v1/dev/inject` → body `{ "needs": [NeedJson...] }`; dev only.
- `GET /healthz` → `{ "ok": true }`.

`Ticket` JSON:
```json
{
  "ticket_no": 142, "category": "MEDICAL", "item": "insulin", "landmark": "school",
  "summary": "mother needs insulin, diabetic", "lat": 20.29612, "lon": 85.82451,
  "has_location": true, "loc_acc": 1, "loc_acc_m": "<10 m",
  "urgency": 3, "people": 1, "vuln_flags": ["ELDERLY"], "lang": "or",
  "report_count": 1, "priority_score": 8.4, "priority_reason": "...",
  "status": "OPEN", "dispatch_draft": {"en": "...", "local": "..."},
  "first_reported_at": 1790000000, "max_hops": 2, "gateways": ["a1b2c3d4e5f60718"],
  "msg_ids": ["0011223344556677"],
  "answers": {"conscious": "no", "road_reachable": "yes"},
  "photo_tags": {"floodwater": 2, "people_stranded": 1}
}
```
`answers` (optional, omitted when the ticket has none) maps question key to answer key (§5.1 table; `unknown` is a
real answer). For a merged ticket it holds the first report's answers, and a later report fills only keys the ticket
does not have yet.
`photo_tags` (optional, omitted when empty) maps a tag key (§5.1 table) to the number of linked reports that carried
it, so `{"floodwater": 2}` on a 3-report ticket means two phones saw floodwater. These are observations from the
requesters' phones, not verified facts, and they never change `priority_score` or `priority_tier`; the only effect on
priority is a note appended to `priority_reason`, e.g. "Photo shows: floodwater, people stranded." The detail view's
`packets` entries carry `photo_tags` as a list of keys when that report had any.
`status` is `OPEN` or `DISPATCHED`.

**`geo`** (optional, only on a ticket with `has_location = false`) is an approximate position, never a fix, and never
set on a ticket that has one: `{ "lat", "lon", "source", ... }`, where `source` is one of
- `"gazetteer"`: looked up from the free-text landmark in the curated Puri gazetteer; also carries `query` (the landmark);
- `"gemini_maps"`: found by Gemini with Google Maps grounding from the landmark; carries `query`, `name` (the Google
  Maps place chosen), `district` (id of the district whose bounding box holds the point: `puri` or `delhi`, as in the
  dashboard) and, when Google returned them, `place_id` and `maps_uri` (an `https` link to the place on Google Maps).
  Only an answer backed by a Google Maps grounding chunk and inside the ticket's district is stored. A dashboard that
  shows a `gemini_maps` position must credit Google Maps (grounding terms) and may link `maps_uri`;
- `"mesh"`: estimated from `LOC_HINT` frames (§5.5); also carries `radius_m` (u32, metres, a multiple of 10) and
  `hint_count` (the hints that agreed and were used). A mesh estimate replaces a landmark one; a landmark lookup never
  replaces a mesh one.

The mesh estimate treats each hint as a disc centred on the relay with radius `accuracy upper bound + 100 m` (the
requester was within radio range of the relay, which is generously 100 m) and takes the **intersection of the discs**.
The backend uses the largest group of hints whose discs share a point (ties: the more accurate group, at most 12
hints, most accurate first), so one forged or wrong hint cannot shut out several that agree; a hint left out is not
counted in `hint_count`. The point is the centre of the intersection, nudged by at most a factor of two towards relays
with a stronger link (`rssi_dbm` is a weak weight, not a distance), and `radius_m` is the radius of the circle around
that point that covers the whole intersection, rounded up to 10 m. With one hint it is the relay's position and
`bucket bound + 100` m.

`has_location`, `loc_acc` and `lat`/`lon` stay as described above: a ticket with only a mesh estimate still counts
as "without location" in `GET /v1/stats` consumers and the dashboard.

Location quality is derived from the ticket's linked NEED packets (§5.1 `loc_acc`):
`loc_acc` is the best (lowest non-zero) bucket among them, `0` only if no packet had a fix;
`loc_acc_m` is its label (`"<10 m"`, `"<50 m"`, `"<200 m"`, `"<1 km"`, `"≥1 km"`, or `null` for 0);
`has_location` is `loc_acc != 0`. When `has_location` is false, `lat` and `lon` are `null` (the
phone's 0,0 is a placeholder, not a place) and clients must not plot the ticket; the dispatch
draft then reads "Location: unknown (no GPS fix)". Each entry of the detail view's `packets`
also carries its own `loc_acc`.

---

## 8. Golden vectors

`protocol/vectors.json` holds hex encodings produced by `protocol/gen_vectors.py` (the
reference encoder). Both codecs must (a) encode each vector's fields to exactly the given hex
and (b) decode the hex back to the same fields. The v1 vectors are byte-identical to the originals; the `need_v2_*`
vectors cover the answer bytes, the `need_v3_*` vectors cover the photo tags (fields carry `photo_tags` and `photo_tags_mask`;
`photo_tag_bank` is the bit table both codecs must match), and the `loc_hint_*` vectors cover `LOC_HINT` (fields include its dedupe `key`).
`must_reject_need_payloads` and `must_reject_loc_hint_payloads` list payloads both decoders must refuse,
`loc_hint_acc_max_m` is the accuracy-bucket table, and `answer_bank` is the question table both codecs must match. Regenerate with
`python3 protocol/gen_vectors.py > protocol/vectors.json`.
