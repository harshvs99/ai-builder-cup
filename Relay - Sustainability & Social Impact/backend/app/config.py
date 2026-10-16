from __future__ import annotations

import os
from dataclasses import dataclass


def _env_bool(name: str, default: bool = False) -> bool:
    value = os.environ.get(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


@dataclass(frozen=True)
class Settings:
    db_path: str = "relay.db"
    # Shared secret that gateway phones send as X-Relay-Key. Unset = open (dev only).
    device_key: str | None = None
    # Shared secret the dashboard sends as X-Operator-Key. Unset = open (dev only).
    operator_key: str | None = None
    gemini_api_key: str | None = None
    gemini_model: str = "gemini-flash-latest"
    llm_timeout_s: float = 8.0
    dev_mode: bool = False
    # Two reports merge into one ticket when they share a category, are within
    # this distance, and arrive within this window.
    merge_radius_m: float = 250.0
    merge_window_h: float = 6.0
    timezone: str = "Asia/Kolkata"
    ack_lookback_h: float = 24.0
    # Comma-separated origins allowed to call the API from a browser (the dashboard).
    cors_origins: str = "*"

    @classmethod
    def from_env(cls) -> "Settings":
        return cls(
            db_path=os.environ.get("RELAY_DB_PATH", cls.db_path),
            device_key=os.environ.get("RELAY_DEVICE_KEY") or None,
            operator_key=os.environ.get("RELAY_OPERATOR_KEY") or None,
            gemini_api_key=os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY") or None,
            gemini_model=os.environ.get("RELAY_GEMINI_MODEL", cls.gemini_model),
            llm_timeout_s=float(os.environ.get("RELAY_LLM_TIMEOUT_S", cls.llm_timeout_s)),
            dev_mode=_env_bool("RELAY_DEV_MODE"),
            merge_radius_m=float(os.environ.get("RELAY_MERGE_RADIUS_M", cls.merge_radius_m)),
            merge_window_h=float(os.environ.get("RELAY_MERGE_WINDOW_H", cls.merge_window_h)),
            timezone=os.environ.get("RELAY_TIMEZONE", cls.timezone),
            ack_lookback_h=float(os.environ.get("RELAY_ACK_LOOKBACK_H", cls.ack_lookback_h)),
            cors_origins=os.environ.get("RELAY_CORS_ORIGINS", cls.cors_origins),
        )
