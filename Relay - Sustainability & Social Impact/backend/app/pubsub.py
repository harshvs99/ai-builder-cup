"""Pub/Sub glue: the publish side of /v1/sync and the push-envelope side of /internal/triage.

Message contract (infra/README.md, "Backend contract"):
  data = UTF-8 JSON  {"gateway_node": "<id as sent>", "frames": ["<base64 outer frame>", ...]}
  no attributes.
Push envelope:
  {"message": {"data": "<base64 of the JSON above>", "messageId": ..., "publishTime": ...},
   "subscription": ..., "deliveryAttempt": n}
"""

from __future__ import annotations

import base64
import binascii
import json
import re
from typing import Any, Callable, Protocol

# Pub/Sub's limit is 10 MB per message; stay well under it. A normal batch (<= 500 frames of a
# few hundred bytes) is a single message; only an absurd batch is split.
MAX_MESSAGE_BYTES = 5 * 1024 * 1024
MAX_FRAMES_PER_MESSAGE = 5000
PUBLISH_TIMEOUT_S = 15.0
_HEX16 = re.compile(r"^[0-9a-fA-F]{16}$")

# token -> verified claims; raises ValueError for an invalid token, TransportError if the
# signing certs could not be fetched
TokenVerifier = Callable[[str], dict[str, Any]]


class Publisher(Protocol):
    def publish(self, topic: str, data: bytes) -> None:
        """Publish and block until the broker has accepted the message (raises on failure)."""
        ...


class PubSubPublisher:
    """``google.cloud.pubsub_v1.PublisherClient`` created on first use (ADC credentials)."""

    def __init__(self, timeout_s: float = PUBLISH_TIMEOUT_S):
        self._timeout_s = timeout_s
        self._client: Any = None

    def publish(self, topic: str, data: bytes) -> None:
        if self._client is None:
            from google.cloud import pubsub_v1

            self._client = pubsub_v1.PublisherClient()
        self._client.publish(topic, data).result(timeout=self._timeout_s)


def build_messages(gateway_node: str, frames: list[str]) -> list[bytes]:
    """One message per accepted batch; split only if it would exceed MAX_MESSAGE_BYTES."""
    messages: list[bytes] = []
    chunk: list[str] = []
    size = 0

    def flush() -> None:
        nonlocal chunk, size
        if chunk:
            messages.append(
                json.dumps({"gateway_node": gateway_node, "frames": chunk}, separators=(",", ":")).encode("utf-8")
            )
            chunk, size = [], 0

    for f in frames:
        if chunk and (size + len(f) + 4 > MAX_MESSAGE_BYTES or len(chunk) >= MAX_FRAMES_PER_MESSAGE):
            flush()
        chunk.append(f)
        size += len(f) + 4
    flush()
    return messages


def parse_push_envelope(raw: bytes) -> tuple[str, list[str]]:
    """Envelope bytes -> (gateway_node, frames). Raises ValueError with a short reason."""
    try:
        env = json.loads(raw)
        data_b64 = env["message"]["data"]
        payload = json.loads(base64.b64decode(data_b64, validate=True))
    except (ValueError, KeyError, TypeError, binascii.Error) as e:
        raise ValueError(f"malformed push envelope: {type(e).__name__}") from e
    if not isinstance(payload, dict):
        raise ValueError("message data is not a JSON object")
    gateway = payload.get("gateway_node")
    frames = payload.get("frames")
    if not isinstance(gateway, str) or not _HEX16.match(gateway):
        raise ValueError("gateway_node missing or not 16 hex chars")
    if not isinstance(frames, list) or not all(isinstance(f, str) for f in frames):
        raise ValueError("frames must be a list of base64 strings")
    if len(frames) > MAX_FRAMES_PER_MESSAGE:
        raise ValueError("too many frames")
    return gateway, frames


def google_token_verifier(audience: str) -> TokenVerifier:
    """Verify a Google-signed OIDC JWT for ``audience`` (signature, exp, aud, iss)."""
    from google.auth.transport import requests as ga_requests
    from google.oauth2 import id_token

    request = ga_requests.Request()

    def verify(token: str) -> dict[str, Any]:
        return id_token.verify_oauth2_token(token, request, audience=audience)

    return verify
