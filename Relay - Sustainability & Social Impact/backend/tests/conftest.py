from __future__ import annotations

import base64
import secrets

import pytest
from fastapi.testclient import TestClient

from app.api import create_app
from app.config import Settings
from app.protocol import Category, Flag, Lang, NeedPacket, encode_need
from app.store import Store

T0 = 1_790_000_000.0  # fixed "now" for deterministic tests


class Clock:
    def __init__(self, now: float = T0):
        self.now = now

    def __call__(self) -> float:
        return self.now

    def advance(self, seconds: float) -> None:
        self.now += seconds


class FakeLLM:
    """Returns canned responses keyed by schema class name and records prompts."""

    name = "fake"

    def __init__(self, responses: dict | None = None):
        self.responses = responses or {}
        self.prompts: list[tuple[str, str]] = []

    def generate(self, prompt, schema):
        self.prompts.append((schema.__name__, prompt))
        response = self.responses.get(schema.__name__)
        if callable(response):
            response = response(prompt)
        return schema.model_validate(response) if response is not None else None


def make_need(**overrides) -> NeedPacket:
    fields = dict(
        msg_id=secrets.token_bytes(8),
        origin_node=0x0A0A0A0A,
        created_at=int(T0) - 60,
        category=Category.MEDICAL,
        urgency=3,
        people=1,
        flags=Flag.ELDERLY,
        lat=19.81352,
        lon=85.83119,
        loc_acc_m=15,
        lang=Lang.OR,
        item="insulin",
        landmark="school",
        summary="mother needs insulin, diabetic",
    )
    fields.update(overrides)
    return NeedPacket(**fields)


def b64(need: NeedPacket) -> str:
    return base64.b64encode(encode_need(need)).decode()


@pytest.fixture
def clock() -> Clock:
    return Clock()


@pytest.fixture
def settings() -> Settings:
    return Settings(db_path=":memory:", dev_mode=True)


@pytest.fixture
def fake_llm() -> FakeLLM:
    return FakeLLM()


@pytest.fixture
def client(settings, fake_llm, clock):
    app = create_app(settings=settings, store=Store(":memory:"), llm=fake_llm, clock=clock)
    with TestClient(app) as c:
        yield c
