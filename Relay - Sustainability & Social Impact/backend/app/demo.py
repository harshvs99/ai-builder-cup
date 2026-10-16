"""Play the gateway phone in the demo scenario against a running backend.

    python -m app.demo --url http://localhost:8080 [--approve]

1. Builds the NEED for "my mother needs insulin, near the school" (Odia speaker).
2. Uploads it through /v1/sync, as the online phone would after two mesh hops.
3. Decodes the ACK that would hop back to the phone that asked.
4. With --approve, plays the operator approving the ticket, syncs again and shows
   the DISPATCHED confirmation.
"""

from __future__ import annotations

import argparse
import base64
import secrets
import sys
import time

import httpx

from .protocol import AckPacket, Category, Flag, Lang, NeedPacket, decode_ack, encode_need, node_hex

GATEWAY_NODE = "d0d0d0d0"


def show_acks(acks_b64: list[str], origin: int) -> None:
    for item in acks_b64:
        ack: AckPacket = decode_ack(base64.b64decode(item))
        mine = "  ← for our phone" if ack.target_node == origin else ""
        eta = f", ETA {ack.eta_min} min" if ack.eta_min else ""
        print(f"  ACK ticket #{ack.ticket_no} {ack.status.name}{eta}: {ack.text}{mine}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--url", default="http://localhost:8080")
    parser.add_argument("--device-key", default=None, help="value of RELAY_DEVICE_KEY, if set")
    parser.add_argument("--operator-key", default=None, help="value of RELAY_OPERATOR_KEY, if set")
    parser.add_argument("--approve", action="store_true", help="also approve the ticket and fetch the confirmation")
    args = parser.parse_args()

    origin = secrets.randbits(32)
    need = NeedPacket(
        msg_id=secrets.token_bytes(8),
        origin_node=origin,
        created_at=int(time.time()),
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
        summary="mother needs insulin, diabetic",
    )
    raw = encode_need(need)
    print(f"Phone {node_hex(origin)} encoded NEED {need.msg_id_hex}: {len(raw)} bytes")

    device_headers = {"X-Relay-Key": args.device_key} if args.device_key else {}
    operator_headers = {"X-Operator-Key": args.operator_key} if args.operator_key else {}
    with httpx.Client(base_url=args.url, timeout=30) as client:
        resp = client.post(
            "/v1/sync",
            headers=device_headers,
            json={"gateway_node": GATEWAY_NODE, "packets": [{"data": base64.b64encode(raw).decode(), "hops": 2}]},
        )
        resp.raise_for_status()
        result = resp.json()
        print(f"Gateway {GATEWAY_NODE} synced: created={result['created']} merged={result['merged']}")
        show_acks(result["acks"], origin)
        cursor = result["ack_cursor"]

        tickets = client.get("/v1/tickets", headers=operator_headers).json()["tickets"]
        ticket = next(t for t in tickets if any(r["msg_id"] == need.msg_id_hex for r in t["reports"]))
        print(f"\nControl room: ticket #{ticket['id']} tier P{ticket['tier']} score {ticket['score']}")
        print(f"  reason: {ticket['priority_reason']}")
        print("  dispatch draft:\n    " + ticket["dispatch_draft"]["en"].replace("\n", "\n    "))

        if args.approve:
            client.post(
                f"/v1/tickets/{ticket['id']}/approve",
                headers=operator_headers,
                json={"eta_min": 45, "note": "Team leaving from Puri block office."},
            ).raise_for_status()
            resp = client.post(
                "/v1/sync", headers=device_headers, json={"gateway_node": GATEWAY_NODE, "ack_cursor": cursor}
            )
            resp.raise_for_status()
            print("\nAfter the operator approved:")
            show_acks(resp.json()["acks"], origin)
    return 0


if __name__ == "__main__":
    sys.exit(main())
