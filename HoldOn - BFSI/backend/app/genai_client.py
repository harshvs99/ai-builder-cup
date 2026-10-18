"""The single place a Gemini client is built. Switching AI Studio -> Vertex is config only."""

from functools import lru_cache

from google import genai

from app.config import get_settings


class GeminiNotConfigured(RuntimeError):
    pass


@lru_cache
def get_client() -> genai.Client:
    s = get_settings()
    if s.google_genai_use_vertexai and s.google_cloud_project:
        return genai.Client(
            vertexai=True, project=s.google_cloud_project, location=s.google_cloud_location
        )
    if s.gemini_api_key:
        return genai.Client(api_key=s.gemini_api_key)
    raise GeminiNotConfigured("Set GEMINI_API_KEY (or Vertex settings) in backend/.env")
