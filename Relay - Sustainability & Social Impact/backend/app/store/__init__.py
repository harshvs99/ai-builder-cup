"""Repository layer. ``Store`` is the interface; ``open_store`` picks the backend from env."""

from __future__ import annotations

import os

from .base import AckSpec, IngestOutcome, PacketIn, Plan, Store
from .firestore import FirestoreStore
from .sqlite import SqliteStore

__all__ = [
    "AckSpec", "FirestoreStore", "IngestOutcome", "PacketIn", "Plan", "SqliteStore", "Store", "open_store",
]


def open_store(kind: str | None = None, db_path: str | None = None) -> Store:
    """``RELAY_STORE``: ``sqlite`` (default; file from ``RELAY_DB``) or ``firestore``
    (``GOOGLE_CLOUD_PROJECT`` + ``RELAY_FIRESTORE_DB``). Neither connects until first use."""
    kind = (kind if kind is not None else os.environ.get("RELAY_STORE", "sqlite")).strip().lower()
    if kind == "sqlite":
        return SqliteStore(db_path)
    if kind == "firestore":
        return FirestoreStore()
    raise ValueError(f"unknown RELAY_STORE {kind!r} (expected 'sqlite' or 'firestore')")
