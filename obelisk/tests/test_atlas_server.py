"""Tests for the Atlas hub endpoints, with a fake (or absent) parser.

Each test gets a fresh module import so the hub DB and vault paths are
isolated per test (the module reads env vars at import time).
"""
import importlib
import sys

import asyncio

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

    async def fake_chat(messages, max_tokens=300, timeout_s=30):
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

    async def fake_chat(messages, max_tokens=300, timeout_s=30):
        return "Everything is quiet right now."

    monkeypatch.setattr(mod, "_chat", fake_chat)
    client.post("/atlas/report", json={"app": "home", "title": "Garage left open"})

    import asyncio

    asyncio.run(mod._rebuild_brief())

    body = client.get("/atlas/brief").json()
    assert body["brief"] == "Everything is quiet right now."


def test_urgent_items_appear_until_acked(atlas, monkeypatch):
    mod, client = atlas

    async def fake_chat(messages, max_tokens=300, timeout_s=30):
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

    async def fake_chat(messages, max_tokens=300, timeout_s=30):
        return None

    monkeypatch.setattr(mod, "_chat", fake_chat)
    client.post("/atlas/report", json={"app": "home", "title": "Garage left open", "body": "since 3pm"})

    resp = client.post("/atlas/ask", json={"question": "garage"})
    assert resp.status_code == 200
    assert "Garage" in resp.json()["answer"]


def test_ask_uses_parser_when_available(atlas, monkeypatch):
    mod, client = atlas

    async def fake_chat(messages, max_tokens=300, timeout_s=30):
        return "The garage has been open since 3pm."

    monkeypatch.setattr(mod, "_chat", fake_chat)
    resp = client.post("/atlas/ask", json={"question": "garage"})
    assert resp.json()["answer"] == "The garage has been open since 3pm."


def test_delegate_forwards_to_hermes_and_files_a_report(atlas, monkeypatch):
    mod, client = atlas

    async def fake_hermes(task):
        return f"done: {task}"

    monkeypatch.setattr(mod, "_run_hermes_oneshot", fake_hermes)
    resp = client.post("/atlas/delegate", json={"task": "water the plants"})
    assert resp.status_code == 200
    assert resp.json()["answer"] == "done: water the plants"

    reports = client.get("/atlas/reports", params={"app": "Hermes"}).json()["reports"]
    assert len(reports) == 1
    assert reports[0]["body"] == "done: water the plants"


def test_delegate_returns_502_and_files_no_report_when_hermes_fails(atlas, monkeypatch):
    mod, client = atlas

    async def fake_hermes(task):
        raise mod.HTTPException(status_code=502, detail="Hermes isn't installed on this node.")

    monkeypatch.setattr(mod, "_run_hermes_oneshot", fake_hermes)
    resp = client.post("/atlas/delegate", json={"task": "water the plants"})
    assert resp.status_code == 502
    assert "isn't installed" in resp.json()["detail"]

    reports = client.get("/atlas/reports", params={"app": "Hermes"}).json()["reports"]
    assert reports == []


def test_urgent_survives_a_fresh_process_without_a_rebuild(atlas):
    # Fix #1 regression test: the very first /atlas/brief call on a freshly
    # started process (no _rebuild_brief has run yet) must still read urgent
    # items from the DB, not from the empty in-memory cache.
    mod, client = atlas
    resp = client.post("/atlas/report", json={"app": "home", "title": "Pipe burst", "priority": "urgent"})
    report_id = resp.json()["id"]

    urgent = client.get("/atlas/brief").json()["urgent"]
    assert any(u["id"] == report_id for u in urgent)

    client.post("/atlas/ack", json={"ids": [report_id]})
    urgent = client.get("/atlas/brief").json()["urgent"]
    assert not any(u["id"] == report_id for u in urgent)


def test_open_loop_capture_and_list(atlas):
    mod, client = atlas
    resp = client.post("/atlas/open-loop", json={"text": "call the vet about Max", "source": "voice"})
    assert resp.status_code == 200
    loop_id = resp.json()["id"]
    assert loop_id > 0

    open_loops = client.get("/atlas/open-loops").json()["open_loops"]
    assert len(open_loops) == 1
    assert open_loops[0]["text"] == "call the vet about Max"
    assert open_loops[0]["source"] == "voice"
    assert open_loops[0]["spoken_count"] == 0


def test_open_loop_key_replaces_earlier_loop(atlas):
    mod, client = atlas
    client.post("/atlas/open-loop", json={"text": "renew passport", "key": "passport"})
    client.post("/atlas/open-loop", json={"text": "renew passport (urgent, expires soon)", "key": "passport"})

    open_loops = client.get("/atlas/open-loops").json()["open_loops"]
    assert len(open_loops) == 1
    assert "expires soon" in open_loops[0]["text"]


def test_open_loops_ride_the_brief_oldest_first_max_two(atlas, monkeypatch):
    mod, client = atlas

    async def fake_chat(messages, max_tokens=300, timeout_s=30):
        return None

    monkeypatch.setattr(mod, "_chat", fake_chat)
    ids = []
    for text in ["first loop", "second loop", "third loop"]:
        ids.append(client.post("/atlas/open-loop", json={"text": text}).json()["id"])

    body = client.get("/atlas/brief").json()
    open_loops = body["open_loops"]
    assert [o["id"] for o in open_loops] == ids[:2]
    assert all(o["spoken_count"] == 1 for o in open_loops)
    assert "first loop" in body["brief"]
    assert "second loop" in body["brief"]
    assert "third loop" not in body["brief"]


def test_open_loop_ack_closes_it_and_the_next_one_rides(atlas, monkeypatch):
    mod, client = atlas

    async def fake_chat(messages, max_tokens=300, timeout_s=30):
        return None

    monkeypatch.setattr(mod, "_chat", fake_chat)
    first = client.post("/atlas/open-loop", json={"text": "first loop"}).json()["id"]
    second = client.post("/atlas/open-loop", json={"text": "second loop"}).json()["id"]
    third = client.post("/atlas/open-loop", json={"text": "third loop"}).json()["id"]

    client.get("/atlas/brief")  # rides first + second
    client.post("/atlas/open-loop/ack", json={"ids": [first]})

    open_loops = client.get("/atlas/brief").json()["open_loops"]
    assert [o["id"] for o in open_loops] == [second, third]

    closed = client.get("/atlas/open-loops", params={"include_closed": True}).json()["open_loops"]
    assert any(o["id"] == first and o["acked_at"] is not None for o in closed)


def test_capture_and_list(atlas):
    mod, client = atlas
    resp = client.post("/atlas/capture", json={"text": "need more PLA filament", "kind": "note"})
    assert resp.status_code == 200
    capture_id = resp.json()["id"]
    assert capture_id > 0

    captures = client.get("/atlas/captures").json()["captures"]
    assert len(captures) == 1
    assert captures[0]["text"] == "need more PLA filament"
    assert captures[0]["kind"] == "note"
    assert captures[0]["category"] is None


def test_capture_key_replaces_earlier_capture(atlas):
    mod, client = atlas
    client.post("/atlas/capture", json={"text": "idea: shop banner", "key": "banner-idea"})
    client.post("/atlas/capture", json={"text": "idea: shop banner v2", "key": "banner-idea"})

    captures = client.get("/atlas/captures").json()["captures"]
    assert len(captures) == 1
    assert captures[0]["text"] == "idea: shop banner v2"


def test_captures_sort_uses_local_model_when_available(atlas, monkeypatch):
    mod, client = atlas

    async def fake_chat(messages, max_tokens=300, timeout_s=30):
        return "house"

    monkeypatch.setattr(mod, "_chat", fake_chat)
    client.post("/atlas/capture", json={"text": "fix the garage door opener"})

    resp = client.post("/atlas/captures/sort")
    assert resp.status_code == 200
    assert resp.json() == {"sorted": 1}

    captures = client.get("/atlas/captures").json()["captures"]
    assert captures[0]["category"] == "house"
    assert captures[0]["sorted_at"] is not None


def test_captures_sort_falls_back_to_keywords_when_model_is_down(atlas, monkeypatch):
    mod, client = atlas

    async def fake_chat(messages, max_tokens=300, timeout_s=30):
        return None

    monkeypatch.setattr(mod, "_chat", fake_chat)
    client.post("/atlas/capture", json={"text": "restock Etsy shop listing photos"})
    client.post("/atlas/capture", json={"text": "mow the yard this weekend"})
    client.post("/atlas/capture", json={"text": "sketch out the new app onboarding flow"})

    client.post("/atlas/captures/sort")

    by_text = {c["text"]: c["category"] for c in client.get("/atlas/captures").json()["captures"]}
    assert by_text["restock Etsy shop listing photos"] == "shop"
    assert by_text["mow the yard this weekend"] == "house"
    assert by_text["sketch out the new app onboarding flow"] == "projects"


def test_captures_sort_only_touches_uncategorized(atlas, monkeypatch):
    mod, client = atlas

    async def fake_chat(messages, max_tokens=300, timeout_s=30):
        return "projects"

    monkeypatch.setattr(mod, "_chat", fake_chat)
    client.post("/atlas/capture", json={"text": "one"})
    first_sort = client.post("/atlas/captures/sort").json()
    assert first_sort == {"sorted": 1}

    second_sort = client.post("/atlas/captures/sort").json()
    assert second_sort == {"sorted": 0}


def test_captures_filtered_by_category(atlas, monkeypatch):
    mod, client = atlas

    async def fake_chat(messages, max_tokens=300, timeout_s=30):
        return "shop"

    monkeypatch.setattr(mod, "_chat", fake_chat)
    client.post("/atlas/capture", json={"text": "shop thing"})
    client.post("/atlas/captures/sort")

    shop_only = client.get("/atlas/captures", params={"category": "shop"}).json()["captures"]
    house_only = client.get("/atlas/captures", params={"category": "house"}).json()["captures"]
    assert len(shop_only) == 1
    assert len(house_only) == 0


def test_money_check_files_a_report_for_an_upcoming_renewal(atlas, monkeypatch):
    mod, client = atlas
    today = mod.datetime.now().date()
    due = today + mod.timedelta(days=3)
    mod.MONEY_FILE.parent.mkdir(parents=True, exist_ok=True)
    mod.MONEY_FILE.write_text(f"- Netflix | 15.49 | monthly | {due:%Y-%m-%d}\n", encoding="utf-8")

    resp = client.post("/atlas/money/check")
    assert resp.status_code == 200
    assert resp.json() == {"checked": 1, "filed": 1}

    reports = client.get("/atlas/reports", params={"app": "money"}).json()["reports"]
    assert len(reports) == 1
    assert "Netflix" in reports[0]["title"]
    assert "15.49" in reports[0]["body"]


def test_money_check_skips_renewals_outside_the_lookahead_window(atlas):
    mod, client = atlas
    today = mod.datetime.now().date()
    far = today + mod.timedelta(days=mod.MONEY_LOOKAHEAD_DAYS + 5)
    mod.MONEY_FILE.parent.mkdir(parents=True, exist_ok=True)
    mod.MONEY_FILE.write_text(f"- Domain | 18 | yearly | {far:%Y-%m-%d}\n", encoding="utf-8")

    resp = client.post("/atlas/money/check")
    assert resp.json() == {"checked": 1, "filed": 0}
    assert client.get("/atlas/reports", params={"app": "money"}).json()["reports"] == []


def test_money_check_is_idempotent_for_the_same_renewal_date(atlas):
    mod, client = atlas
    today = mod.datetime.now().date()
    due = today + mod.timedelta(days=1)
    mod.MONEY_FILE.parent.mkdir(parents=True, exist_ok=True)
    mod.MONEY_FILE.write_text(f"- Etsy Plus | 10 | monthly | {due:%Y-%m-%d}\n", encoding="utf-8")

    client.post("/atlas/money/check")
    client.post("/atlas/money/check")

    reports = client.get("/atlas/reports", params={"app": "money"}).json()["reports"]
    assert len(reports) == 1


def test_money_check_handles_missing_file_and_malformed_lines(atlas):
    mod, client = atlas
    resp = client.post("/atlas/money/check")
    assert resp.status_code == 200
    assert resp.json() == {"checked": 0, "filed": 0}

    mod.MONEY_FILE.parent.mkdir(parents=True, exist_ok=True)
    mod.MONEY_FILE.write_text(
        "# just a header\n- incomplete line\n- Bad Date | 5 | monthly | not-a-date\n",
        encoding="utf-8",
    )
    resp = client.post("/atlas/money/check")
    assert resp.json() == {"checked": 0, "filed": 0}


def test_money_subscriptions_lists_parsed_entries(atlas):
    mod, client = atlas
    today = mod.datetime.now().date()
    due = today + mod.timedelta(days=2)
    mod.MONEY_FILE.parent.mkdir(parents=True, exist_ok=True)
    mod.MONEY_FILE.write_text(f"- Gym | 29.99 | monthly | {due:%Y-%m-%d}\n", encoding="utf-8")

    subs = client.get("/atlas/money/subscriptions").json()["subscriptions"]
    assert len(subs) == 1
    assert subs[0]["name"] == "Gym"
    assert subs[0]["amount"] == 29.99


def test_catch_me_up_summarizes_reports_since_last_call_then_clears(atlas, monkeypatch):
    mod, client = atlas

    async def fake_chat(messages, max_tokens=300, timeout_s=30):
        return None

    monkeypatch.setattr(mod, "_chat", fake_chat)
    client.post("/atlas/report", json={"app": "home", "title": "Package delivered"})

    first = client.get("/atlas/catch-me-up").json()
    assert "Package delivered" in first["summary"]

    second = client.get("/atlas/catch-me-up").json()
    assert second["summary"] == "Nothing to report."


def test_catch_me_up_peek_does_not_advance_the_watermark(atlas, monkeypatch):
    mod, client = atlas

    async def fake_chat(messages, max_tokens=300, timeout_s=30):
        return None

    monkeypatch.setattr(mod, "_chat", fake_chat)
    client.post("/atlas/report", json={"app": "home", "title": "Garage left open"})

    peeked = client.get("/atlas/catch-me-up", params={"peek": True}).json()
    assert "Garage left open" in peeked["summary"]

    real = client.get("/atlas/catch-me-up").json()
    assert "Garage left open" in real["summary"]

    after = client.get("/atlas/catch-me-up").json()
    assert after["summary"] == "Nothing to report."


def test_catch_me_up_caps_the_lookback_window(atlas, monkeypatch):
    mod, client = atlas

    async def fake_chat(messages, max_tokens=300, timeout_s=30):
        return None

    monkeypatch.setattr(mod, "_chat", fake_chat)
    too_old = mod._now_ms() - (mod.CATCH_UP_MAX_HOURS + 1) * 3600 * 1000
    client.post("/atlas/report", json={"app": "home", "title": "Ancient event", "at": too_old})

    summary = client.get("/atlas/catch-me-up").json()["summary"]
    assert "Ancient event" not in summary


def test_catch_me_up_uses_parser_when_available(atlas, monkeypatch):
    mod, client = atlas

    async def fake_chat(messages, max_tokens=300, timeout_s=30):
        return "Welcome back! Quiet while you were out."

    monkeypatch.setattr(mod, "_chat", fake_chat)
    body = client.get("/atlas/catch-me-up").json()
    assert body["summary"] == "Welcome back! Quiet while you were out."


def test_frigate_cameras_lists_from_config(atlas, monkeypatch):
    mod, client = atlas

    async def fake_frigate_get(path, timeout_s=5):
        assert path == "/api/config"
        return {"cameras": {"garage_cam": {"enabled": False}, "front_porch": {"enabled": True}}}

    monkeypatch.setattr(mod, "_frigate_get", fake_frigate_get)
    body = client.get("/atlas/frigate/cameras").json()
    assert body["ok"] is True
    assert {"name": "garage_cam", "enabled": False} in body["cameras"]
    assert {"name": "front_porch", "enabled": True} in body["cameras"]


def test_frigate_cameras_when_frigate_is_down(atlas, monkeypatch):
    mod, client = atlas

    async def fake_frigate_get(path, timeout_s=5):
        return None

    monkeypatch.setattr(mod, "_frigate_get", fake_frigate_get)
    body = client.get("/atlas/frigate/cameras").json()
    assert body == {"ok": False, "cameras": []}


def test_print_preflight_not_configured_when_camera_missing(atlas, monkeypatch):
    mod, client = atlas

    async def fake_frigate_get(path, timeout_s=5):
        return {"cameras": {"front_porch": {"enabled": True}}}

    monkeypatch.setattr(mod, "_frigate_get", fake_frigate_get)
    body = client.get("/atlas/print/preflight").json()
    assert body["ok"] is False
    assert mod.PRINT_CAMERA in body["reason"]


def test_print_preflight_reports_disabled_camera(atlas, monkeypatch):
    mod, client = atlas

    async def fake_frigate_get(path, timeout_s=5):
        return {"cameras": {mod.PRINT_CAMERA: {"enabled": False}}}

    monkeypatch.setattr(mod, "_frigate_get", fake_frigate_get)
    body = client.get("/atlas/print/preflight").json()
    assert body["ok"] is False
    assert "disabled" in body["reason"]


def test_print_preflight_ok_when_camera_enabled(atlas, monkeypatch):
    mod, client = atlas

    async def fake_frigate_get(path, timeout_s=5):
        return {"cameras": {mod.PRINT_CAMERA: {"enabled": True}}}

    monkeypatch.setattr(mod, "_frigate_get", fake_frigate_get)
    body = client.get("/atlas/print/preflight").json()
    assert body["ok"] is True
    assert body["camera"] == mod.PRINT_CAMERA
    assert mod.PRINT_CAMERA in body["snapshot_url"]


def test_print_watch_reports_only_on_transition(atlas, monkeypatch):
    mod, client = atlas
    responses = iter(
        [
            {"cameras": {mod.PRINT_CAMERA: {"enabled": True}}},
            {"cameras": {mod.PRINT_CAMERA: {"enabled": True}}},
            {"cameras": {mod.PRINT_CAMERA: {"enabled": False}}},
            {"cameras": {mod.PRINT_CAMERA: {"enabled": False}}},
        ]
    )

    async def fake_frigate_get(path, timeout_s=5):
        return next(responses)

    monkeypatch.setattr(mod, "_frigate_get", fake_frigate_get)

    client.post("/atlas/print/watch-check")  # first reading, no prior state: no report
    client.post("/atlas/print/watch-check")  # still ok: no report
    client.post("/atlas/print/watch-check")  # transitions to not-ok: files a report
    client.post("/atlas/print/watch-check")  # stays not-ok: no duplicate

    reports = client.get("/atlas/reports", params={"app": "print"}).json()["reports"]
    assert len(reports) == 1
    assert "offline" in reports[0]["title"].lower()


def test_filament_check_flags_a_shortage(atlas):
    mod, client = atlas
    mod.FILAMENT_FILE.parent.mkdir(parents=True, exist_ok=True)
    mod.FILAMENT_FILE.write_text("- PLA | Black | 50 | 1000\n", encoding="utf-8")
    mod.QUEUE_FILE.parent.mkdir(parents=True, exist_ok=True)
    mod.QUEUE_FILE.write_text("- Bracket | PLA | 200 | queued\n", encoding="utf-8")

    resp = client.post("/atlas/filament/check")
    assert resp.json() == {"materials_checked": 1, "filed": 1}

    reports = client.get("/atlas/reports", params={"app": "filament"}).json()["reports"]
    assert len(reports) == 1
    assert "PLA" in reports[0]["title"]
    assert "150" in reports[0]["body"]  # short by 200 - 50


def test_filament_check_ok_when_enough_on_hand(atlas):
    mod, client = atlas
    mod.FILAMENT_FILE.parent.mkdir(parents=True, exist_ok=True)
    mod.FILAMENT_FILE.write_text("- PLA | Black | 900 | 1000\n", encoding="utf-8")
    mod.QUEUE_FILE.parent.mkdir(parents=True, exist_ok=True)
    mod.QUEUE_FILE.write_text("- Bracket | PLA | 200 | queued\n", encoding="utf-8")

    resp = client.post("/atlas/filament/check")
    assert resp.json() == {"materials_checked": 1, "filed": 0}
    assert client.get("/atlas/reports", params={"app": "filament"}).json()["reports"] == []


def test_filament_check_ignores_jobs_not_queued(atlas):
    mod, client = atlas
    mod.FILAMENT_FILE.parent.mkdir(parents=True, exist_ok=True)
    mod.FILAMENT_FILE.write_text("- PLA | Black | 10 | 1000\n", encoding="utf-8")
    mod.QUEUE_FILE.parent.mkdir(parents=True, exist_ok=True)
    mod.QUEUE_FILE.write_text("- Bracket | PLA | 500 | printing\n- Old job | PLA | 500 | done\n", encoding="utf-8")

    resp = client.post("/atlas/filament/check")
    assert resp.json() == {"materials_checked": 0, "filed": 0}


def test_filament_check_is_idempotent_for_the_same_shortage(atlas):
    mod, client = atlas
    mod.FILAMENT_FILE.parent.mkdir(parents=True, exist_ok=True)
    mod.FILAMENT_FILE.write_text("- PLA | Black | 10 | 1000\n", encoding="utf-8")
    mod.QUEUE_FILE.parent.mkdir(parents=True, exist_ok=True)
    mod.QUEUE_FILE.write_text("- Bracket | PLA | 200 | queued\n", encoding="utf-8")

    client.post("/atlas/filament/check")
    client.post("/atlas/filament/check")

    reports = client.get("/atlas/reports", params={"app": "filament"}).json()["reports"]
    assert len(reports) == 1


def test_filament_spools_and_queue_list_endpoints(atlas):
    mod, client = atlas
    mod.FILAMENT_FILE.parent.mkdir(parents=True, exist_ok=True)
    mod.FILAMENT_FILE.write_text("- PETG | Clear | 300 | 1000\n", encoding="utf-8")
    mod.QUEUE_FILE.parent.mkdir(parents=True, exist_ok=True)
    mod.QUEUE_FILE.write_text("- Enclosure | PETG | 150 | queued\n", encoding="utf-8")

    spools = client.get("/atlas/filament/spools").json()["spools"]
    assert spools == [{"material": "PETG", "color": "Clear", "remaining_g": 300.0, "total_g": 1000.0}]

    queue = client.get("/atlas/print/queue").json()["queue"]
    assert queue == [{"name": "Enclosure", "material": "PETG", "estimated_g": 150.0, "status": "queued"}]


def test_filament_check_handles_missing_files(atlas):
    mod, client = atlas
    resp = client.post("/atlas/filament/check")
    assert resp.status_code == 200
    assert resp.json() == {"materials_checked": 0, "filed": 0}


def test_athena_status_refresh_is_token_safe(atlas, monkeypatch):
    """Regression (Helios, live Nov 11): the auto-refresh must never raise 401.

    The background refresh calls the same SSH helper the /atlas/athena/status
    route uses; with a hub token configured but no caller present, a refresh
    that goes through the token dependency dies with 401 and the cache stays
    empty forever while the route still answers 200. Pin ATLAS_TOKEN so the
    dependency is armed and assert the refresh still populates the cache.
    """
    mod, client = atlas
    monkeypatch.setenv("ATLAS_TOKEN", "test-token")
    monkeypatch.setattr(mod, "TOKEN", "test-token")

    async def fake_ssh(command, timeout_s):
        if "docker ps" in command:
            return 0, "jellyfin|Up 2 hours|0.0.0.0:8096->8096/tcp", ""
        if "LastBootUpTime" in command:
            return 0, "3 days, 4 hours", ""
        if "Get-PSDrive" in command:
            return 0, "Used Free ---- ---- 100 200", ""
        return 0, "", ""

    monkeypatch.setattr(mod, "_run_athena_ssh", fake_ssh)
    asyncio.run(mod._refresh_athena_status())
    assert mod._athena_cache["containers"], "refresh left the cache empty"
    assert mod._athena_cache["containers"][0]["name"] == "jellyfin"
    assert mod._athena_cache["updated"] > 0


def test_athena_exec_requires_token_even_when_not_globally_required(atlas):
    mod, client = atlas
    # ATLAS_TOKEN is unset in this fixture, so other endpoints run tokenless —
    # but the exec channel must stay closed regardless (non-negotiable spec).
    resp = client.post("/atlas/athena", json={"command": "docker ps"})
    assert resp.status_code == 401


def test_athena_exec_runs_over_ssh_and_files_a_report(tmp_path, monkeypatch):
    monkeypatch.setenv("ATLAS_HUB_DB", str(tmp_path / "hub.db"))
    monkeypatch.setenv("ATLAS_VAULT", str(tmp_path / "vault"))
    monkeypatch.setenv("ATLAS_TOKEN", "secret123")
    sys.modules.pop("atlas_server", None)
    mod = importlib.import_module("atlas_server")

    async def fake_ssh(command, timeout_s):
        return 0, "hello from athena\n", ""

    monkeypatch.setattr(mod, "_run_athena_ssh", fake_ssh)
    with TestClient(mod.app) as client:
        resp = client.post(
            "/atlas/athena",
            json={"command": "echo hello from athena"},
            headers={"Authorization": "Bearer secret123"},
        )
        assert resp.status_code == 200
        body = resp.json()
        assert body["ok"] is True
        assert body["exit"] == 0
        assert "hello from athena" in body["stdout"]

        reports = client.get(
            "/atlas/reports", params={"app": "athena"}, headers={"Authorization": "Bearer secret123"}
        ).json()["reports"]
        assert len(reports) == 1


def test_athena_status_parses_docker_output(atlas):
    mod, client = atlas
    parsed = mod._parse_athena_status(
        "jellyfin|Up 3 days|8096->8096/tcp\nbazarr|Up 3 days|6767->6767/tcp",
        "2.01:23:45",
        "Used 120GB Free 380GB",
    )
    assert parsed["containers"] == [
        {"name": "jellyfin", "status": "Up 3 days", "ports": "8096->8096/tcp"},
        {"name": "bazarr", "status": "Up 3 days", "ports": "6767->6767/tcp"},
    ]
    assert "120GB" in parsed["disk"]


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
