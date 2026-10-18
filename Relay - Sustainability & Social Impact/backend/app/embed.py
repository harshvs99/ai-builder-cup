"""Item embeddings for near-duplicate clustering ("insulin" ~ "इंसुलिन" ~ "ଇନସୁଲିନ").

Embeddings replace ONLY the exact item-text test in ``cluster``; the hard gates (same category,
<= 200 m, <= 6 h, no-fix never merges) are untouched.

Design (phase 1 must never wait on a model):
  * the incoming need's vector is computed by the pipeline BEFORE the store transaction, with a hard
    timeout; any failure gives ``None`` and the merge falls back to the plain rule;
  * each ticket stores its canonical item vector (``item_vec``: unit-length float16, base64), so
    inside the transaction the comparison is a local dot product against candidate tickets;
  * a ticket without a stored vector (created before this feature, or while the embedder was down)
    is compared by the rule alone.

Embedded text is ``f"{category}: {item}"`` (see ``item_text``): the item is the canonical short noun;
the free-text summary is longer, mixes in urgency and people counts, and would blur "same need" into
"same situation". The category prefix keeps e.g. a FOOD "water" away from a MEDICAL "saline".

``SIMILARITY_THRESHOLD`` is a starting guess. It MUST be tuned on the demo night with real
multilingual pairs (en/hi/or/tl/war/ceb): too low merges different needs, too high misses
translations. Override with ``RELAY_EMBED_THRESHOLD`` without a code change.
"""

from __future__ import annotations

import base64
import concurrent.futures
import logging
import math
import os
import re
import struct
import threading
from collections import OrderedDict
from typing import Any, Protocol

log = logging.getLogger("relay.embed")

DEFAULT_MODEL = "gemini-embedding-001"
DIMENSIONS = 256
SIMILARITY_THRESHOLD = 0.80
EMBED_TIMEOUT_S = 3.0
CACHE_SIZE = 2048
_POOL = concurrent.futures.ThreadPoolExecutor(max_workers=4, thread_name_prefix="embed")


class Embedder(Protocol):
    def embed(self, texts: list[str]) -> list[list[float]]: ...


def norm_text(s: str) -> str:
    return re.sub(r"\s+", " ", s.strip()).casefold()


def item_text(category: str, item: str) -> str:
    return f"{category}: {item.strip()}"


def cosine(a: list[float], b: list[float]) -> float:
    """Cosine similarity; 0.0 for empty, mismatched or zero vectors."""
    if not a or len(a) != len(b):
        return 0.0
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    return dot / (na * nb) if na and nb else 0.0


def threshold_from_env(env: dict[str, str] | None = None) -> float:
    env = os.environ if env is None else env
    try:
        v = float(env.get("RELAY_EMBED_THRESHOLD", ""))
    except ValueError:
        return SIMILARITY_THRESHOLD
    return v if 0.0 < v <= 1.0 else SIMILARITY_THRESHOLD


def encode_vec(v: list[float]) -> str:
    """Unit-normalise and pack as base64 little-endian float16 (256 dims -> 684 chars). '' if unusable."""
    n = math.sqrt(sum(x * x for x in v))
    if not v or not n or not math.isfinite(n):
        return ""
    return base64.b64encode(struct.pack(f"<{len(v)}e", *(x / n for x in v))).decode("ascii")


def decode_vec(s: str | None) -> list[float] | None:
    if not s:
        return None
    try:
        raw = base64.b64decode(s)
        return list(struct.unpack(f"<{len(raw) // 2}e", raw))
    except Exception:
        return None


class GenaiEmbedder:
    """google-genai adapter (API key, or Vertex via ADC). The client is built lazily, so constructing
    this needs no credentials or network. Results are cached per normalised text."""

    def __init__(
        self, api_key: str | None = None, model: str = DEFAULT_MODEL, timeout_s: float = EMBED_TIMEOUT_S, *,
        vertexai: bool = False, project: str | None = None, location: str | None = None, cache_size: int = CACHE_SIZE,
    ):
        self._model = model
        self._timeout_s = timeout_s
        self._kwargs: dict[str, Any] = (
            {"vertexai": True, "project": project, "location": location or "global"}
            if vertexai else {"api_key": api_key}
        )
        self._client: Any = None
        self._lock = threading.Lock()
        self._cache: OrderedDict[str, list[float]] = OrderedDict()
        self._cache_size = cache_size

    def _get(self):
        if self._client is None:
            with self._lock:
                if self._client is None:
                    from google import genai
                    from google.genai import types

                    self._client = genai.Client(
                        http_options=types.HttpOptions(timeout=int(self._timeout_s * 1000)), **self._kwargs
                    )
        return self._client

    def embed(self, texts: list[str]) -> list[list[float]]:
        keys = [norm_text(t) for t in texts]
        with self._lock:
            missing = list(dict.fromkeys(k for k in keys if k not in self._cache))
            for k in keys:
                if k in self._cache:
                    self._cache.move_to_end(k)
        fresh: dict[str, list[float]] = {}
        if missing:
            from google.genai import types

            resp = self._get().models.embed_content(
                model=self._model, contents=missing,
                config=types.EmbedContentConfig(task_type="SEMANTIC_SIMILARITY", output_dimensionality=DIMENSIONS),
            )
            vecs = [list(e.values) for e in resp.embeddings]
            if len(vecs) != len(missing):
                raise ValueError("embedding count mismatch")
            fresh = dict(zip(missing, vecs))
            with self._lock:
                self._cache.update(fresh)
                while len(self._cache) > self._cache_size:
                    self._cache.popitem(last=False)
        with self._lock:
            return [fresh[k] if k in fresh else self._cache[k] for k in keys]


def embedder_from_env(env: dict[str, str] | None = None) -> Embedder | None:
    """None (rules only) when ``RELAY_EMBED_MERGE`` is "off" or there are no Gemini credentials.
    Same switch as the agent: Vertex wins over an API key."""
    env = os.environ if env is None else env
    if env.get("RELAY_EMBED_MERGE", "").strip().lower() in ("off", "0", "false", "no"):
        return None
    model = env.get("RELAY_EMBED_MODEL") or DEFAULT_MODEL
    use_vertex = env.get("GOOGLE_GENAI_USE_VERTEXAI", "").strip().lower() in ("true", "1")
    key = env.get("GEMINI_API_KEY")
    try:
        if use_vertex:
            return GenaiEmbedder(
                model=model, vertexai=True, project=env.get("GOOGLE_CLOUD_PROJECT") or None,
                location=env.get("GOOGLE_CLOUD_LOCATION") or "global",
            )
        if key:
            return GenaiEmbedder(key, model)
    except Exception as e:
        log.error("embedding client init failed (%s); merging by exact item only", e)
    return None


def embed_item(
    embedder: Embedder | None, category: str, item: str, timeout_s: float | None = None
) -> list[float] | None:
    """Unit vector of one item as stored (float16-rounded), or None (no embedder, empty item, timeout,
    any error). Never raises."""
    if embedder is None or not item.strip():
        return None
    fut = _POOL.submit(embedder.embed, [item_text(category, item)])
    try:
        enc = encode_vec(fut.result(timeout=EMBED_TIMEOUT_S if timeout_s is None else timeout_s)[0])
        return decode_vec(enc) if enc else None
    except Exception as e:  # includes TimeoutError
        fut.cancel()
        log.warning("item embedding failed (%s); using exact item match", type(e).__name__)
        return None
