"""Optional Gemini text-to-speech for the Ready plan. Off unless ``RELAY_TTS_MODEL`` is set.

The plan is read aloud on the phone. Android already has on-device TextToSpeech (``Speaker.kt``),
which works with no signal at all, so server audio is a nicety: a better voice for the languages
Gemini speaks, fetched while the phone still has signal and cached next to the plan.

``Speech`` is the seam tests fake. ``GenaiSpeech`` calls ``generate_content`` with
``response_modalities=["AUDIO"]`` and a prebuilt voice; Gemini returns raw 16-bit PCM, mono, 24 kHz in
``inline_data`` and ``pcm_to_wav`` wraps it in a WAV header so any player can open it.

Language support (as of writing; check the model card before a pilot): Hindi and English are
supported; Odia (or-IN) and Filipino are preview languages; Waray is most likely unsupported. A language
outside ``TTS_LANGS`` gets no ``audio_url`` and the phone falls back to its own voice. Everything here is
defensive: the client is built lazily (importing the app needs no credentials or network), every call has a
hard timeout, and any failure is an exception the endpoint turns into "no audio, use device TTS".
"""

from __future__ import annotations

import concurrent.futures
import logging
import os
import struct
import threading
from typing import Any, Protocol

log = logging.getLogger("relay.tts")

PCM_RATE = 24_000
PCM_CHANNELS = 1
PCM_WIDTH = 2  # bytes per sample (16-bit)
DEFAULT_VOICE = "Kore"
TTS_TIMEOUT_S = 40.0
TTS_LANGS = frozenset({"en", "hi", "or", "tl"})  # Waray (war) and Cebuano (ceb): device TTS only
MAX_TEXT_CHARS = 2000
_POOL = concurrent.futures.ThreadPoolExecutor(max_workers=2, thread_name_prefix="tts")


class Speech(Protocol):
    def supports(self, lang: str) -> bool: ...

    def synthesize(self, text: str, lang: str) -> bytes:
        """WAV bytes (RIFF, PCM16 mono). Raises on any failure."""
        ...


def pcm_to_wav(pcm: bytes, rate: int = PCM_RATE, channels: int = PCM_CHANNELS, width: int = PCM_WIDTH) -> bytes:
    """Wrap raw little-endian PCM in a canonical 44-byte WAV header."""
    if not pcm:
        raise ValueError("empty audio")
    if len(pcm) % (channels * width):
        pcm = pcm[: len(pcm) - len(pcm) % (channels * width)]  # drop a torn last sample
    byte_rate = rate * channels * width
    header = (
        b"RIFF" + struct.pack("<I", 36 + len(pcm)) + b"WAVE"
        + b"fmt " + struct.pack("<IHHIIHH", 16, 1, channels, rate, byte_rate, channels * width, width * 8)
        + b"data" + struct.pack("<I", len(pcm))
    )
    return header + pcm


class GenaiSpeech:
    """google-genai TTS: API key (AI Studio) or Vertex AI via ADC, built on first use."""

    def __init__(
        self, model: str, api_key: str | None = None, *, voice: str = DEFAULT_VOICE, timeout_s: float = TTS_TIMEOUT_S,
        vertexai: bool = False, project: str | None = None, location: str | None = None,
    ):
        self._model = model
        self._voice = voice
        self._timeout_s = timeout_s
        self._kwargs: dict[str, Any] = (
            {"vertexai": True, "project": project, "location": location or "global"}
            if vertexai else {"api_key": api_key}
        )
        self._client: Any = None
        self._lock = threading.Lock()

    def supports(self, lang: str) -> bool:
        return lang in TTS_LANGS

    def _get(self):
        if self._client is None:
            with self._lock:
                if self._client is None:
                    from google import genai  # lazy: no import cost or credentials unless TTS is on
                    from google.genai import types

                    self._client = genai.Client(
                        http_options=types.HttpOptions(timeout=int(self._timeout_s * 1000)), **self._kwargs
                    )
        return self._client

    def _call(self, text: str) -> bytes:
        from google.genai import types

        resp = self._get().models.generate_content(
            model=self._model,
            contents=text,
            config=types.GenerateContentConfig(
                response_modalities=["AUDIO"],
                speech_config=types.SpeechConfig(
                    voice_config=types.VoiceConfig(
                        prebuilt_voice_config=types.PrebuiltVoiceConfig(voice_name=self._voice)
                    )
                ),
            ),
        )
        data = resp.candidates[0].content.parts[0].inline_data.data
        if not data:
            raise ValueError("TTS response carried no audio")
        return pcm_to_wav(bytes(data))

    def synthesize(self, text: str, lang: str) -> bytes:
        text = text.strip()[:MAX_TEXT_CHARS]
        if not text:
            raise ValueError("nothing to say")
        fut = _POOL.submit(self._call, text)
        try:
            return fut.result(timeout=self._timeout_s + 5)
        except concurrent.futures.TimeoutError as e:
            fut.cancel()
            raise TimeoutError(f"TTS call exceeded {self._timeout_s}s") from e


def speech_from_env(env: dict[str, str] | None = None) -> Speech | None:
    """``RELAY_TTS_MODEL`` unset = no server audio. Same credential switch as the text client
    (Vertex wins over an API key); no credentials = off, with a log line."""
    env = os.environ if env is None else env
    model = (env.get("RELAY_TTS_MODEL") or "").strip()
    if not model:
        return None
    voice = (env.get("RELAY_TTS_VOICE") or DEFAULT_VOICE).strip()
    use_vertex = env.get("GOOGLE_GENAI_USE_VERTEXAI", "").strip().lower() in ("true", "1")
    key = env.get("GEMINI_API_KEY")
    if use_vertex:
        return GenaiSpeech(
            model, voice=voice, vertexai=True, project=env.get("GOOGLE_CLOUD_PROJECT") or None,
            location=env.get("GOOGLE_CLOUD_LOCATION") or "global",
        )
    if key:
        return GenaiSpeech(model, key, voice=voice)
    log.error("RELAY_TTS_MODEL is set but neither Vertex nor GEMINI_API_KEY is configured; TTS stays off")
    return None
