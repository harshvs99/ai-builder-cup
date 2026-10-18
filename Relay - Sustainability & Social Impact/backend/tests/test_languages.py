"""Language registry: English and Hindi on by default, the rest registered but off until RELAY_LANGS."""

import pytest

from app import codec, districts, languages
from app.main import create_app


@pytest.fixture(autouse=True)
def default_langs(monkeypatch):
    monkeypatch.delenv("RELAY_LANGS", raising=False)


def test_defaults_and_helpers():
    assert languages.enabled() == ("en", "hi")
    assert languages.is_enabled("hi") and not languages.is_enabled("or")
    assert languages.effective("hi") == "hi" and languages.effective("or") == "en" and languages.effective("zz") == "en"
    assert languages.name("or") == "Odia" and languages.name("en") == "English"


def test_registry_covers_every_wire_language():
    assert [e["code"] for e in languages.listing()] == list(codec.LANG_CODES)
    assert {e["code"] for e in languages.listing() if e["reviewed"]} == {"en", "hi"}


def test_relay_langs_overrides(monkeypatch):
    monkeypatch.setenv("RELAY_LANGS", " hi , EN, or ,or")
    assert languages.enabled() == ("en", "hi", "or")  # wire order, de-duplicated
    assert languages.effective("or") == "or"


@pytest.mark.parametrize("bad", ["hi,or", "en,klingon"])
def test_bad_relay_langs_fails_loudly(monkeypatch, bad):
    monkeypatch.setenv("RELAY_LANGS", bad)
    with pytest.raises(ValueError):
        languages.enabled()
    with pytest.raises(ValueError):
        create_app(db_path=":memory:")


def test_district_local_lang_follows_enabled_languages(monkeypatch):
    puri, delhi = districts.by_id("puri"), districts.by_id("delhi")
    assert (puri.native_lang, puri.local_lang, delhi.local_lang) == ("or", "hi", "hi")
    monkeypatch.setenv("RELAY_LANGS", "en,hi,or")
    assert (puri.local_lang, delhi.local_lang) == ("or", "hi")


def test_languages_endpoint_needs_no_auth(client, monkeypatch):
    r = client.get("/v1/languages")
    assert r.status_code == 200
    body = r.json()
    assert [e["code"] for e in body] == list(codec.LANG_CODES)
    assert {e["code"] for e in body if e["enabled"]} == {"en", "hi"}
    assert set(body[0]) >= {"code", "english_name", "native_name", "enabled"}
    monkeypatch.setenv("RELAY_LANGS", "en,hi,or")
    assert {e["code"] for e in client.get("/v1/languages").json() if e["enabled"]} == {"en", "hi", "or"}
