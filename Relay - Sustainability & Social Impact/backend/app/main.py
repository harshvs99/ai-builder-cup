"""ASGI entry point: ``uvicorn app.main:app``."""

import logging
import os

from .api import create_app

logging.basicConfig(
    level=os.environ.get("RELAY_LOG_LEVEL", "INFO"),
    format="%(asctime)s %(levelname)s %(name)s: %(message)s",
)

app = create_app()
