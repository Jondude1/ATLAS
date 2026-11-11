"""Tests for the Atlas hub endpoints, with a fake (or absent) parser.

Each test gets a fresh module import so the hub DB and vault paths are
isolated per test (the module reads env vars at import time).
"""
import importlib
import sys

import pytest
from fastapi.testclient import TestClient


@pytest.fixture
def atlas(tmp_path, monkeypatch):
    monkeypatch.setenv("ATLAS_HUB_DB", str(tmp_path / "hub.db"))
    monkeypatch.setenv("ATLAS_VAULT", str(tmp_path / "vault"))
    monkeypatch.delenv("ATLAS_TOKEN", raising=False)
    sys.modules.pop("atlas_server", None)
    mod = importlib.import_module("atlas_server")
    with TestClient(mod.app) as client:
        yield mod, client


def test_report_and_list(atlas):
    mod, client = atlas
    resp = client.post("/atlas/report", json={"app": "home", "title": "Garage left open"})
    assert resp.status_code == 200
    report_id = resp.json()["id"]
    assert report_id > 0

    resp = client.get("/atlas/reports")
    assert resp.status_code == 200
    reports = resp.json()["reports"]
    assert len(reports) == 1
    assert reports[0]["title"] == "Garage left open"


def test_key_replaces_earlier_report(atlas):
    mod, client = atlas
    client.post("/atlas/report", json={"app": "home", "title": "Garage open", "key": "garage-door"})
    client.post("/atlas/report", json={"app": "home", "title": "Garage closed", "key": "garage-door"})

    reports = client.get("/atlas/reports", params={"app": "home"}).json()["reports"]
    assert len(reports) == 1
    assert reports[0]["title"] == "Garage closed"


def test_reports_filtered_by_app(atlas):
    mod, client = atlas
    client.post("/atlas/report", json={"app": "home", "title": "A"})
    client.post("/atlas/report", json={"app": "mail", "title": "B"})

    reports = client.get("/atlas/reports", params={"app": "mail"}).json()["reports"]
    assert len(reports) == 1
    assert reports[0]["app"] == "mail"


def test_brief_falls_back_to_rules_when_parser_is_down(atlas, monkeypatch):
    mod, client = atlas

    async def fake_chat(messages, max_tokens=300):
        return None

    monkeypatch.setattr(mod, "_chat", fake_chat)
    client.post("/atlas/report", json={"app": "home", "title": "Garage left open"})

    import asyncio

    asyncio.run(mod._rebuild_brief())

    resp = client.get("/atlas/brief")
    assert resp.status_code == 200
    body = resp.json()
    assert "Garage left open" in body["brief"]
    assert len(body["brief"].split()) <= mod.BRIEF_MAX_WORDS


def test_brief_uses_parser_when_available(atlas, monkeypatch):
    mod, client = atlas

    async def fake_chat(messages, max_tokens=300):
        return "Everything is quiet right now."

    monkeypatch.setattr(mod, "_chat", fake_chat)
    client.post("/atlas/report", json={"app": "home", "title": "Garage left open"})

    import asyncio

    asyncio.run(mod._rebuild_brief())

    body = client.get("/atlas/brief").json()
    assert body["brief"] == "Everything is quiet right now."


def test_urgent_items_appear_until_acked(atlas, monkeypatch):
    mod, client = atlas

    async def fake_chat(messages, max_tokens=300):
        return None

    monkeypatch.setattr(mod, "_chat", fake_chat)
    resp = client.post(
        "/atlas/report", json={"app": "home", "title": "Pipe burst", "priority": "urgent"}
    )
    report_id = resp.json()["id"]

    import asyncio

    asyncio.run(mod._rebuild_brief())
    urgent = client.get("/atlas/brief").json()["urgent"]
    assert any(u["id"] == report_id for u in urgent)

    client.post("/atlas/ack", json={"ids": [report_id]})
    urgent = client.get("/atlas/brief").json()["urgent"]
    assert not any(u["id"] == report_id for u in urgent)


def test_ask_falls_back_to_matching_reports_when_parser_is_down(atlas, monkeypatch):
    mod, client = atlas

    async def fake_chat(messages, max_tokens=300):
        return None

    monkeypatch.setattr(mod, "_chat", fake_chat)
    client.post("/atlas/report", json={"app": "home", "title": "Garage left open", "body": "since 3pm"})

    resp = client.post("/atlas/ask", json={"question": "garage"})
    assert resp.status_code == 200
    assert "Garage" in resp.json()["answer"]


def test_ask_uses_parser_when_available(atlas, monkeypatch):
    mod, client = atlas

    async def fake_chat(messages, max_tokens=300):
        return "The garage has been open since 3pm."

    monkeypatch.setattr(mod, "_chat", fake_chat)
    resp = client.post("/atlas/ask", json={"question": "garage"})
    assert resp.json()["answer"] == "The garage has been open since 3pm."


def test_token_required_when_set(tmp_path, monkeypatch):
    monkeypatch.setenv("ATLAS_HUB_DB", str(tmp_path / "hub.db"))
    monkeypatch.setenv("ATLAS_VAULT", str(tmp_path / "vault"))
    monkeypatch.setenv("ATLAS_TOKEN", "secret123")
    sys.modules.pop("atlas_server", None)
    mod = importlib.import_module("atlas_server")
    with TestClient(mod.app) as client:
        resp = client.post("/atlas/report", json={"app": "home", "title": "x"})
        assert resp.status_code == 401

        resp = client.post(
            "/atlas/report",
            json={"app": "home", "title": "x"},
            headers={"Authorization": "Bearer secret123"},
        )
        assert resp.status_code == 200


def test_mcp_endpoint_is_mounted(atlas):
    mod, client = atlas
    resp = client.post(
        "/mcp",
        json={
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": {"name": "test", "version": "0"},
            },
        },
        headers={"Accept": "application/json, text/event-stream"},
    )
    assert resp.status_code == 200
