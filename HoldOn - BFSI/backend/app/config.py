"""Runtime settings. Every external dependency is optional; see PLAN.md "POC decisions"."""

from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict

BACKEND_DIR = Path(__file__).resolve().parent.parent
PROJECT_DIR = BACKEND_DIR.parent  # "HoldOn - BFSI/"
KNOWLEDGE_DIR = PROJECT_DIR / "knowledge"
DEMO_DIR = PROJECT_DIR / "demo"
DATA_DIR = BACKEND_DIR / ".data"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=BACKEND_DIR / ".env", extra="ignore")

    gemini_api_key: str = ""
    google_genai_use_vertexai: bool = False
    google_cloud_project: str = ""
    google_cloud_location: str = "asia-southeast1"

    holdon_live_model: str = "gemini-2.5-flash-native-audio-preview-09-2025"
    holdon_flash_model: str = "gemini-2.5-flash"
    holdon_report_model: str = "gemini-2.5-flash"

    telegram_bot_token: str = ""
    telegram_bot_username: str = ""

    holdon_detector: Literal["auto", "gemini", "heuristic"] = "auto"
    holdon_alert_threshold: float = 0.7

    holdon_cors_origins: str = "http://localhost:3000,http://127.0.0.1:3000"
    holdon_public_web_url: str = "http://localhost:3000"

    @property
    def gemini_enabled(self) -> bool:
        return bool(self.gemini_api_key) or (
            self.google_genai_use_vertexai and bool(self.google_cloud_project)
        )

    @property
    def telegram_enabled(self) -> bool:
        return bool(self.telegram_bot_token)

    @property
    def use_gemini_detector(self) -> bool:
        if self.holdon_detector == "heuristic":
            return False
        if self.holdon_detector == "gemini":
            return True
        return self.gemini_enabled

    @property
    def cors_origins(self) -> list[str]:
        return [o.strip() for o in self.holdon_cors_origins.split(",") if o.strip()]


@lru_cache
def get_settings() -> Settings:
    return Settings()
