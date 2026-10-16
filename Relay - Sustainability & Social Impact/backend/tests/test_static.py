"""Static dashboard mount: served at /, index.html fallback, API routes never shadowed."""

import pytest

from conftest import need_frame, sync

INDEX = "<!doctype html><title>Relay control room</title><div id=root></div>"


@pytest.fixture
def static_dir(tmp_path):
    d = tmp_path / "static"
    (d / "assets").mkdir(parents=True)
    (d / "index.html").write_text(INDEX)
    (d / "assets" / "app.js").write_text("console.log('relay')")
    (d / "favicon.svg").write_text("<svg/>")
    return str(d)


@pytest.fixture
def web(make_client, static_dir):
    return make_client(static_dir=static_dir)


def test_root_and_assets_are_served(web):
    r = web.get("/")
    assert r.status_code == 200 and r.text == INDEX and r.headers["content-type"].startswith("text/html")
    assert web.get("/index.html").text == INDEX
    js = web.get("/assets/app.js")
    assert js.status_code == 200 and js.text == "console.log('relay')" and "javascript" in js.headers["content-type"]
    assert web.get("/favicon.svg").status_code == 200


def test_unknown_non_api_get_paths_fall_back_to_index(web):
    for path in ("/tickets/101", "/some/deep/client/route", "/dispatch"):
        r = web.get(path)
        assert r.status_code == 200 and r.text == INDEX, path


def test_missing_files_are_404_not_index(web):
    assert web.get("/assets/missing.js").status_code == 404
    assert web.get("/nope.png").status_code == 404


def test_api_routes_are_never_shadowed(web):
    assert web.get("/healthz").json() == {"ok": True}
    assert web.get("/v1/tickets").json() == {"tickets": []}
    assert web.get("/v1/stats").json()["tickets"] == 0
    assert sync(web, [need_frame()[0]]).json()["accepted"] == 1          # POST /v1/sync still wins
    assert web.get("/v1/tickets/101").json()["ticket_no"] == 101
    assert web.post("/v1/tickets/101/approve").json()["status"] == "DISPATCHED"
    assert web.post("/v1/dev/inject", json={"needs": []}).status_code == 200
    # unknown paths under API prefixes are plain 404s, never the dashboard
    for path in ("/v1/nope", "/v1/tickets/999", "/v1", "/v1/", "/internal/nope", "/internal", "/healthz/x", "/v1/dev/x"):
        r = web.get(path)
        assert r.status_code == 404 and "Relay control room" not in r.text, path
    # wrong method on an API path: not the dashboard either
    assert web.post("/v1/tickets").status_code in (404, 405)
    assert web.post("/healthz").status_code in (404, 405)
    assert "Relay control room" not in web.get("/internal/triage").text
    assert web.post("/internal/triage").status_code == 401                # route reached, auth enforced


def test_no_static_dir_means_no_mount(make_client, tmp_path):
    assert make_client(static_dir=str(tmp_path / "missing")).get("/").status_code == 404
    assert make_client(static_dir="").get("/tickets/1").status_code == 404


def test_static_dir_from_env(monkeypatch, static_dir):
    from fastapi.testclient import TestClient

    from app.main import create_app

    monkeypatch.setenv("RELAY_STATIC_DIR", static_dir)
    c = TestClient(create_app(db_path=":memory:", pubsub_topic=""))
    assert c.get("/").text == INDEX and c.get("/healthz").json() == {"ok": True}
    c.app.state.store.close()


def test_path_traversal_is_contained(web):
    r = web.get("/../../etc/passwd")
    assert "root:" not in r.text
