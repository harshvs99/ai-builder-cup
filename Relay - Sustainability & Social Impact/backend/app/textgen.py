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

DEFAULT_MODEL = "gemini-3.8-flash"
# The sitrep is the one long, once-an-hour document: worth a Pro-tier model. A Google-maintained
# alias, so a retired Pro version never strands the deploy; Flash is the fallback (see sitrep_textgen_from_env).
SITREP_DEFAULT_MODEL = "gemini-pro-latest"
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


class FallbackTextGen:
    """Tries each client in order; the first answer wins. Only a failed call (error, quota, unknown
    model) moves on: an answer the caller later rejects does not. Callers still bound the whole
    chain with ``call_json``'s timeout."""

    def __init__(self, clients: list[tuple[str, TextGen]]):
        self.clients = clients

    def generate_json(self, prompt: str, schema: type[BaseModel]) -> str:
        last: Exception | None = None
        for name, client in self.clients:
            try:
                return client.generate_json(prompt, schema)
            except Exception as e:
                log.warning("model %s failed (%s: %s); trying the next one", name, type(e).__name__, e)
                last = e
        raise last or RuntimeError("no model configured")


def textgen_from_env(
    model_var: str = "GEMINI_MODEL", env: dict[str, str] | None = None, default_model: str | None = None,
    timeout_s: float = 15.0,
) -> TextGen | None:
    """Same switch as ``agent_from_env`` (Vertex wins over an API key); ``None`` = no model.
    The model name comes from ``model_var``, else ``default_model``, else ``GEMINI_MODEL``, else Flash;
    ``timeout_s`` is the client's own request timeout (longer writers, e.g. a Pro-tier report, raise it)."""
    env = os.environ if env is None else env
    model = env.get(model_var) or default_model or env.get("GEMINI_MODEL") or DEFAULT_MODEL
    use_vertex = env.get("GOOGLE_GENAI_USE_VERTEXAI", "").strip().lower() in ("true", "1")
    key = env.get("GEMINI_API_KEY")
    try:
        if use_vertex:
            return SchemaGenaiClient(
                model=model, timeout_s=timeout_s, vertexai=True,
                project=env.get("GOOGLE_CLOUD_PROJECT") or None,
                location=env.get("GOOGLE_CLOUD_LOCATION") or "global",
            )
        if key:
            return SchemaGenaiClient(key, model, timeout_s=timeout_s)
    except Exception as e:
        log.error("Gemini text client init failed (%s); using templates", e)
    return None


def sitrep_textgen_from_env(env: dict[str, str] | None = None) -> TextGen | None:
    """``SITREP_GEMINI_MODEL`` (default: the Pro alias), falling back to ``GEMINI_MODEL`` (Flash) when
    the Pro call fails, so a Pro quota or availability problem costs prose quality, not the report."""
    env = os.environ if env is None else env
    pro = textgen_from_env("SITREP_GEMINI_MODEL", env, default_model=SITREP_DEFAULT_MODEL, timeout_s=60.0)
    if pro is None:
        return None
    pro_name = env.get("SITREP_GEMINI_MODEL") or SITREP_DEFAULT_MODEL
    flash_name = env.get("GEMINI_MODEL") or DEFAULT_MODEL
    if flash_name == pro_name:
        return pro
    flash = textgen_from_env("GEMINI_MODEL", env, timeout_s=20.0)
    return FallbackTextGen([(pro_name, pro)] + ([(flash_name, flash)] if flash else []))


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
