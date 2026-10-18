"""Language registry: which wire languages Relay serves, and which are switched on.

The team can only read and review English and Hindi, so only those two ship reviewed templates and
model output. The other wire languages (``codec.LANG_CODES``) stay registered so a community
reviewer can switch one on, but they are OFF by default and every language-dependent path falls back
through ``effective``: a requester whose language is not enabled gets English text.

Override the enabled set with ``RELAY_LANGS`` (comma list, must include ``en``), e.g.
``RELAY_LANGS=en,hi,or`` brings Odia back. The environment is read on every call, so tests can
monkeypatch it. See the "Languages" section of backend/README.md.
"""

from __future__ import annotations

import os
from typing import Any

from .codec import LANG_CODES

FALLBACK = "en"
CONTROL_ROOM_SECOND = "hi"  # the control room's second language when a district's own is not enabled
DEFAULT_ENABLED = ("en", "hi")

# code -> (english_name, native_name, reviewed): ``reviewed`` means a native speaker on the team has
# checked the shipped strings. Only en and hi are.
_REGISTRY: dict[str, tuple[str, str, bool]] = {
    "en": ("English", "English", True),
    "hi": ("Hindi", "हिन्दी", True),
    "or": ("Odia", "ଓଡ଼ିଆ", False),
    "tl": ("Filipino (Tagalog)", "Filipino", False),
    "war": ("Waray", "Winaray", False),
    "ceb": ("Cebuano", "Cebuano", False),
}
assert tuple(_REGISTRY) == LANG_CODES


def _configured() -> tuple[str, ...]:
    raw = os.environ.get("RELAY_LANGS")
    if not raw or not raw.strip():
        return DEFAULT_ENABLED
    codes = [c.strip().lower() for c in raw.split(",") if c.strip()]
    unknown = [c for c in codes if c not in _REGISTRY]
    if unknown:
        raise ValueError(f"RELAY_LANGS has unknown language(s) {unknown}; known: {list(_REGISTRY)}")
    if FALLBACK not in codes:
        raise ValueError("RELAY_LANGS must include 'en'")
    return tuple(c for c in LANG_CODES if c in codes)  # wire order, de-duplicated


def validate() -> None:
    """Raise ValueError on a bad ``RELAY_LANGS`` (called at app start so a typo fails loudly)."""
    _configured()


def enabled() -> tuple[str, ...]:
    return _configured()


def is_enabled(code: str) -> bool:
    return code in _configured()


def effective(code: str) -> str:
    """``code`` if enabled, else English."""
    return code if is_enabled(code) else FALLBACK


def name(code: str) -> str:
    """English name of a language, for prompts and logs."""
    return _REGISTRY[code][0] if code in _REGISTRY else code


def listing() -> list[dict[str, Any]]:
    """The registry for ``GET /v1/languages``, in wire order."""
    on = set(_configured())
    return [
        {"code": c, "english_name": _REGISTRY[c][0], "native_name": _REGISTRY[c][1],
         "enabled": c in on, "reviewed": _REGISTRY[c][2]}
        for c in LANG_CODES
    ]
