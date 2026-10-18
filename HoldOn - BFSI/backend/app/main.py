"""FastAPI entrypoint. Routes are added per CONTRACTS.md."""

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.config import get_settings
from app.tools.schemas import Health

settings = get_settings()

app = FastAPI(title="HoldOn", version="0.1.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_origin_regex=r"http://(localhost|127\.0\.0\.1|192\.168\.\d+\.\d+|10\.\d+\.\d+\.\d+):\d+",
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/healthz", response_model=Health)
def healthz() -> Health:
    return Health(
        gemini=settings.gemini_enabled,
        telegram=settings.telegram_enabled,
        detector="gemini" if settings.use_gemini_detector else "heuristic",
        models={
            "live": settings.holdon_live_model,
            "flash": settings.holdon_flash_model,
            "report": settings.holdon_report_model,
        },
    )
