"""Structured-JSON Gemini client for the control-room writers (dispatch orders, sitreps).

``agent.GenaiClient`` is bound to the triage output schema, so these writers get their own
thin client with the same two modes (API key, or Vertex AI via ADC) and the same rules:
built lazily, hard timeout, and any failure surfaces as an exception that the caller turns
into its deterministic fallback. Tests inject a fake with the same ``generate_json`` shape.
"""

from __future__ import annotations

import concurrent.futures
import json
import logging
import os
from typing import Any, Protocol

from pydantic import BaseModel

from .agent import GenaiClient

log = logging.getLogger("relay.textgen")

DEFAULT_MODEL = "gemini-flash-latest"
_POOL = concurrent.futures.ThreadPoolExecutor(max_workers=8, thread_name_prefix="textgen")


class TextGen(Protocol):
    def generate_json(self, prompt: str, schema: type[BaseModel]) -> str: ...


class SchemaGenaiClient(GenaiClient):
    """``GenaiClient`` with the response schema chosen per call."""

    def generate_json(self, prompt: str, schema: type[BaseModel]) -> str:  # type: ignore[override]
        resp = self._get().models.generate_content(
            model=self._model,
            contents=prompt,
            config=self._types.GenerateContentConfig(
                response_mime_type="application/json", response_schema=schema, temperature=0.2
            ),
        )
        return resp.text or ""


def textgen_from_env(model_var: str = "GEMINI_MODEL", env: dict[str, str] | None = None) -> TextGen | None:
    """Same switch as ``agent_from_env`` (Vertex wins over an API key); ``None`` = no model.
    The model name comes from ``model_var`` (falling back to ``GEMINI_MODEL``, then Flash)."""
    env = os.environ if env is None else env
    model = env.get(model_var) or env.get("GEMINI_MODEL") or DEFAULT_MODEL
    use_vertex = env.get("GOOGLE_GENAI_USE_VERTEXAI", "").strip().lower() in ("true", "1")
    key = env.get("GEMINI_API_KEY")
    try:
        if use_vertex:
            return SchemaGenaiClient(
                model=model, timeout_s=15.0, vertexai=True,
                project=env.get("GOOGLE_CLOUD_PROJECT") or None,
                location=env.get("GOOGLE_CLOUD_LOCATION") or "global",
            )
        if key:
            return SchemaGenaiClient(key, model, timeout_s=15.0)
    except Exception as e:
        log.error("Gemini text client init failed (%s); using templates", e)
    return None


def call_json(client: TextGen, prompt: str, schema: type[BaseModel], timeout_s: float = 15.0) -> dict[str, Any]:
    """One model call with a hard wall-clock timeout; returns the parsed JSON object or raises."""
    fut = _POOL.submit(client.generate_json, prompt, schema)
    try:
        raw = fut.result(timeout=timeout_s)
    except concurrent.futures.TimeoutError as e:
        fut.cancel()
        raise TimeoutError(f"model call exceeded {timeout_s}s") from e
    data = json.loads(raw)
    if not isinstance(data, dict):
        raise ValueError("model output is not a JSON object")
    return data
