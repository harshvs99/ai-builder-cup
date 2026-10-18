from fastapi.testclient import TestClient

from app.main import app
from app.tools.schemas import EXPORTED_MODELS, RaiseScamAlertArgs


def test_healthz():
    r = TestClient(app).get("/healthz")
    assert r.status_code == 200
    body = r.json()
    assert body["ok"] is True
    assert body["detector"] in ("gemini", "heuristic")


def test_every_contract_model_has_a_json_schema():
    for m in EXPORTED_MODELS:
        assert m.model_json_schema()["title"] == m.__name__


def test_alert_args_bounds():
    import pytest
    from pydantic import ValidationError

    with pytest.raises(ValidationError):
        RaiseScamAlertArgs(
            scam_type="investment", confidence=1.5, red_flags=[], warning_text="x", language="en"
        )
