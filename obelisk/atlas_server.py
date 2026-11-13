"""Atlas server for Obelisk.

Gives the phone app a home on your node:
  - every exchange is appended to a daily note in your Obsidian vault
  - recall can search the whole vault
  - the node knows which device you're on (presence)
  - the hub: every app reports here, a local model digests it, ATLAS
    (which pays per token on Claude) only ever sees a short brief or answer

Run:
    pip install -r requirements.txt
    ATLAS_VAULT=/path/to/ObsidianVault uvicorn atlas_server:app --host 0.0.0.0 --port 8765

Hub env vars:
    ATLAS_LLM_URL    OpenAI-style chat endpoint (default http://localhost:11434/v1, Ollama)
    ATLAS_LLM_MODEL  model name for that endpoint (default qwen3:14b-ha)
    ATLAS_TOKEN      if set, every /atlas/* request needs Authorization: Bearer <token>

Optional, for transcribing the Buddie earbud stream with Whisper on the node
(the phone only falls back to this when Android's own recognizer can't do it):
    pip install faster-whisper numpy
    ATLAS_WHISPER=base.en   (model size; default base.en)

Then put http://<obelisk-tailscale-name>:8765 in the app under
Settings -> Obelisk and local models. Keep it on Tailscale only: there is no login.
"""
import asyncio
import base64
import json
import os
import re
import sqlite3
import time
from contextlib import asynccontextmanager, closing, contextmanager
from datetime import datetime, timedelta
from pathlib import Path
from typing import Optional

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastmcp import FastMCP
from pydantic import BaseModel

VAULT = Path(os.environ.get("ATLAS_VAULT", str(Path.home() / "Obsidian"))).expanduser()
DAILY = VAULT / "Atlas" / "Daily"

HUB_DB = Path(os.environ.get("ATLAS_HUB_DB", str(Path.home() / ".local/share/atlas/hub.db"))).expanduser()
LLM_URL = os.environ.get("ATLAS_LLM_URL", "http://localhost:11434/v1")
LLM_MODEL = os.environ.get("ATLAS_LLM_MODEL", "qwen3:14b-ha")
TOKEN = os.environ.get("ATLAS_TOKEN")
REPORT_TTL_DAYS = 30
BRIEF_DEBOUNCE_S = 30
BRIEF_MAX_WORDS = 120
ASK_MAX_WORDS = 80
OPEN_LOOPS_PER_BRIEF = 2  # oldest first; stays in the running list until acked
CAPTURE_CATEGORIES = ("shop", "house", "projects")
CAPTURES_SORT_HOUR = int(os.environ.get("ATLAS_CAPTURES_SORT_HOUR", "2"))  # local 24h clock

# Money Sentinel v1: a hand-maintained vault file, one renewal per markdown
# list item: "- Name | Amount | Cadence | YYYY-MM-DD". Richer feeds later.
MONEY_FILE = Path(
    os.environ.get("ATLAS_MONEY_FILE", str(VAULT / "Atlas" / "subscriptions.md"))
).expanduser()
MONEY_LOOKAHEAD_DAYS = int(os.environ.get("ATLAS_MONEY_LOOKAHEAD_DAYS", "7"))
MONEY_CHECK_HOUR = int(os.environ.get("ATLAS_MONEY_CHECK_HOUR", "6"))  # local 24h clock

# Catch Me Up: the hub has no geofencing of its own, so "presence-triggered"
# means the app calls this the moment ITS presence signal says arrival; the
# hub's job is only the composition, watermarked by the last time this was
# actually fetched (not peeked).
CATCH_UP_MAX_HOURS = int(os.environ.get("ATLAS_CATCH_UP_MAX_HOURS", "72"))

SUNDAY_BRIEF_HOUR = int(os.environ.get("ATLAS_SUNDAY_BRIEF_HOUR", "9"))  # local 24h clock, Sunday
SUNDAY_BRIEF_LOOKBACK_DAYS = int(os.environ.get("ATLAS_SUNDAY_BRIEF_LOOKBACK_DAYS", "7"))

WHERE_WAS_I_LOOKBACK_MIN = int(os.environ.get("ATLAS_WHERE_WAS_I_LOOKBACK_MIN", "180"))

# Storm & Outage Protocol (#4), Outage half only. The Storm half (severe
# weather alerts) needs Jon's zip/coordinates to query correctly, which
# isn't guessed here — flagged in the issue instead. Outage detection is
# self-contained: TCP reachability to well-known hosts, no credentials,
# no personal data, so it ships now.
OUTAGE_CHECK_HOSTS = [h.strip() for h in os.environ.get("ATLAS_OUTAGE_CHECK_HOSTS", "1.1.1.1:443,8.8.8.8:443").split(",") if h.strip()]
OUTAGE_CHECK_INTERVAL_S = int(os.environ.get("ATLAS_OUTAGE_CHECK_INTERVAL_S", "120"))
_outage_state = {"last_up": None}
_outage_task: Optional[asyncio.Task] = None

# Account Sentinel (#6). No account-provider API access exists from this
# environment (same blocker class as #21/#24/#26), so this is the Money
# Sentinel pattern: a hand-maintained vault file, promoted to urgent so an
# alert can't quietly get buried -- written from the Nov 5 lesson.
ACCOUNT_ALERTS_FILE = Path(
    os.environ.get("ATLAS_ACCOUNT_ALERTS_FILE", str(VAULT / "Atlas" / "account_alerts.md"))
).expanduser()
ACCOUNT_CHECK_INTERVAL_S = int(os.environ.get("ATLAS_ACCOUNT_CHECK_INTERVAL_S", "600"))
_account_alerts_seen: set = set()
_account_task: Optional[asyncio.Task] = None

# House Steward (#23): voice-built grocery/supply lists (a plain DB-backed
# list, since items come and go by voice, not from a hand-edited file) plus
# maintenance clocks (vault file + cadence math, same shape as Money
# Sentinel) that announce when due, not when dead.
HOUSE_MAINTENANCE_FILE = Path(
    os.environ.get("ATLAS_HOUSE_MAINTENANCE_FILE", str(VAULT / "Atlas" / "house_maintenance.md"))
).expanduser()
HOUSE_MAINTENANCE_CHECK_HOUR = int(os.environ.get("ATLAS_HOUSE_MAINTENANCE_CHECK_HOUR", "8"))
_house_maintenance_task: Optional[asyncio.Task] = None

# Print Sentinel hub half v1. Honest limit: Frigate on Athena only tracks
# person/car/dog/cat on its cameras today, no printer-specific camera or
# zone — so this is camera-liveness watch + pre-flight snapshot data, NOT
# print-failure detection. Don't claim more than that in reports or UI text.
FRIGATE_URL = os.environ.get("ATLAS_FRIGATE_URL", "http://100.88.113.24:5000")
PRINT_CAMERA = os.environ.get("ATLAS_PRINT_CAMERA", "garage_cam")
PRINT_WATCH_INTERVAL_S = int(os.environ.get("ATLAS_PRINT_WATCH_INTERVAL_S", "300"))

# Filament & Queue Conductor v1. No design detail existed for this item (no
# OctoPrint/Moonraker/Spoolman found anywhere on the tailnet or in Athena's
# containers to integrate with), so this follows the Money Sentinel
# precedent: hand-maintained vault files, documented plainly, flagged in
# the issue as an assumption pending correction.
FILAMENT_FILE = Path(
    os.environ.get("ATLAS_FILAMENT_FILE", str(VAULT / "Atlas" / "filament.md"))
).expanduser()
QUEUE_FILE = Path(os.environ.get("ATLAS_QUEUE_FILE", str(VAULT / "Atlas" / "print_queue.md"))).expanduser()
FILAMENT_CHECK_HOUR = int(os.environ.get("ATLAS_FILAMENT_CHECK_HOUR", "7"))  # local 24h clock

# Conversational Slicer Tuning (#20): advisory only -- no OctoPrint/Moonraker
# or any slicer-control service exists to apply a setting automatically, so
# this tracks a settings profile in the vault and proposes a bounded tweak
# Jon applies himself. Bounds are enforced in code, not just the prompt, so
# a bad model response can't produce an absurd suggestion.
SLICER_PROFILE_FILE = Path(
    os.environ.get("ATLAS_SLICER_PROFILE_FILE", str(VAULT / "Atlas" / "slicer_profile.md"))
).expanduser()
DEFAULT_SLICER_SETTINGS = {"retraction_distance_mm": 0.8, "retraction_speed_mms": 40.0, "nozzle_temp_c": 200.0}
SLICER_SETTING_BOUNDS = {
    "retraction_distance_mm": (0.0, 10.0),
    "retraction_speed_mms": (5.0, 100.0),
    "nozzle_temp_c": (150.0, 260.0),
}

# Place-and-Person Triggers (#11): condition is free text, voice-created.
# Two ways a trigger fires: the hub evaluates a handful of known keyword
# patterns itself (currently just "printer idle/empty/done/free", via the
# print queue -- the only context the hub can actually sense on its own),
# or an app-side signal calls POST /atlas/triggers/event with a short event
# name ("home", "jenna") that's matched by substring against pending
# conditions -- "home" is not something this hub can detect (no geofencing
# here either, same conclusion as Catch Me Up / Leave-Home).
PLACE_TRIGGER_CHECK_INTERVAL_S = int(os.environ.get("ATLAS_PLACE_TRIGGER_CHECK_INTERVAL_S", "300"))
_place_trigger_task: Optional[asyncio.Task] = None

CHRONICLE_MAX_WORDS = 150  # a recap can run a bit longer than a spoken brief

# Care Nudges (#28): opt-in, off by default, so these live in a durable
# hub_settings table (not an in-memory flag -- a preference like this
# should survive a restart). "Sedentary stretch" isn't something this hub
# can sense itself, so the movement nudge is event-driven: the app calls
# POST /atlas/care/sedentary when ITS sensors say so, and the hub decides
# whether to actually speak up (opted in, past cooldown) -- rare and
# killable by design, never urgent.
CARE_MOVEMENT_COOLDOWN_S = int(os.environ.get("ATLAS_CARE_MOVEMENT_COOLDOWN_S", str(4 * 3600)))
_care_state = {"last_movement_nudge_at": 0}

# Second Set of Eyes, hub half (#7). "Point the phone" is the phone's own
# camera -- app-side, not this hub. The hub half is the "ask about a camera
# view" case: a local vision model (confirmed present in Ollama:
# qwen3-vl family) answers a plain question from a Frigate snapshot.
# "/no_think" is Qwen3's own inline control token to skip its chain-of-
# thought preamble -- without it the model burns the whole token budget
# thinking and returns empty content, confirmed live before relying on it.
VISION_MODEL = os.environ.get("ATLAS_VISION_MODEL", "qwen3-vl:4b")
VISION_TIMEOUT_S = 90

# Created up front (not after the routes) because the MCP sub-app's session
# manager needs its lifespan wired into the parent FastAPI app at construction.
mcp = FastMCP("atlas-hub")
_mcp_app = mcp.http_app()

@asynccontextmanager
async def _atlas_lifespan(app):
    # FastMCP's session manager guards run() so it can only be entered once
    # per process; TestClient enters the lifespan on every block, so a second
    # entry skips the MCP session manager instead of crashing startup.
    try:
        async with _mcp_app.lifespan(app):
            _schedule_athena_refresh()
            _schedule_captures_nightly()
            _schedule_money_check()
            _schedule_print_watch()
            _schedule_filament_check()
            _schedule_athena_container_watch()
            _schedule_gatekeeper()
            _schedule_sunday_brief()
            _schedule_outage_watch()
            _schedule_account_watch()
            _schedule_house_maintenance()
            _schedule_place_triggers()
            yield
    except RuntimeError:
        _schedule_athena_refresh()
        _schedule_captures_nightly()
        _schedule_money_check()
        _schedule_print_watch()
        _schedule_filament_check()
        _schedule_athena_container_watch()
        _schedule_gatekeeper()
        _schedule_sunday_brief()
        _schedule_outage_watch()
        _schedule_account_watch()
        _schedule_house_maintenance()
        _schedule_place_triggers()
        yield


app = FastAPI(title="Atlas on Obelisk", lifespan=_atlas_lifespan)
state = {"device": "unknown", "since": 0.0, "started": time.time()}


def require_token(authorization: Optional[str] = Header(default=None)):
    if not TOKEN:
        return
    if authorization != f"Bearer {TOKEN}":
        raise HTTPException(status_code=401, detail="bad or missing bearer token")


class LogEntry(BaseModel):
    speaker: str
    text: str
    device: str = "phone"


class Presence(BaseModel):
    device: str


@app.post("/atlas/log")
def log(entry: LogEntry):
    DAILY.mkdir(parents=True, exist_ok=True)
    now = datetime.now()
    path = DAILY / f"{now:%Y-%m-%d}.md"
    if not path.exists():
        path.write_text(f"# Atlas log, {now:%A %B %d %Y}\n\n", encoding="utf-8")
    text = entry.text.replace("\n", " ").strip()
    with path.open("a", encoding="utf-8") as f:
        if entry.speaker == "journal":
            f.write(f"\n## Journal\n{text}\n\n")
        else:
            f.write(f"- {now:%H:%M} **{entry.speaker}** ({entry.device}): {text}\n")
    return {"ok": True}


@app.post("/atlas/presence")
def presence(p: Presence):
    state["device"] = p.device
    state["since"] = time.time()
    return {"ok": True}


@app.get("/atlas/presence")
def where():
    return {"device": state["device"], "since": state["since"]}


def _search_vault_hits(q: str, limit: int = 8) -> list[dict]:
    terms = [t for t in re.split(r"\W+", q.lower()) if len(t) > 2]
    hits = []
    if terms and VAULT.exists():
        files = sorted(VAULT.rglob("*.md"), key=lambda p: p.stat().st_mtime, reverse=True)[:3000]
        for path in files:
            try:
                for line in path.read_text(encoding="utf-8", errors="ignore").splitlines():
                    low = line.lower()
                    score = sum(1 for t in terms if t in low)
                    if score:
                        hits.append({"note": path.stem, "line": line.strip()[:260], "score": score})
            except OSError:
                continue
    hits.sort(key=lambda h: -h["score"])
    return hits[:limit]


def _search_vault(q: str, limit: int = 8) -> list[str]:
    return [f"{h['note']}: {h['line']}" for h in _search_vault_hits(q, limit)]


@app.get("/atlas/search")
def search(q: str, limit: int = 8):
    return {"results": _search_vault(q, limit)}


@app.get("/atlas/status")
def status():
    return {
        "ok": True,
        "vault": str(VAULT),
        "vault_exists": VAULT.exists(),
        "device": state["device"],
        "uptime_s": int(time.time() - state["started"]),
    }


_whisper = None


def _model():
    """Loads faster-whisper on first use. Raises ImportError if it isn't installed."""
    global _whisper
    if _whisper is None:
        from faster_whisper import WhisperModel

        _whisper = WhisperModel(os.environ.get("ATLAS_WHISPER", "base.en"), compute_type="auto")
    return _whisper


@app.post("/atlas/transcribe")
async def transcribe(request: Request):
    """Body: raw 16 kHz mono 16-bit little-endian PCM. Returns {"text": ...}."""
    pcm = await request.body()
    if len(pcm) < 3200:
        return {"text": ""}
    try:
        import numpy as np

        model = _model()
    except ImportError:
        raise HTTPException(status_code=501, detail="faster-whisper is not installed on this node")
    audio = np.frombuffer(pcm[: len(pcm) // 2 * 2], dtype=np.int16).astype(np.float32) / 32768.0
    segments, _ = model.transcribe(audio, language="en", vad_filter=True)
    return {"text": " ".join(seg.text.strip() for seg in segments).strip()}


# ---------------------------------------------------------------------------
# Hub: every app reports here, a local model digests it, ATLAS gets a brief.
# ---------------------------------------------------------------------------

HUB_DB.parent.mkdir(parents=True, exist_ok=True)


@contextmanager
def hub_db():
    conn = sqlite3.connect(HUB_DB)
    conn.row_factory = sqlite3.Row
    try:
        with closing(conn):
            yield conn
    finally:
        pass


def init_hub_db():
    with hub_db() as conn:
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS reports (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                app TEXT NOT NULL,
                title TEXT NOT NULL,
                body TEXT NOT NULL DEFAULT '',
                priority TEXT NOT NULL DEFAULT 'normal',
                at INTEGER NOT NULL,
                key TEXT,
                told INTEGER NOT NULL DEFAULT 0
            )
            """
        )
        conn.execute("CREATE INDEX IF NOT EXISTS idx_reports_app_key ON reports(app, key)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_reports_at ON reports(at)")
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS open_loops (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                text TEXT NOT NULL,
                source TEXT NOT NULL DEFAULT 'voice',
                created_at INTEGER NOT NULL,
                acked_at INTEGER,
                spoken_count INTEGER NOT NULL DEFAULT 0,
                last_spoken_at INTEGER,
                key TEXT
            )
            """
        )
        conn.execute("CREATE INDEX IF NOT EXISTS idx_open_loops_acked ON open_loops(acked_at)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_open_loops_key ON open_loops(key)")
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS captures (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                text TEXT NOT NULL,
                kind TEXT NOT NULL DEFAULT 'note',
                created_at INTEGER NOT NULL,
                category TEXT,
                sorted_at INTEGER,
                key TEXT
            )
            """
        )
        conn.execute("CREATE INDEX IF NOT EXISTS idx_captures_category ON captures(category)")
        conn.execute("CREATE INDEX IF NOT EXISTS idx_captures_key ON captures(key)")
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS house_list_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                list_name TEXT NOT NULL DEFAULT 'grocery',
                item TEXT NOT NULL,
                added_at INTEGER NOT NULL,
                done_at INTEGER
            )
            """
        )
        conn.execute("CREATE INDEX IF NOT EXISTS idx_house_list ON house_list_items(list_name, done_at)")
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS routines (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL UNIQUE,
                steps TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS place_triggers (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                condition TEXT NOT NULL,
                reminder TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                fired_at INTEGER
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS chronicle_notes (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                text TEXT NOT NULL,
                kind TEXT NOT NULL DEFAULT 'note',
                created_at INTEGER NOT NULL
            )
            """
        )
        conn.execute("CREATE INDEX IF NOT EXISTS idx_chronicle_session ON chronicle_notes(session_id)")
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS chronicle_recaps (
                session_id TEXT PRIMARY KEY,
                recap TEXT NOT NULL,
                composed_at INTEGER NOT NULL
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS hub_settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """
        )
        conn.execute(
            """
            CREATE TABLE IF NOT EXISTS weigh_ins (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                weight REAL NOT NULL,
                logged_at INTEGER NOT NULL
            )
            """
        )
        conn.commit()


init_hub_db()

_brief_cache = {"brief": "", "updated": 0}
_brief_lock = asyncio.Lock()
_brief_mode = {"urgent_only": False}
_brief_task: Optional[asyncio.Task] = None
_captures_task: Optional[asyncio.Task] = None
_money_task: Optional[asyncio.Task] = None
_catch_up_cache = {"last_shown_at": 0}
_sunday_brief_cache = {"brief": "", "since": 0, "updated": 0}
_sunday_brief_task: Optional[asyncio.Task] = None
_print_watch_task: Optional[asyncio.Task] = None
_print_watch_state = {"last_ok": None}
_filament_task: Optional[asyncio.Task] = None


class Report(BaseModel):
    app: str
    title: str
    body: str = ""
    priority: str = "normal"
    at: Optional[int] = None
    key: Optional[str] = None


class Ack(BaseModel):
    ids: list[int]


class Ask(BaseModel):
    question: str


class OpenLoop(BaseModel):
    text: str
    source: str = "voice"
    key: Optional[str] = None


class OpenLoopAck(BaseModel):
    ids: list[int]


class Capture(BaseModel):
    text: str
    kind: str = "note"  # idea | note | remind, as spoken
    key: Optional[str] = None


class HouseListItem(BaseModel):
    item: str
    list_name: str = "grocery"


class HouseListDone(BaseModel):
    ids: list[int]


class Routine(BaseModel):
    name: str
    steps: list[str]


class RoutineRun(BaseModel):
    name: str


class SlicerTune(BaseModel):
    symptom: str


class PlaceTrigger(BaseModel):
    condition: str
    reminder: str


class PlaceTriggerEvent(BaseModel):
    event: str


class ChronicleNote(BaseModel):
    text: str
    session_id: Optional[str] = None
    kind: str = "note"  # loot | name | ruling | note


class ChronicleRecapRequest(BaseModel):
    session_id: str


class WeighIn(BaseModel):
    weight: float


class CameraAsk(BaseModel):
    camera: str
    question: str


class CameraFind(BaseModel):
    question: str


def _now_ms() -> int:
    return int(time.time() * 1000)


def _prune_old_reports(conn: sqlite3.Connection):
    cutoff = _now_ms() - REPORT_TTL_DAYS * 86400 * 1000
    conn.execute("DELETE FROM reports WHERE at < ?", (cutoff,))


async def _chat(messages: list[dict], max_tokens: int = 300, timeout_s: float = 30) -> Optional[str]:
    """Calls the OpenAI-style endpoint. Returns None (never raises) if it's down.

    Qwen3 models (qwen3:14b-ha included) are "thinking" models by default:
    without a cue to skip the reasoning preamble, they can spend the whole
    max_tokens budget on chain-of-thought and return empty content, which
    _rebuild_brief/ask then treat as "model's down" and silently use the
    deterministic fallback instead (B1.1, live-confirmed via finish_reason
    "length" + empty content on the exact brief prompt). Qwen3's own inline
    control token /no_think, prepended to the system message here, is the
    fix -- centralized so every _chat() caller inherits it.
    """
    primed = messages
    if messages and messages[0].get("role") == "system":
        primed = [dict(messages[0], content=f"/no_think {messages[0]['content']}")] + messages[1:]
    try:
        async with httpx.AsyncClient(timeout=timeout_s) as client:
            resp = await client.post(
                f"{LLM_URL.rstrip('/')}/chat/completions",
                json={"model": LLM_MODEL, "messages": primed, "max_tokens": max_tokens},
            )
            resp.raise_for_status()
            data = resp.json()
            return data["choices"][0]["message"]["content"].strip()
    except Exception:
        return None


def _age_str(at_ms: int, now_ms: int) -> str:
    secs = max(0, int((now_ms - at_ms) / 1000))
    if secs < 60:
        return f"{secs} sec ago"
    mins = secs // 60
    if mins < 60:
        return f"{mins} min ago"
    hrs = mins // 60
    if hrs < 24:
        return f"{hrs} hr ago"
    return f"{hrs // 24} d ago"


def _fallback_brief(rows: list[sqlite3.Row], loop_rows: Optional[list[sqlite3.Row]] = None) -> str:
    lines = [f"{r['app']}: {r['title']}" for r in rows[:8]]
    if loop_rows:
        lines.extend(f"open loop: {r['text']}" for r in loop_rows)
    if not lines:
        return "Nothing to report."
    text = ". ".join(lines)
    words = text.split()
    return " ".join(words[:BRIEF_MAX_WORDS])


def _pick_resurfacing_loops(conn: sqlite3.Connection, limit: int = OPEN_LOOPS_PER_BRIEF) -> list[sqlite3.Row]:
    return conn.execute(
        "SELECT * FROM open_loops WHERE acked_at IS NULL ORDER BY created_at ASC LIMIT ?",
        (limit,),
    ).fetchall()


async def _rebuild_brief():
    if _brief_mode["urgent_only"]:
        # Named Routines (#22) can put the hub in "workshop mode" etc: skip
        # the full LLM composition and just name the urgent items, nothing
        # else, until a routine (or the toggle) turns it back off.
        with hub_db() as conn:
            urgent_rows = conn.execute(
                "SELECT * FROM reports WHERE priority = 'urgent' AND told = 0 ORDER BY at DESC"
            ).fetchall()
        _brief_cache["brief"] = (
            "; ".join(r["title"] for r in urgent_rows) if urgent_rows else "Nothing urgent right now."
        )
        _brief_cache["updated"] = _now_ms()
        return
    # Fix #3: if a report lands while we wait on the model, rebuild once more
    # after finishing so it is not missed until some later report.
    for _attempt in range(2):
        query_started_ms = _now_ms()
        with hub_db() as conn:
            _prune_old_reports(conn)
            conn.commit()
            max_id_row = conn.execute("SELECT COALESCE(MAX(id), 0) AS m FROM reports").fetchone()
            max_id_at_start = max_id_row["m"]
            cutoff = query_started_ms - 24 * 3600 * 1000
            # Fix #2: only the last 24 hours, plus any untold urgent items.
            rows = conn.execute(
                "SELECT * FROM reports WHERE at >= ? OR (priority = 'urgent' AND told = 0) "
                "ORDER BY at DESC LIMIT 50",
                (cutoff,),
            ).fetchall()
            urgent_rows = conn.execute(
                "SELECT * FROM reports WHERE priority = 'urgent' AND told = 0 ORDER BY at DESC"
            ).fetchall()
            loop_rows = _pick_resurfacing_loops(conn)

        now_ms = _now_ms()
        reports_text = "\n".join(
            f"- [{_age_str(r['at'], now_ms)}] [{r['app']}] {r['title']}: {r['body']}".strip()
            for r in rows
        )
        loops_text = "\n".join(
            f"- [{_age_str(r['created_at'], now_ms)}] {r['text']}" for r in loop_rows
        )
        current_time = datetime.now().strftime("%A %Y-%m-%d %H:%M")
        reply = await _chat(
            [
                {
                    "role": "system",
                    "content": (
                        "You brief a voice assistant on what matters right now, across every app "
                        "reporting in, plus up to two open loops the person still hasn't closed. "
                        "Weave the open loops in briefly, near the end. Plain spoken English, at "
                        "most 120 words, no markdown, no lists."
                    ),
                },
                {
                    "role": "user",
                    "content": (
                        f"Current time: {current_time}\n\n{reports_text or '(no reports)'}"
                        + (f"\n\nOpen loops waiting:\n{loops_text}" if loop_rows else "")
                    ),
                },
            ],
            max_tokens=800,
            timeout_s=90,  # Fix #4: a cold local model needs longer than 20s.
        )
        brief = reply if reply else _fallback_brief(rows, loop_rows)
        _brief_cache["brief"] = brief
        _brief_cache["updated"] = _now_ms()
        _brief_cache["urgent"] = [
            {"id": r["id"], "app": r["app"], "title": r["title"], "body": r["body"], "at": r["at"]}
            for r in urgent_rows
        ]
        with hub_db() as conn:
            arrived = conn.execute(
                "SELECT COUNT(*) AS c FROM reports WHERE id > ?", (max_id_at_start,)
            ).fetchone()["c"]
        if not arrived:
            break


async def _debounced_rebuild():
    await asyncio.sleep(BRIEF_DEBOUNCE_S)
    await _rebuild_brief()


def _schedule_brief_rebuild():
    global _brief_task
    if _brief_task is not None and not _brief_task.done():
        return
    try:
        loop = asyncio.get_running_loop()
    except RuntimeError:
        return
    _brief_task = loop.create_task(_debounced_rebuild())


@app.post("/atlas/report", dependencies=[Depends(require_token)])
async def report(r: Report):
    # async (not def) so _schedule_brief_rebuild runs on the live event loop
    # instead of FastAPI's threadpool, where there is none to schedule onto.
    at = r.at if r.at is not None else _now_ms()
    with hub_db() as conn:
        if r.key:
            conn.execute("DELETE FROM reports WHERE app = ? AND key = ?", (r.app, r.key))
        cur = conn.execute(
            "INSERT INTO reports (app, title, body, priority, at, key, told) VALUES (?, ?, ?, ?, ?, ?, 0)",
            (r.app, r.title, r.body, r.priority, at, r.key),
        )
        conn.commit()
        report_id = cur.lastrowid
    _schedule_brief_rebuild()
    return {"id": report_id}


@app.get("/atlas/brief", dependencies=[Depends(require_token)])
def brief():
    with hub_db() as conn:
        urgent_rows = conn.execute(
            "SELECT * FROM reports WHERE priority = 'urgent' AND told = 0 ORDER BY at DESC"
        ).fetchall()
        loop_rows = _pick_resurfacing_loops(conn)
        now_ms = _now_ms()
        if loop_rows:
            # Counts as "spoken" here, not in _rebuild_brief: this GET is the
            # app actually taking the brief to read/say aloud.
            conn.executemany(
                "UPDATE open_loops SET spoken_count = spoken_count + 1, last_spoken_at = ? WHERE id = ?",
                [(now_ms, r["id"]) for r in loop_rows],
            )
            conn.commit()
        if _brief_cache["updated"] == 0:
            rows = conn.execute("SELECT * FROM reports ORDER BY at DESC LIMIT 50").fetchall()
            _brief_cache["brief"] = _fallback_brief(rows, loop_rows)
            _brief_cache["updated"] = now_ms
    urgent = [
        {"id": r["id"], "app": r["app"], "title": r["title"], "body": r["body"], "at": r["at"]}
        for r in urgent_rows
    ]
    open_loops = [
        {
            "id": r["id"],
            "text": r["text"],
            "source": r["source"],
            "created_at": r["created_at"],
            "spoken_count": r["spoken_count"] + 1,
            "last_spoken_at": now_ms,
        }
        for r in loop_rows
    ]
    _brief_cache["urgent"] = urgent
    _brief_cache["open_loops"] = open_loops
    return {
        "brief": _brief_cache["brief"],
        "urgent": urgent,
        "open_loops": open_loops,
        "updated": _brief_cache["updated"],
    }


@app.post("/atlas/ack", dependencies=[Depends(require_token)])
def ack(a: Ack):
    with hub_db() as conn:
        conn.executemany("UPDATE reports SET told = 1 WHERE id = ?", [(i,) for i in a.ids])
        conn.commit()
    _brief_cache["urgent"] = [u for u in _brief_cache.get("urgent", []) if u["id"] not in set(a.ids)]
    return {"ok": True}


@app.post("/atlas/open-loop", dependencies=[Depends(require_token)])
def open_loop(o: OpenLoop):
    with hub_db() as conn:
        if o.key:
            conn.execute("DELETE FROM open_loops WHERE key = ?", (o.key,))
        cur = conn.execute(
            "INSERT INTO open_loops (text, source, created_at, key) VALUES (?, ?, ?, ?)",
            (o.text, o.source, _now_ms(), o.key),
        )
        conn.commit()
        loop_id = cur.lastrowid
    return {"id": loop_id}


@app.get("/atlas/open-loops", dependencies=[Depends(require_token)])
def list_open_loops(include_closed: bool = False, limit: int = 50):
    with hub_db() as conn:
        if include_closed:
            rows = conn.execute(
                "SELECT * FROM open_loops ORDER BY created_at DESC LIMIT ?", (limit,)
            ).fetchall()
        else:
            rows = conn.execute(
                "SELECT * FROM open_loops WHERE acked_at IS NULL ORDER BY created_at ASC LIMIT ?",
                (limit,),
            ).fetchall()
    return {"open_loops": [dict(r) for r in rows]}


@app.post("/atlas/open-loop/ack", dependencies=[Depends(require_token)])
def open_loop_ack(a: OpenLoopAck):
    with hub_db() as conn:
        conn.executemany(
            "UPDATE open_loops SET acked_at = ? WHERE id = ? AND acked_at IS NULL",
            [(_now_ms(), i) for i in a.ids],
        )
        conn.commit()
    _brief_cache["open_loops"] = [
        o for o in _brief_cache.get("open_loops", []) if o["id"] not in set(a.ids)
    ]
    return {"ok": True}


def _fallback_capture_category(text: str) -> str:
    lowered = text.lower()
    shop_words = ("order", "etsy", "listing", "customer", "shop", "ship", "inventory")
    house_words = ("house", "home", "garage", "laundry", "kitchen", "yard", "repair", "chore")
    if any(w in lowered for w in shop_words):
        return "shop"
    if any(w in lowered for w in house_words):
        return "house"
    return "projects"


async def _classify_capture(text: str) -> str:
    reply = await _chat(
        [
            {
                "role": "system",
                "content": (
                    "Sort this quick-captured note into exactly one category: shop, house, "
                    "or projects. Reply with only that one word, nothing else."
                ),
            },
            {"role": "user", "content": text},
        ],
        max_tokens=300,
        timeout_s=20,
    )
    if reply:
        lowered = reply.lower()
        for category in CAPTURE_CATEGORIES:
            if category in lowered:
                return category
    return _fallback_capture_category(text)


async def _sort_captures() -> dict:
    with hub_db() as conn:
        rows = conn.execute(
            "SELECT * FROM captures WHERE category IS NULL ORDER BY created_at ASC"
        ).fetchall()
    for r in rows:
        category = await _classify_capture(r["text"])
        with hub_db() as conn:
            conn.execute(
                "UPDATE captures SET category = ?, sorted_at = ? WHERE id = ?",
                (category, _now_ms(), r["id"]),
            )
            conn.commit()
    return {"sorted": len(rows)}


def _seconds_until_next_hour(hour: int) -> float:
    now = datetime.now()
    target = now.replace(hour=hour, minute=0, second=0, microsecond=0)
    if target <= now:
        target += timedelta(days=1)
    return (target - now).total_seconds()


async def _captures_nightly_loop():
    while True:
        await asyncio.sleep(_seconds_until_next_hour(CAPTURES_SORT_HOUR))
        try:
            await _sort_captures()
        except Exception:
            pass


def _schedule_captures_nightly():
    global _captures_task
    if _captures_task is not None and not _captures_task.done():
        return
    try:
        loop = asyncio.get_running_loop()
    except RuntimeError:
        return
    _captures_task = loop.create_task(_captures_nightly_loop())


@app.post("/atlas/capture", dependencies=[Depends(require_token)])
def capture(c: Capture):
    with hub_db() as conn:
        if c.key:
            conn.execute("DELETE FROM captures WHERE key = ?", (c.key,))
        cur = conn.execute(
            "INSERT INTO captures (text, kind, created_at, key) VALUES (?, ?, ?, ?)",
            (c.text, c.kind, _now_ms(), c.key),
        )
        conn.commit()
        capture_id = cur.lastrowid
    return {"id": capture_id}


@app.get("/atlas/captures", dependencies=[Depends(require_token)])
def list_captures(category: Optional[str] = None, since: Optional[int] = None, limit: int = 50):
    query = "SELECT * FROM captures WHERE 1=1"
    params: list = []
    if category:
        query += " AND category = ?"
        params.append(category)
    if since is not None:
        query += " AND created_at >= ?"
        params.append(since)
    query += " ORDER BY created_at DESC LIMIT ?"
    params.append(limit)
    with hub_db() as conn:
        rows = conn.execute(query, params).fetchall()
    return {"captures": [dict(r) for r in rows]}


@app.post("/atlas/captures/sort", dependencies=[Depends(require_token)])
async def captures_sort():
    return await _sort_captures()


def _parse_money_line(line: str) -> Optional[dict]:
    line = line.strip()
    if not line.startswith("-"):
        return None
    parts = [p.strip() for p in line[1:].split("|")]
    if len(parts) < 4 or not parts[0]:
        return None
    name, amount_raw, cadence, date_raw = parts[0], parts[1], parts[2], parts[3]
    try:
        next_date = datetime.strptime(date_raw, "%Y-%m-%d").date()
    except ValueError:
        return None
    try:
        amount = float(amount_raw.replace("$", "").replace(",", ""))
    except ValueError:
        amount = None
    return {"name": name, "amount": amount, "cadence": cadence, "next_date": date_raw, "_date": next_date}


def _read_subscriptions() -> list[dict]:
    if not MONEY_FILE.exists():
        return []
    subs = []
    for line in MONEY_FILE.read_text(encoding="utf-8", errors="ignore").splitlines():
        parsed = _parse_money_line(line)
        if parsed:
            subs.append(parsed)
    return subs


async def _check_money_subscriptions() -> dict:
    today = datetime.now().date()
    horizon = today + timedelta(days=MONEY_LOOKAHEAD_DAYS)
    subs = _read_subscriptions()
    filed = 0
    for sub in subs:
        if today <= sub["_date"] <= horizon:
            days_out = (sub["_date"] - today).days
            when = "today" if days_out == 0 else ("tomorrow" if days_out == 1 else f"in {days_out} days")
            amount_str = f"${sub['amount']:.2f}" if sub["amount"] is not None else "unknown amount"
            await report(
                Report(
                    app="money",
                    title=f"{sub['name']} renews {when}",
                    body=f"{amount_str}, {sub['cadence']}, due {sub['next_date']}",
                    priority="normal",
                    key=f"money:{sub['name']}:{sub['next_date']}",
                )
            )
            filed += 1
    return {"checked": len(subs), "filed": filed}


async def _money_nightly_loop():
    while True:
        await asyncio.sleep(_seconds_until_next_hour(MONEY_CHECK_HOUR))
        try:
            await _check_money_subscriptions()
        except Exception:
            pass


def _schedule_money_check():
    global _money_task
    if _money_task is not None and not _money_task.done():
        return
    try:
        loop = asyncio.get_running_loop()
    except RuntimeError:
        return
    _money_task = loop.create_task(_money_nightly_loop())


@app.get("/atlas/money/subscriptions", dependencies=[Depends(require_token)])
def money_subscriptions():
    subs = _read_subscriptions()
    return {
        "subscriptions": [
            {"name": s["name"], "amount": s["amount"], "cadence": s["cadence"], "next_date": s["next_date"]}
            for s in subs
        ]
    }


@app.post("/atlas/money/check", dependencies=[Depends(require_token)])
async def money_check():
    return await _check_money_subscriptions()


async def _compose_catch_me_up(peek: bool = False) -> dict:
    now_ms = _now_ms()
    floor = now_ms - CATCH_UP_MAX_HOURS * 3600 * 1000
    since = max(_catch_up_cache.get("last_shown_at", 0), floor)
    with hub_db() as conn:
        rows = conn.execute(
            "SELECT * FROM reports WHERE at >= ? ORDER BY at ASC LIMIT 50", (since,)
        ).fetchall()
    reports_text = "\n".join(
        f"- [{_age_str(r['at'], now_ms)}] [{r['app']}] {r['title']}: {r['body']}".strip()
        for r in rows
    )
    reply = await _chat(
        [
            {
                "role": "system",
                "content": (
                    "Welcome the person back with a short, warm catch-up on what happened "
                    "while they were away, drawn only from the reports given. If nothing "
                    "happened, say so briefly and warmly. Plain spoken English, at most 120 "
                    "words, no markdown, no lists."
                ),
            },
            {
                "role": "user",
                "content": reports_text or "(nothing happened while they were away)",
            },
        ],
        max_tokens=800,
        timeout_s=60,
    )
    summary = reply if reply else _fallback_brief(rows)
    if not peek:
        _catch_up_cache["last_shown_at"] = now_ms
    return {"summary": summary, "since": since, "updated": now_ms}


@app.get("/atlas/catch-me-up", dependencies=[Depends(require_token)])
async def catch_me_up(peek: bool = False):
    return await _compose_catch_me_up(peek=peek)


def _seconds_until_next_sunday(hour: int) -> float:
    now = datetime.now()
    days_ahead = (6 - now.weekday()) % 7  # Monday=0 ... Sunday=6
    target = (now + timedelta(days=days_ahead)).replace(hour=hour, minute=0, second=0, microsecond=0)
    if target <= now:
        target += timedelta(days=7)
    return (target - now).total_seconds()


async def _compose_sunday_brief() -> dict:
    now_ms = _now_ms()
    since = now_ms - SUNDAY_BRIEF_LOOKBACK_DAYS * 86400 * 1000
    with hub_db() as conn:
        rows = conn.execute(
            "SELECT * FROM reports WHERE at >= ? ORDER BY at ASC LIMIT 200", (since,)
        ).fetchall()
    reports_text = "\n".join(
        f"- [{_age_str(r['at'], now_ms)}] [{r['app']}] {r['title']}: {r['body']}".strip()
        for r in rows
    )
    # Care Nudges (#28): opt-in weekly weigh-in line, written to be easy to
    # skip past -- never phrased as pressure or guilt.
    care_line = ""
    if _get_setting("care_weighin_enabled") == "true":
        with hub_db() as conn:
            latest = conn.execute("SELECT * FROM weigh_ins ORDER BY logged_at DESC LIMIT 1").fetchone()
        if latest and latest["logged_at"] >= since:
            care_line = f"This week's weigh-in: {latest['weight']}."
        else:
            care_line = "No weigh-in logged this week -- a gentle, optional nudge, no pressure."
    reply = await _chat(
        [
            {
                "role": "system",
                "content": (
                    "Write a short, warm weekly look-back covering the last 7 days, drawn "
                    "only from the reports given. If the week was quiet, say so briefly and "
                    "warmly. If a weigh-in line is given, mention it once, gently, in a way "
                    "that's easy to skip past -- never pressure or guilt. Plain spoken "
                    "English, at most 120 words, no markdown, no lists."
                ),
            },
            {
                "role": "user",
                "content": (reports_text or "(a quiet week, nothing reported)")
                + (f"\n\n{care_line}" if care_line else ""),
            },
        ],
        max_tokens=800,
        timeout_s=90,
    )
    summary = reply if reply else (_fallback_brief(rows) + (f" {care_line}" if care_line else ""))
    _sunday_brief_cache["brief"] = summary
    _sunday_brief_cache["since"] = since
    _sunday_brief_cache["updated"] = now_ms
    return {"brief": summary, "since": since, "updated": now_ms}


async def _sunday_brief_loop():
    while True:
        await asyncio.sleep(_seconds_until_next_sunday(SUNDAY_BRIEF_HOUR))
        try:
            await _compose_sunday_brief()
        except Exception:
            pass


def _schedule_sunday_brief():
    global _sunday_brief_task
    if _sunday_brief_task is not None and not _sunday_brief_task.done():
        return
    try:
        loop = asyncio.get_running_loop()
    except RuntimeError:
        return
    _sunday_brief_task = loop.create_task(_sunday_brief_loop())


@app.get("/atlas/sunday-brief", dependencies=[Depends(require_token)])
def sunday_brief():
    return {
        "brief": _sunday_brief_cache["brief"] or "No Sunday Brief has been composed yet.",
        "since": _sunday_brief_cache["since"],
        "updated": _sunday_brief_cache["updated"],
    }


@app.post("/atlas/sunday-brief/compose", dependencies=[Depends(require_token)])
async def sunday_brief_compose():
    return await _compose_sunday_brief()


def _recent_daily_log_lines(limit: int = 8) -> list[str]:
    if not DAILY.exists():
        return []
    today_path = DAILY / f"{datetime.now():%Y-%m-%d}.md"
    path = today_path if today_path.exists() else None
    if path is None:
        files = sorted(DAILY.glob("*.md"), key=lambda p: p.stat().st_mtime, reverse=True)
        path = files[0] if files else None
    if path is None:
        return []
    try:
        lines = [l.strip() for l in path.read_text(encoding="utf-8", errors="ignore").splitlines() if l.strip()]
    except OSError:
        return []
    lines = [l for l in lines if not l.startswith("# Atlas log")]
    return lines[-limit:]


async def _compose_where_was_i() -> dict:
    now_ms = _now_ms()
    cutoff = now_ms - WHERE_WAS_I_LOOKBACK_MIN * 60 * 1000
    log_lines = _recent_daily_log_lines(limit=8)
    with hub_db() as conn:
        recent_reports = conn.execute(
            "SELECT * FROM reports WHERE at >= ? ORDER BY at DESC LIMIT 5", (cutoff,)
        ).fetchall()
        latest_capture = conn.execute("SELECT * FROM captures ORDER BY created_at DESC LIMIT 1").fetchone()
        latest_loop = conn.execute(
            "SELECT * FROM open_loops WHERE acked_at IS NULL ORDER BY created_at DESC LIMIT 1"
        ).fetchone()
    pieces = []
    if log_lines:
        pieces.append("Recent log:\n" + "\n".join(log_lines))
    if recent_reports:
        pieces.append(
            "Recent reports:\n"
            + "\n".join(f"- [{r['app']}] {r['title']}: {r['body']}".strip() for r in recent_reports)
        )
    if latest_capture:
        pieces.append(f"Most recent quick-capture: {latest_capture['text']}")
    if latest_loop:
        pieces.append(f"Most recent still-open loop: {latest_loop['text']}")
    context_text = "\n\n".join(pieces)
    reply = await _chat(
        [
            {
                "role": "system",
                "content": (
                    "Remind the person what they were just doing or talking about, from the "
                    "activity given. One or two short spoken sentences, plain English, no "
                    "markdown. If there's nothing recent, say so briefly."
                ),
            },
            {"role": "user", "content": context_text or "(nothing recent)"},
        ],
        max_tokens=400,
        timeout_s=30,
    )
    if not reply:
        if log_lines:
            reply = f"Last logged: {log_lines[-1]}"
        elif recent_reports:
            r = recent_reports[0]
            reply = f"Last thing noted: [{r['app']}] {r['title']}"
        elif latest_capture:
            reply = f"You last jotted down: {latest_capture['text']}"
        elif latest_loop:
            reply = f"You still need to: {latest_loop['text']}"
        else:
            reply = "Nothing recent to pick back up on."
    return {"answer": reply, "updated": now_ms}


@app.get("/atlas/where-was-i", dependencies=[Depends(require_token)])
async def where_was_i():
    return await _compose_where_was_i()


async def _tcp_reachable(host_port: str, timeout_s: float = 5) -> bool:
    try:
        host, port_str = host_port.rsplit(":", 1)
        port = int(port_str)
    except ValueError:
        return False
    try:
        reader, writer = await asyncio.wait_for(asyncio.open_connection(host, port), timeout=timeout_s)
        writer.close()
        try:
            await writer.wait_closed()
        except Exception:
            pass
        return True
    except Exception:
        return False


async def _check_internet_outage() -> dict:
    up = False
    for host_port in OUTAGE_CHECK_HOSTS:
        if await _tcp_reachable(host_port):
            up = True
            break
    last_up = _outage_state.get("last_up")
    if last_up is not None and up != last_up:
        if up:
            title, body = "Internet's back", "Obelisk can reach the outside internet again."
        else:
            title, body = (
                "Internet looks down",
                "Obelisk can't reach any outside host right now — could be an outage, or just this node.",
            )
        await report(
            Report(
                app="outage",
                title=title,
                body=body,
                priority="urgent" if not up else "normal",
                key="internet-outage",
            )
        )
    _outage_state["last_up"] = up
    return {"up": up}


async def _outage_loop():
    while True:
        await asyncio.sleep(OUTAGE_CHECK_INTERVAL_S)
        try:
            await _check_internet_outage()
        except Exception:
            pass


def _schedule_outage_watch():
    global _outage_task
    if _outage_task is not None and not _outage_task.done():
        return
    try:
        loop = asyncio.get_running_loop()
    except RuntimeError:
        return
    _outage_task = loop.create_task(_outage_loop())


@app.post("/atlas/outage/check", dependencies=[Depends(require_token)])
async def outage_check():
    return await _check_internet_outage()


def _parse_account_alert_line(line: str) -> Optional[dict]:
    line = line.strip()
    if not line.startswith("-"):
        return None
    parts = [p.strip() for p in line[1:].split("|")]
    if len(parts) < 3 or not parts[0]:
        return None
    return {"account": parts[0], "detail": parts[1], "date": parts[2]}


def _read_account_alerts() -> list[dict]:
    if not ACCOUNT_ALERTS_FILE.exists():
        return []
    alerts = []
    for line in ACCOUNT_ALERTS_FILE.read_text(encoding="utf-8", errors="ignore").splitlines():
        parsed = _parse_account_alert_line(line)
        if parsed:
            alerts.append(parsed)
    return alerts


async def _check_account_alerts() -> dict:
    alerts = _read_account_alerts()
    filed = 0
    for alert in alerts:
        key = f"account-alert:{alert['account']}:{alert['detail']}:{alert['date']}"
        if key in _account_alerts_seen:
            continue
        await report(
            Report(
                app="account",
                title=f"New sign-in: {alert['account']}",
                body=f"{alert['detail']} — {alert['date']}",
                priority="urgent",
                key=key,
            )
        )
        _account_alerts_seen.add(key)
        filed += 1
    return {"checked": len(alerts), "filed": filed}


async def _account_loop():
    while True:
        await asyncio.sleep(ACCOUNT_CHECK_INTERVAL_S)
        try:
            await _check_account_alerts()
        except Exception:
            pass


def _schedule_account_watch():
    global _account_task
    if _account_task is not None and not _account_task.done():
        return
    try:
        loop = asyncio.get_running_loop()
    except RuntimeError:
        return
    _account_task = loop.create_task(_account_loop())


@app.post("/atlas/account/check", dependencies=[Depends(require_token)])
async def account_check():
    return await _check_account_alerts()


@app.post("/atlas/house/list/item", dependencies=[Depends(require_token)])
def house_list_add(i: HouseListItem):
    with hub_db() as conn:
        cur = conn.execute(
            "INSERT INTO house_list_items (list_name, item, added_at) VALUES (?, ?, ?)",
            (i.list_name, i.item, _now_ms()),
        )
        conn.commit()
        item_id = cur.lastrowid
    return {"id": item_id}


@app.get("/atlas/house/list", dependencies=[Depends(require_token)])
def house_list(list_name: str = "grocery", include_done: bool = False):
    with hub_db() as conn:
        if include_done:
            rows = conn.execute(
                "SELECT * FROM house_list_items WHERE list_name = ? ORDER BY added_at DESC", (list_name,)
            ).fetchall()
        else:
            rows = conn.execute(
                "SELECT * FROM house_list_items WHERE list_name = ? AND done_at IS NULL ORDER BY added_at ASC",
                (list_name,),
            ).fetchall()
    return {"items": [dict(r) for r in rows]}


@app.post("/atlas/house/list/item/done", dependencies=[Depends(require_token)])
def house_list_done(d: HouseListDone):
    with hub_db() as conn:
        conn.executemany(
            "UPDATE house_list_items SET done_at = ? WHERE id = ? AND done_at IS NULL",
            [(_now_ms(), i) for i in d.ids],
        )
        conn.commit()
    return {"ok": True}


def _parse_maintenance_line(line: str) -> Optional[dict]:
    line = line.strip()
    if not line.startswith("-"):
        return None
    parts = [p.strip() for p in line[1:].split("|")]
    if len(parts) < 3 or not parts[0]:
        return None
    item, cadence_raw, last_done_raw = parts[0], parts[1], parts[2]
    try:
        cadence_days = int(float(cadence_raw))
    except ValueError:
        return None
    try:
        last_done = datetime.strptime(last_done_raw, "%Y-%m-%d").date()
    except ValueError:
        return None
    return {"item": item, "cadence_days": cadence_days, "last_done": last_done_raw, "_last_done": last_done}


def _read_house_maintenance() -> list[dict]:
    if not HOUSE_MAINTENANCE_FILE.exists():
        return []
    items = []
    for line in HOUSE_MAINTENANCE_FILE.read_text(encoding="utf-8", errors="ignore").splitlines():
        parsed = _parse_maintenance_line(line)
        if parsed:
            items.append(parsed)
    return items


async def _check_house_maintenance() -> dict:
    today = datetime.now().date()
    filed = 0
    items = _read_house_maintenance()
    for it in items:
        next_due = it["_last_done"] + timedelta(days=it["cadence_days"])
        if today >= next_due:
            await report(
                Report(
                    app="house",
                    title=f"{it['item']} is due",
                    body=f"Every {it['cadence_days']} days, last done {it['last_done']}",
                    priority="normal",
                    key=f"maintenance:{it['item']}:{it['last_done']}",
                )
            )
            filed += 1
    return {"checked": len(items), "filed": filed}


async def _house_maintenance_loop():
    while True:
        await asyncio.sleep(_seconds_until_next_hour(HOUSE_MAINTENANCE_CHECK_HOUR))
        try:
            await _check_house_maintenance()
        except Exception:
            pass


def _schedule_house_maintenance():
    global _house_maintenance_task
    if _house_maintenance_task is not None and not _house_maintenance_task.done():
        return
    try:
        loop = asyncio.get_running_loop()
    except RuntimeError:
        return
    _house_maintenance_task = loop.create_task(_house_maintenance_loop())


@app.get("/atlas/house/maintenance", dependencies=[Depends(require_token)])
def house_maintenance():
    return {
        "items": [
            {"item": i["item"], "cadence_days": i["cadence_days"], "last_done": i["last_done"]}
            for i in _read_house_maintenance()
        ]
    }


@app.post("/atlas/house/maintenance/check", dependencies=[Depends(require_token)])
async def house_maintenance_check():
    return await _check_house_maintenance()


async def _compose_leave_home_check() -> dict:
    # Composition/checklist half only: doors, thermostat setback and
    # HA-armed state live on Home Assistant, which this hub has no token
    # for and architecturally isn't meant to hold (the app talks to HA
    # directly, per docs/REACH.md). Flagged as unreachable below rather
    # than stalling on it or faking a result.
    cams = await frigate_cameras()
    print_state = await print_preflight()
    checklist = []
    exceptions = []

    if cams.get("ok"):
        names = [c["name"] for c in cams["cameras"]]
        disabled = [c["name"] for c in cams["cameras"] if not c["enabled"]]
        checklist.append(f"Cameras: {len(names) - len(disabled)}/{len(names)} armed")
        if disabled:
            exceptions.append(f"Not armed: {', '.join(disabled)}")
    else:
        exceptions.append("Cameras: Frigate isn't answering")

    if print_state.get("ok"):
        checklist.append(f"Print area camera ({print_state['camera']}): watching")
    else:
        exceptions.append(f"Print area: {print_state.get('reason', 'not available')}")

    exceptions.append("Doors/thermostat/HA-armed state: not reachable from this hub -- check the app's HA connection")

    reply = await _chat(
        [
            {
                "role": "system",
                "content": (
                    "Compose a one-line 'House is set' style summary for someone leaving "
                    "home, from the checklist and flagged items given. Mention exceptions "
                    "by name; if the checklist items are all fine, lead with 'House is set.' "
                    "Plain spoken English, one short sentence, no markdown."
                ),
            },
            {
                "role": "user",
                "content": "Checklist:\n" + "\n".join(checklist) + "\n\nFlagged:\n" + "\n".join(exceptions),
            },
        ],
        max_tokens=400,
        timeout_s=30,
    )
    if not reply:
        reply = "House is set, except: " + "; ".join(exceptions)
    return {"summary": reply, "checklist": checklist, "exceptions": exceptions}


@app.get("/atlas/leave-home/check", dependencies=[Depends(require_token)])
async def leave_home_check():
    return await _compose_leave_home_check()


# Named Routines (#22), hub half. A routine is a flat list of free-text
# steps, voice-taught. Only a handful of step phrases are hub-actionable
# (printer status, brief mode); anything else is named back to the caller
# as "pass to app/HA" rather than silently dropped -- this hub has no way
# to flip a light or start music itself.
ROUTINE_HUB_KEYWORDS = {
    "printer status": "printer_status",
    "print status": "printer_status",
    "urgent only": "urgent_only_on",
    "urgent-only": "urgent_only_on",
    "quiet briefs": "urgent_only_on",
    "normal briefs": "urgent_only_off",
    "all briefs": "urgent_only_off",
}


async def _execute_routine_step(step: str) -> str:
    low = step.lower()
    for phrase, action in ROUTINE_HUB_KEYWORDS.items():
        if phrase in low:
            if action == "printer_status":
                state = await print_preflight()
                return f"Printer status: {state.get('reason') or 'camera watching, ' + state['camera']}"
            if action == "urgent_only_on":
                _brief_mode["urgent_only"] = True
                return "Briefs set to urgent-only."
            if action == "urgent_only_off":
                _brief_mode["urgent_only"] = False
                return "Briefs back to normal."
    return f"Not hub-actionable, pass to app/HA: {step}"


async def _run_routine(name: str) -> dict:
    with hub_db() as conn:
        row = conn.execute("SELECT * FROM routines WHERE name = ?", (name,)).fetchone()
    if row is None:
        raise HTTPException(status_code=404, detail=f"no routine named '{name}'")
    steps = json.loads(row["steps"])
    results = [await _execute_routine_step(s) for s in steps]
    return {"name": name, "results": results}


@app.post("/atlas/routines", dependencies=[Depends(require_token)])
def save_routine(r: Routine):
    with hub_db() as conn:
        conn.execute("DELETE FROM routines WHERE name = ?", (r.name,))
        conn.execute(
            "INSERT INTO routines (name, steps, created_at) VALUES (?, ?, ?)",
            (r.name, json.dumps(r.steps), _now_ms()),
        )
        conn.commit()
    return {"ok": True}


@app.get("/atlas/routines", dependencies=[Depends(require_token)])
def list_routines():
    with hub_db() as conn:
        rows = conn.execute("SELECT * FROM routines ORDER BY name ASC").fetchall()
    return {"routines": [{"name": r["name"], "steps": json.loads(r["steps"])} for r in rows]}


@app.post("/atlas/routines/run", dependencies=[Depends(require_token)])
async def run_routine(r: RoutineRun):
    return await _run_routine(r.name)


@app.post("/atlas/brief/urgent-only", dependencies=[Depends(require_token)])
def set_urgent_only_mode(on: bool = True):
    _brief_mode["urgent_only"] = on
    return {"urgent_only": _brief_mode["urgent_only"]}


async def _compose_departure_check() -> dict:
    # Same fusion/flag split as Leave-Home (#29): calendar, weather, traffic
    # and car status are all app-side (phone GPS/calendar, no ClearDash/OBD
    # reachable here) -- flagged explicitly, never faked. In particular,
    # never invent a travel-time call; this hub has no traffic data at all.
    print_state = await print_preflight()
    queued = [j for j in _read_print_queue() if j["status"] == "queued"]
    with hub_db() as conn:
        urgent_rows = conn.execute(
            "SELECT * FROM reports WHERE priority = 'urgent' AND told = 0 ORDER BY at DESC"
        ).fetchall()

    checklist = []
    exceptions = []

    if print_state.get("ok"):
        checklist.append(f"Print area camera ({print_state['camera']}): watching")
    else:
        exceptions.append(f"Print area: {print_state.get('reason', 'not available')}")

    if queued:
        checklist.append(f"Print queue: {len(queued)} job(s) waiting")

    if urgent_rows:
        exceptions.append(
            f"{len(urgent_rows)} unacked urgent item(s): " + "; ".join(r["title"] for r in urgent_rows[:3])
        )
    else:
        checklist.append("No unacked urgent items")

    exceptions.append(
        "Calendar/weather/traffic/car status: not reachable from this hub -- the app supplies those for the final call"
    )

    reply = await _chat(
        [
            {
                "role": "system",
                "content": (
                    "Compose a short 'clear to go' style departure check from the checklist "
                    "and flagged items given. Lead with 'Clear to go' if nothing in the "
                    "checklist is a problem, otherwise name the exception. Do not invent a "
                    "travel-time recommendation -- you have no traffic data. Plain spoken "
                    "English, one short sentence, no markdown."
                ),
            },
            {
                "role": "user",
                "content": "Checklist:\n" + "\n".join(checklist) + "\n\nFlagged:\n" + "\n".join(exceptions),
            },
        ],
        max_tokens=400,
        timeout_s=30,
    )
    if not reply:
        reply = (
            "Clear to go."
            if not urgent_rows and print_state.get("ok", True)
            else "Check before you go: " + "; ".join(exceptions[:2])
        )
    return {"summary": reply, "checklist": checklist, "exceptions": exceptions}


@app.get("/atlas/departure/check", dependencies=[Depends(require_token)])
async def departure_check():
    return await _compose_departure_check()


async def _fire_trigger(row: sqlite3.Row) -> None:
    await report(
        Report(app="trigger", title=row["reminder"], body=f"Triggered by: {row['condition']}", key=None)
    )
    with hub_db() as conn:
        conn.execute("UPDATE place_triggers SET fired_at = ? WHERE id = ?", (_now_ms(), row["id"]))
        conn.commit()


async def _check_place_triggers() -> dict:
    with hub_db() as conn:
        pending = conn.execute("SELECT * FROM place_triggers WHERE fired_at IS NULL").fetchall()
    fired = 0
    printer_idle = None
    for row in pending:
        cond = row["condition"].lower()
        if "printer" in cond and any(w in cond for w in ("idle", "empty", "done", "free")):
            if printer_idle is None:
                printer_idle = not any(j["status"] == "queued" for j in _read_print_queue())
            if printer_idle:
                await _fire_trigger(row)
                fired += 1
    return {"checked": len(pending), "fired": fired}


async def _fire_triggers_for_event(event: str) -> dict:
    event_low = event.strip().lower()
    with hub_db() as conn:
        pending = conn.execute("SELECT * FROM place_triggers WHERE fired_at IS NULL").fetchall()
    fired = 0
    for row in pending:
        if event_low and event_low in row["condition"].lower():
            await _fire_trigger(row)
            fired += 1
    return {"checked": len(pending), "fired": fired, "event": event}


async def _place_trigger_loop():
    while True:
        await asyncio.sleep(PLACE_TRIGGER_CHECK_INTERVAL_S)
        try:
            await _check_place_triggers()
        except Exception:
            pass


def _schedule_place_triggers():
    global _place_trigger_task
    if _place_trigger_task is not None and not _place_trigger_task.done():
        return
    try:
        loop = asyncio.get_running_loop()
    except RuntimeError:
        return
    _place_trigger_task = loop.create_task(_place_trigger_loop())


@app.post("/atlas/triggers", dependencies=[Depends(require_token)])
def create_trigger(t: PlaceTrigger):
    with hub_db() as conn:
        cur = conn.execute(
            "INSERT INTO place_triggers (condition, reminder, created_at) VALUES (?, ?, ?)",
            (t.condition, t.reminder, _now_ms()),
        )
        conn.commit()
        trigger_id = cur.lastrowid
    return {"id": trigger_id}


@app.get("/atlas/triggers", dependencies=[Depends(require_token)])
def list_triggers(include_fired: bool = False):
    with hub_db() as conn:
        if include_fired:
            rows = conn.execute("SELECT * FROM place_triggers ORDER BY created_at DESC").fetchall()
        else:
            rows = conn.execute(
                "SELECT * FROM place_triggers WHERE fired_at IS NULL ORDER BY created_at ASC"
            ).fetchall()
    return {"triggers": [dict(r) for r in rows]}


@app.post("/atlas/triggers/check", dependencies=[Depends(require_token)])
async def triggers_check():
    return await _check_place_triggers()


@app.post("/atlas/triggers/event", dependencies=[Depends(require_token)])
async def triggers_event(e: PlaceTriggerEvent):
    return await _fire_triggers_for_event(e.event)


@app.post("/atlas/chronicle/note", dependencies=[Depends(require_token)])
def chronicle_note(n: ChronicleNote):
    session_id = n.session_id or datetime.now().strftime("%Y-%m-%d")
    with hub_db() as conn:
        cur = conn.execute(
            "INSERT INTO chronicle_notes (session_id, text, kind, created_at) VALUES (?, ?, ?, ?)",
            (session_id, n.text, n.kind, _now_ms()),
        )
        conn.commit()
        note_id = cur.lastrowid
    return {"id": note_id, "session_id": session_id}


@app.get("/atlas/chronicle/notes", dependencies=[Depends(require_token)])
def chronicle_notes(session_id: str):
    with hub_db() as conn:
        rows = conn.execute(
            "SELECT * FROM chronicle_notes WHERE session_id = ? ORDER BY created_at ASC", (session_id,)
        ).fetchall()
    return {"notes": [dict(r) for r in rows]}


@app.get("/atlas/chronicle/sessions", dependencies=[Depends(require_token)])
def chronicle_sessions():
    with hub_db() as conn:
        rows = conn.execute(
            "SELECT session_id, COUNT(*) AS note_count, MAX(created_at) AS last_at "
            "FROM chronicle_notes GROUP BY session_id ORDER BY last_at DESC"
        ).fetchall()
    return {"sessions": [dict(r) for r in rows]}


async def _compose_chronicle_recap(session_id: str) -> dict:
    with hub_db() as conn:
        rows = conn.execute(
            "SELECT * FROM chronicle_notes WHERE session_id = ? ORDER BY created_at ASC", (session_id,)
        ).fetchall()
    if not rows:
        return {"session_id": session_id, "recap": "No notes captured for that session.", "note_count": 0}
    notes_text = "\n".join(f"- [{r['kind']}] {r['text']}" for r in rows)
    reply = await _chat(
        [
            {
                "role": "system",
                "content": (
                    "Write a short D&D session recap from these whispered voice notes (loot, "
                    "names, rulings). Plain spoken English, at most 150 words, no markdown, "
                    "no lists -- tell it like a story beat, not a log."
                ),
            },
            {"role": "user", "content": notes_text},
        ],
        max_tokens=800,
        timeout_s=60,
    )
    recap = reply if reply else "; ".join(f"[{r['kind']}] {r['text']}" for r in rows[:10])
    recap = " ".join(recap.split()[:CHRONICLE_MAX_WORDS])
    now_ms = _now_ms()
    with hub_db() as conn:
        conn.execute(
            "INSERT INTO chronicle_recaps (session_id, recap, composed_at) VALUES (?, ?, ?) "
            "ON CONFLICT(session_id) DO UPDATE SET recap = excluded.recap, composed_at = excluded.composed_at",
            (session_id, recap, now_ms),
        )
        conn.commit()
    return {"session_id": session_id, "recap": recap, "note_count": len(rows)}


@app.post("/atlas/chronicle/recap", dependencies=[Depends(require_token)])
async def chronicle_recap(r: ChronicleRecapRequest):
    return await _compose_chronicle_recap(r.session_id)


async def _compose_chronicle_opener(exclude_session_id: Optional[str] = None) -> dict:
    with hub_db() as conn:
        if exclude_session_id:
            row = conn.execute(
                "SELECT * FROM chronicle_recaps WHERE session_id != ? ORDER BY composed_at DESC LIMIT 1",
                (exclude_session_id,),
            ).fetchone()
        else:
            row = conn.execute("SELECT * FROM chronicle_recaps ORDER BY composed_at DESC LIMIT 1").fetchone()
    if row is None:
        return {"opener": "No previous session recap to open with.", "session_id": None}
    reply = await _chat(
        [
            {
                "role": "system",
                "content": (
                    "Turn this D&D session recap into a short 'Last time on...' opening line "
                    "for the next session. Plain spoken English, at most 50 words, no markdown."
                ),
            },
            {"role": "user", "content": row["recap"]},
        ],
        max_tokens=400,
        timeout_s=30,
    )
    opener = reply if reply else f"Last time on {row['session_id']}: {row['recap']}"
    return {"opener": opener, "session_id": row["session_id"]}


@app.get("/atlas/chronicle/opener", dependencies=[Depends(require_token)])
async def chronicle_opener(exclude_session_id: Optional[str] = None):
    return await _compose_chronicle_opener(exclude_session_id)


def _get_setting(key: str, default: str = "false") -> str:
    with hub_db() as conn:
        row = conn.execute("SELECT value FROM hub_settings WHERE key = ?", (key,)).fetchone()
    return row["value"] if row else default


def _set_setting(key: str, value: str) -> None:
    with hub_db() as conn:
        conn.execute(
            "INSERT INTO hub_settings (key, value) VALUES (?, ?) "
            "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            (key, value),
        )
        conn.commit()


def _care_settings() -> dict:
    return {
        "movement_enabled": _get_setting("care_movement_enabled") == "true",
        "weighin_enabled": _get_setting("care_weighin_enabled") == "true",
    }


@app.get("/atlas/care/settings", dependencies=[Depends(require_token)])
def get_care_settings():
    return _care_settings()


@app.post("/atlas/care/settings", dependencies=[Depends(require_token)])
def set_care_settings(movement_enabled: Optional[bool] = None, weighin_enabled: Optional[bool] = None):
    if movement_enabled is not None:
        _set_setting("care_movement_enabled", "true" if movement_enabled else "false")
    if weighin_enabled is not None:
        _set_setting("care_weighin_enabled", "true" if weighin_enabled else "false")
    return _care_settings()


async def _maybe_movement_nudge() -> dict:
    if not _care_settings()["movement_enabled"]:
        return {"nudged": False, "reason": "opted out"}
    now_ms = _now_ms()
    if now_ms - _care_state["last_movement_nudge_at"] < CARE_MOVEMENT_COOLDOWN_S * 1000:
        return {"nudged": False, "reason": "cooldown"}
    await report(
        Report(
            app="care",
            title="Stretch break",
            body="Been at it a while -- maybe stand up and stretch for a minute. No worries if not.",
            priority="low",
            key=None,
        )
    )
    _care_state["last_movement_nudge_at"] = now_ms
    return {"nudged": True}


@app.post("/atlas/care/sedentary", dependencies=[Depends(require_token)])
async def care_sedentary():
    return await _maybe_movement_nudge()


@app.post("/atlas/care/weigh-in", dependencies=[Depends(require_token)])
def log_weigh_in(w: WeighIn):
    with hub_db() as conn:
        cur = conn.execute("INSERT INTO weigh_ins (weight, logged_at) VALUES (?, ?)", (w.weight, _now_ms()))
        conn.commit()
        weigh_in_id = cur.lastrowid
    return {"id": weigh_in_id}


@app.get("/atlas/care/weigh-ins", dependencies=[Depends(require_token)])
def list_weigh_ins(limit: int = 10):
    with hub_db() as conn:
        rows = conn.execute("SELECT * FROM weigh_ins ORDER BY logged_at DESC LIMIT ?", (limit,)).fetchall()
    return {"weigh_ins": [dict(r) for r in rows]}


async def _frigate_get(path: str, timeout_s: float = 5) -> Optional[dict]:
    """Calls Frigate's read-only API. Returns None (never raises) if it's down."""
    try:
        async with httpx.AsyncClient(timeout=timeout_s) as client:
            resp = await client.get(f"{FRIGATE_URL.rstrip('/')}{path}")
            resp.raise_for_status()
            return resp.json()
    except Exception:
        return None


@app.get("/atlas/frigate/cameras", dependencies=[Depends(require_token)])
async def frigate_cameras():
    config = await _frigate_get("/api/config")
    if config is None:
        return {"ok": False, "cameras": []}
    cameras = [
        {"name": name, "enabled": bool(cfg.get("enabled", False))}
        for name, cfg in config.get("cameras", {}).items()
    ]
    return {"ok": True, "cameras": cameras}


@app.get("/atlas/print/preflight", dependencies=[Depends(require_token)])
async def print_preflight():
    config = await _frigate_get("/api/config")
    if config is None:
        return {"ok": False, "camera": PRINT_CAMERA, "reason": "Frigate isn't answering"}
    camera_cfg = config.get("cameras", {}).get(PRINT_CAMERA)
    if camera_cfg is None:
        return {"ok": False, "camera": PRINT_CAMERA, "reason": f"no camera named '{PRINT_CAMERA}' in Frigate"}
    if not camera_cfg.get("enabled", False):
        return {"ok": False, "camera": PRINT_CAMERA, "reason": "camera exists but is disabled in Frigate"}
    return {
        "ok": True,
        "camera": PRINT_CAMERA,
        "snapshot_url": f"{FRIGATE_URL.rstrip('/')}/api/{PRINT_CAMERA}/latest.jpg",
    }


async def _fetch_frigate_snapshot(camera: str, timeout_s: float = 10) -> Optional[bytes]:
    try:
        async with httpx.AsyncClient(timeout=timeout_s) as client:
            resp = await client.get(f"{FRIGATE_URL.rstrip('/')}/api/{camera}/latest.jpg")
            resp.raise_for_status()
            return resp.content
    except Exception:
        return None


async def _vision_chat(image_bytes: bytes, question: str, timeout_s: float = VISION_TIMEOUT_S) -> Optional[str]:
    """Calls the local vision model with one image. Returns None (never raises) if it's down."""
    try:
        b64 = base64.b64encode(image_bytes).decode()
        async with httpx.AsyncClient(timeout=timeout_s) as client:
            resp = await client.post(
                f"{LLM_URL.rstrip('/')}/chat/completions",
                json={
                    "model": VISION_MODEL,
                    "messages": [
                        {
                            "role": "user",
                            "content": [
                                {
                                    "type": "text",
                                    "text": f"/no_think {question} Answer in one short plain sentence.",
                                },
                                {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{b64}"}},
                            ],
                        }
                    ],
                    "max_tokens": 300,
                },
            )
            resp.raise_for_status()
            content = resp.json()["choices"][0]["message"]["content"].strip()
            return content or None
    except Exception:
        return None


async def _ask_camera(camera: str, question: str) -> dict:
    image = await _fetch_frigate_snapshot(camera)
    if image is None:
        return {"answer": f"Couldn't get a snapshot from '{camera}' right now.", "camera": camera}
    reply = await _vision_chat(image, question)
    if not reply:
        return {"answer": "The vision model isn't answering right now.", "camera": camera}
    return {"answer": " ".join(reply.split()[:ASK_MAX_WORDS]), "camera": camera}


@app.post("/atlas/camera/ask", dependencies=[Depends(require_token)])
async def camera_ask(a: CameraAsk):
    return await _ask_camera(a.camera, a.question)


@app.post("/atlas/print/bed-clear", dependencies=[Depends(require_token)])
async def print_bed_clear():
    return await _ask_camera(
        PRINT_CAMERA,
        "Is the print bed/build plate visible and clear, or is there a finished or failed print still on it?",
    )


@app.post("/atlas/camera/find", dependencies=[Depends(require_token)])
async def camera_find(f: CameraFind):
    config = await _frigate_get("/api/config")
    cameras = [name for name, cfg in (config or {}).get("cameras", {}).items() if cfg.get("enabled")]
    for camera in cameras:
        result = await _ask_camera(camera, f"Does this image show: {f.question}? Start your answer with YES or NO.")
        if result["answer"].strip().upper().startswith("YES"):
            return {"found": True, "camera": camera, "answer": result["answer"]}
    return {"found": False, "camera": None, "answer": "Didn't spot that on any camera right now."}


async def _check_print_camera() -> dict:
    config = await _frigate_get("/api/config")
    camera_cfg = (config or {}).get("cameras", {}).get(PRINT_CAMERA)
    ok = bool(camera_cfg and camera_cfg.get("enabled"))
    last_ok = _print_watch_state.get("last_ok")
    if last_ok is not None and ok != last_ok:
        if ok:
            title, body = (
                "Print camera back online",
                f"{PRINT_CAMERA} is reachable and enabled again.",
            )
        else:
            title, body = (
                "Print camera offline",
                f"{PRINT_CAMERA} stopped answering or is disabled — the print area isn't being watched.",
            )
        await report(
            Report(app="print", title=title, body=body, priority="normal", key="print-camera-status")
        )
    _print_watch_state["last_ok"] = ok
    return {"camera": PRINT_CAMERA, "ok": ok}


async def _print_watch_loop():
    while True:
        await asyncio.sleep(PRINT_WATCH_INTERVAL_S)
        try:
            await _check_print_camera()
        except Exception:
            pass


def _schedule_print_watch():
    global _print_watch_task
    if _print_watch_task is not None and not _print_watch_task.done():
        return
    try:
        loop = asyncio.get_running_loop()
    except RuntimeError:
        return
    _print_watch_task = loop.create_task(_print_watch_loop())


@app.post("/atlas/print/watch-check", dependencies=[Depends(require_token)])
async def print_watch_check():
    return await _check_print_camera()


async def _check_gatekeeper() -> dict:
    after = _gate_watermark["after"]
    if after is None:
        # First-ever check: start watching from now, don't backfill Frigate's
        # whole history as a flood of "someone was just at your door" reports.
        _gate_watermark["after"] = time.time()
        return {"checked": 0, "filed": 0}
    newest = after
    checked = 0
    filed = 0
    for camera in GATE_CAMERAS:
        events = await _frigate_get(f"/api/events?cameras={camera}&labels=person&after={after}&limit=20") or []
        for event in events:
            checked += 1
            start = event.get("start_time") or after
            newest = max(newest, start)
            when = datetime.fromtimestamp(start).strftime("%-I:%M %p")
            await report(
                Report(
                    app="gatekeeper",
                    title=f"Someone at the {camera.replace('_', ' ')}",
                    body=f"Detected at {when}",
                    priority="normal",
                    key=f"gatekeeper:{event.get('id')}",
                )
            )
            filed += 1
    if newest > after:
        _gate_watermark["after"] = newest + 0.001
    return {"checked": checked, "filed": filed}


async def _gatekeeper_loop():
    while True:
        await asyncio.sleep(GATE_CHECK_INTERVAL_S)
        try:
            await _check_gatekeeper()
        except Exception:
            pass


def _schedule_gatekeeper():
    global _gate_task
    if _gate_task is not None and not _gate_task.done():
        return
    try:
        loop = asyncio.get_running_loop()
    except RuntimeError:
        return
    _gate_task = loop.create_task(_gatekeeper_loop())


@app.post("/atlas/gatekeeper/check", dependencies=[Depends(require_token)])
async def gatekeeper_check():
    return await _check_gatekeeper()


def _parse_filament_line(line: str) -> Optional[dict]:
    line = line.strip()
    if not line.startswith("-"):
        return None
    parts = [p.strip() for p in line[1:].split("|")]
    if len(parts) < 4 or not parts[0]:
        return None
    material, color, remaining_raw, total_raw = parts[0], parts[1], parts[2], parts[3]
    try:
        remaining_g = float(remaining_raw)
        total_g = float(total_raw)
    except ValueError:
        return None
    return {"material": material, "color": color, "remaining_g": remaining_g, "total_g": total_g}


def _read_filament_spools() -> list[dict]:
    if not FILAMENT_FILE.exists():
        return []
    spools = []
    for line in FILAMENT_FILE.read_text(encoding="utf-8", errors="ignore").splitlines():
        parsed = _parse_filament_line(line)
        if parsed:
            spools.append(parsed)
    return spools


def _parse_queue_line(line: str) -> Optional[dict]:
    line = line.strip()
    if not line.startswith("-"):
        return None
    parts = [p.strip() for p in line[1:].split("|")]
    if len(parts) < 4 or not parts[0]:
        return None
    name, material, grams_raw, status = parts[0], parts[1], parts[2], parts[3]
    try:
        estimated_g = float(grams_raw)
    except ValueError:
        return None
    return {"name": name, "material": material, "estimated_g": estimated_g, "status": status.strip().lower()}


def _read_print_queue() -> list[dict]:
    if not QUEUE_FILE.exists():
        return []
    jobs = []
    for line in QUEUE_FILE.read_text(encoding="utf-8", errors="ignore").splitlines():
        parsed = _parse_queue_line(line)
        if parsed:
            jobs.append(parsed)
    return jobs


async def _check_filament_for_queue() -> dict:
    remaining_by_material: dict[str, float] = {}
    for s in _read_filament_spools():
        remaining_by_material[s["material"]] = remaining_by_material.get(s["material"], 0) + s["remaining_g"]
    needed_by_material: dict[str, float] = {}
    for j in _read_print_queue():
        if j["status"] == "queued":
            needed_by_material[j["material"]] = needed_by_material.get(j["material"], 0) + j["estimated_g"]
    filed = 0
    for material, needed_g in needed_by_material.items():
        available_g = remaining_by_material.get(material, 0)
        if needed_g > available_g:
            short_by = needed_g - available_g
            await report(
                Report(
                    app="filament",
                    title=f"Not enough {material} for the queue",
                    body=f"Queue needs {needed_g:.0f}g, {available_g:.0f}g on hand, short by {short_by:.0f}g",
                    priority="normal",
                    key=f"filament-short:{material}",
                )
            )
            filed += 1
    return {"materials_checked": len(needed_by_material), "filed": filed}


async def _filament_nightly_loop():
    while True:
        await asyncio.sleep(_seconds_until_next_hour(FILAMENT_CHECK_HOUR))
        try:
            await _check_filament_for_queue()
        except Exception:
            pass


def _schedule_filament_check():
    global _filament_task
    if _filament_task is not None and not _filament_task.done():
        return
    try:
        loop = asyncio.get_running_loop()
    except RuntimeError:
        return
    _filament_task = loop.create_task(_filament_nightly_loop())


@app.get("/atlas/filament/spools", dependencies=[Depends(require_token)])
def filament_spools():
    return {"spools": _read_filament_spools()}


@app.get("/atlas/print/queue", dependencies=[Depends(require_token)])
def print_queue():
    return {"queue": _read_print_queue()}


@app.post("/atlas/filament/check", dependencies=[Depends(require_token)])
async def filament_check():
    return await _check_filament_for_queue()


def _parse_slicer_line(line: str) -> Optional[tuple]:
    line = line.strip()
    if not line.startswith("-"):
        return None
    parts = [p.strip() for p in line[1:].split("|")]
    if len(parts) < 2 or not parts[0]:
        return None
    try:
        return (parts[0], float(parts[1]))
    except ValueError:
        return None


def _read_slicer_settings() -> dict:
    settings = dict(DEFAULT_SLICER_SETTINGS)
    if SLICER_PROFILE_FILE.exists():
        for line in SLICER_PROFILE_FILE.read_text(encoding="utf-8", errors="ignore").splitlines():
            parsed = _parse_slicer_line(line)
            if parsed:
                settings[parsed[0]] = parsed[1]
    return settings


def _write_slicer_settings(settings: dict) -> None:
    SLICER_PROFILE_FILE.parent.mkdir(parents=True, exist_ok=True)
    lines = ["# Atlas Conversational Slicer Tuning -- current profile (hub-managed)", ""]
    lines.extend(f"- {name} | {value}" for name, value in settings.items())
    SLICER_PROFILE_FILE.write_text("\n".join(lines) + "\n", encoding="utf-8")


def _fallback_slicer_tweak(symptom: str, current: dict) -> tuple:
    low = symptom.lower()
    if "string" in low:
        return "retraction_distance_mm", current.get("retraction_distance_mm", 0.8) + 0.2, (
            "Increased retraction distance slightly to reduce stringing."
        )
    if "warp" in low:
        return "nozzle_temp_c", current.get("nozzle_temp_c", 200.0) - 5, (
            "Lowered nozzle temp slightly to reduce warping."
        )
    return None, None, None


async def _tune_slicer(symptom: str) -> dict:
    current = _read_slicer_settings()
    settings_text = "\n".join(f"- {k}: {v}" for k, v in current.items())
    reply = await _chat(
        [
            {
                "role": "system",
                "content": (
                    "You tune FDM 3D-printer slicer settings. Given the current settings and "
                    "a reported print symptom, propose exactly one targeted change. Reply as "
                    "exactly two lines: 'CHANGE: <setting>=<value>' then 'WHY: <reason>', "
                    "using one of these setting names: "
                    + ", ".join(DEFAULT_SLICER_SETTINGS)
                    + ". Keep changes modest."
                ),
            },
            {"role": "user", "content": f"Current settings:\n{settings_text}\n\nSymptom: {symptom}"},
        ],
        max_tokens=400,
        timeout_s=30,
    )
    setting = value = reason = None
    if reply:
        for line in reply.splitlines():
            if line.upper().startswith("CHANGE:") and "=" in line:
                name, _, val = line.split(":", 1)[1].partition("=")
                try:
                    if name.strip() in current:
                        setting, value = name.strip(), float(val.strip())
                except ValueError:
                    pass
            elif line.upper().startswith("WHY:"):
                reason = line.split(":", 1)[1].strip()
    if setting is None:
        setting, value, fallback_reason = _fallback_slicer_tweak(symptom, current)
        reason = reason or fallback_reason
    if setting is None:
        return {"changed": False, "reason": "No specific tweak suggested for that symptom."}
    lo, hi = SLICER_SETTING_BOUNDS.get(setting, (float("-inf"), float("inf")))
    value = max(lo, min(hi, value))
    old_value = current.get(setting)
    current[setting] = value
    _write_slicer_settings(current)
    await report(
        Report(
            app="slicer",
            title=f"Tuned {setting}",
            body=f"{old_value} -> {value}. {reason or ''}".strip(),
            priority="normal",
            key=None,
        )
    )
    return {"changed": True, "setting": setting, "old_value": old_value, "new_value": value, "reason": reason}


@app.get("/atlas/slicer/profile", dependencies=[Depends(require_token)])
def slicer_profile():
    return {"settings": _read_slicer_settings()}


@app.post("/atlas/slicer/tune", dependencies=[Depends(require_token)])
async def slicer_tune(t: SlicerTune):
    return await _tune_slicer(t.symptom)


@app.post("/atlas/ask", dependencies=[Depends(require_token)])
async def ask(a: Ask):
    with hub_db() as conn:
        rows = conn.execute("SELECT * FROM reports ORDER BY at DESC LIMIT 200").fetchall()
    now_ms = _now_ms()
    reports_text = "\n".join(
        f"- [{_age_str(r['at'], now_ms)}] [{r['app']}] {r['title']}: {r['body']}".strip() for r in rows
    )
    vault_hits = _search_vault(a.question, limit=6)
    vault_text = "\n".join(vault_hits)
    reply = await _chat(
        [
            {
                "role": "system",
                "content": (
                    "Answer the question using only the reports and notes given. Plain spoken "
                    "English, at most 80 words. If you don't know, say so briefly."
                ),
            },
            {
                "role": "user",
                "content": f"Question: {a.question}\n\nReports:\n{reports_text}\n\nNotes:\n{vault_text}",
            },
        ],
        max_tokens=800,
        timeout_s=60,
    )
    if not reply:
        matches = [r for r in rows if a.question.lower() in (r["title"] + " " + r["body"]).lower()]
        pool = matches or rows
        reply = _fallback_brief(pool[:5]) if pool else "I don't have anything on that."
    words = reply.split()
    return {"answer": " ".join(words[:ASK_MAX_WORDS])}


@app.post("/atlas/ask-vault", dependencies=[Depends(require_token)])
async def ask_vault(a: Ask):
    # Unlike /atlas/ask, this never looks at the reports table — it answers
    # strictly from the Obsidian vault, with each excerpt labelled by the
    # note it came from so the model (and the person) can see the source.
    hits = _search_vault_hits(a.question, limit=8)
    vault_text = "\n".join(f"[{h['note']}] {h['line']}" for h in hits)
    reply = await _chat(
        [
            {
                "role": "system",
                "content": (
                    "Answer the question using only the vault excerpts given, each labelled "
                    "with the note it's from. Mention which note an answer came from when it's "
                    "natural to. Plain spoken English, at most 80 words. If nothing in the "
                    "excerpts answers it, say so briefly rather than guessing."
                ),
            },
            {
                "role": "user",
                "content": f"Question: {a.question}\n\nVault excerpts:\n{vault_text or '(no matching notes)'}",
            },
        ],
        max_tokens=800,
        timeout_s=60,
    )
    if not reply:
        reply = (
            "; ".join(f"from {h['note']}: {h['line']}" for h in hits[:3])
            if hits
            else "I couldn't find anything in the vault about that."
        )
    words = reply.split()
    return {"answer": " ".join(words[:ASK_MAX_WORDS]), "notes": sorted({h["note"] for h in hits})}


DELEGATE_TIMEOUT_S = 240
HERMES_BIN = os.environ.get("ATLAS_HERMES_BIN", str(Path.home() / ".local/bin/hermes"))


class Delegate(BaseModel):
    task: str


async def _run_hermes_oneshot(task: str) -> str:
    """Runs `hermes -z <task>` one-shot and returns its stdout on success.
    Raises HTTPException(502) when Hermes is missing, times out, or exits
    non-zero (fix #5) — a failure must never be spoken as Hermes's answer.
    Hermes is a CLI agent, not a standing server, so a subprocess is the
    straightforward way to hand it a job and get an answer."""
    try:
        proc = await asyncio.create_subprocess_exec(
            HERMES_BIN,
            "-z",
            task,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        try:
            stdout, stderr = await asyncio.wait_for(proc.communicate(), timeout=DELEGATE_TIMEOUT_S)
        except asyncio.TimeoutError:
            proc.kill()
            await proc.wait()
            raise HTTPException(status_code=502, detail="Hermes didn't answer in time.")
        if proc.returncode != 0:
            detail = stderr.decode(errors="ignore").strip() or stdout.decode(errors="ignore").strip()
            raise HTTPException(status_code=502, detail=f"Hermes couldn't run that: {detail[:200]}")
        answer = stdout.decode(errors="ignore").strip()
        if not answer:
            raise HTTPException(status_code=502, detail="Hermes didn't say anything back.")
        return answer
    except FileNotFoundError:
        raise HTTPException(status_code=502, detail="Hermes isn't installed on this node.")


@app.post("/atlas/delegate", dependencies=[Depends(require_token)])
async def delegate(d: Delegate):
    # Fix #5: _run_hermes_oneshot raises 502 on any Hermes failure, so the
    # report below is filed only on success and failures surface as errors.
    answer = await _run_hermes_oneshot(d.task)
    await report(Report(app="Hermes", title=d.task[:120], body=answer, key=None))
    return {"answer": answer}



# ---------------------------------------------------------------------------
# Athena channel: ATLAS talks to Athena (Jon's Windows desktop) over the SSH
# Jon set up from Obelisk. Exec follows the /atlas/delegate pattern; status
# is cached and refreshed in the background like the brief, so the app never
# waits on SSH to paint a screen.
# ---------------------------------------------------------------------------

ATHENA_HOST = os.environ.get("ATLAS_ATHENA_HOST", "user@athena.tail8dab0b.ts.net")
ATHENA_EXEC_TIMEOUT_S = 60
ATHENA_STATUS_TIMEOUT_S = 30
ATHENA_STATUS_TTL_S = 60
ATHENA_MAX_OUTPUT = 20000

_athena_cache = {"containers": [], "uptime": "", "disk": "", "updated": 0}
_athena_task: Optional[asyncio.Task] = None

# Container CPR (#19): detect-and-report only, never an automatic restart —
# Athena already runs autoheal for actual healing on containers with health
# checks; the hub's job is proactively telling Jon/ATLAS when something
# needed attention, healed or not. The one mutating action (restart) stays
# an explicit, name-validated endpoint for a person to trigger, not
# something this watch calls on its own.
ATHENA_CONTAINER_CHECK_INTERVAL_S = int(os.environ.get("ATLAS_ATHENA_CONTAINER_CHECK_INTERVAL_S", "300"))
_CONTAINER_NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,62}$")
_container_health_state: dict[str, bool] = {}  # name -> is_down
_athena_container_task: Optional[asyncio.Task] = None

# Gatekeeper's Digest (#3): reuses Frigate's existing, already-live person
# detection on the real entry cameras. No separate composition machinery —
# each sighting becomes a normal hub report under app "gatekeeper" and
# rides the existing brief/digest pipeline like any other reporter.
GATE_CAMERAS = [c.strip() for c in os.environ.get("ATLAS_GATE_CAMERAS", "front_porch,back_deck").split(",") if c.strip()]
GATE_CHECK_INTERVAL_S = int(os.environ.get("ATLAS_GATE_CHECK_INTERVAL_S", "300"))
_gate_watermark = {"after": None}  # None = not started yet; first check only sets the baseline
_gate_task: Optional[asyncio.Task] = None


def require_athena_token(authorization: Optional[str] = Header(default=None)):
    """Stricter than require_token: an exec channel stays closed until the
    hub has ATLAS_TOKEN set AND the caller presents it. Other endpoints may
    run tokenless on the tailnet; this one may not (spec is non-negotiable).
    The token itself is never logged."""
    if not TOKEN:
        raise HTTPException(status_code=401, detail="ATLAS_TOKEN is not set on the hub; the Athena channel is disabled until it is.")
    if authorization != f"Bearer {TOKEN}":
        raise HTTPException(status_code=401, detail="bad or missing bearer token")


class AthenaExec(BaseModel):
    command: str
    timeout_s: Optional[int] = None


async def _run_athena_ssh(command: str, timeout_s: int) -> tuple[int, str, str]:
    """Runs one command on Athena via ssh (BatchMode: fails fast, never
    prompts). Returns (exit, stdout, stderr), each output truncated.
    Raises HTTPException(502) only when SSH itself cannot run/time out --
    a remote command that exits non-zero is a result, not a hub error."""
    try:
        proc = await asyncio.create_subprocess_exec(
            "ssh",
            "-o", "BatchMode=yes",
            "-o", "ConnectTimeout=10",
            ATHENA_HOST,
            command,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        try:
            stdout, stderr = await asyncio.wait_for(proc.communicate(), timeout=timeout_s)
        except asyncio.TimeoutError:
            proc.kill()
            await proc.wait()
            raise HTTPException(status_code=502, detail="Athena didn't answer in time.")
        out = stdout.decode(errors="ignore")[:ATHENA_MAX_OUTPUT]
        err = stderr.decode(errors="ignore")[:ATHENA_MAX_OUTPUT]
        return proc.returncode if proc.returncode is not None else -1, out, err
    except FileNotFoundError:
        raise HTTPException(status_code=502, detail="ssh isn't installed on this node.")


@app.post("/atlas/athena", dependencies=[Depends(require_athena_token)])
async def athena_exec(a: AthenaExec):
    command = a.command.strip()
    if not command:
        raise HTTPException(status_code=400, detail="empty command")
    timeout_s = a.timeout_s or ATHENA_EXEC_TIMEOUT_S
    timeout_s = max(5, min(int(timeout_s), 120))
    exit_code, out, err = await _run_athena_ssh(command, timeout_s)
    # Every completed exec is filed as a hub report (app athena) so it shows
    # in the brief/audit trail. Filing must never break the exec result.
    try:
        body = (out or err).strip()[:500]
        await report(Report(app="athena", title=f"Athena exec: {command[:80]}",
                            body=body or f"exit {exit_code}", key=None))
    except Exception:
        pass
    return {"ok": exit_code == 0, "exit": exit_code, "stdout": out, "stderr": err}


def _parse_athena_status(docker_out: str, uptime_out: str, disk_out: str) -> dict:
    containers = []
    for line in docker_out.splitlines():
        line = line.strip()
        if not line or "|" not in line:
            continue
        parts = line.split("|")
        name = parts[0].strip()
        status = parts[1].strip() if len(parts) > 1 else ""
        ports = parts[2].strip() if len(parts) > 2 else ""
        if name:
            containers.append({"name": name, "status": status, "ports": ports})
    uptime = " ".join(uptime_out.split())[:200]
    disk = " ".join(disk_out.split())[:200]
    return {"containers": containers, "uptime": uptime, "disk": disk}


async def _refresh_athena_status():
    docker_out = uptime_out = disk_out = ""
    try:
        code, docker_out, _ = await _run_athena_ssh(
            'docker ps --format "{{.Names}}|{{.Status}}|{{.Ports}}"', ATHENA_STATUS_TIMEOUT_S)
        if code != 0:
            docker_out = ""
    except HTTPException:
        docker_out = ""
    try:
        _, uptime_out, _ = await _run_athena_ssh(
            "powershell -NoProfile -Command \"(Get-Date)-(Get-CimInstance Win32_OperatingSystem).LastBootUpTime\"",
            ATHENA_STATUS_TIMEOUT_S)
    except HTTPException:
        uptime_out = ""
    try:
        _, disk_out, _ = await _run_athena_ssh(
            "powershell -NoProfile -Command \"Get-PSDrive C | Select-Object Used,Free\"",
            ATHENA_STATUS_TIMEOUT_S)
    except HTTPException:
        disk_out = ""
    parsed = _parse_athena_status(docker_out, uptime_out, disk_out)
    _athena_cache.update(parsed)
    _athena_cache["updated"] = _now_ms()


def _schedule_athena_refresh():
    global _athena_task
    if _athena_task is not None and not _athena_task.done():
        return
    try:
        loop = asyncio.get_running_loop()
    except RuntimeError:
        return
    _athena_task = loop.create_task(_refresh_athena_status())


@app.get("/atlas/athena/status", dependencies=[Depends(require_token)])
async def athena_status():
    # async (not def) so _schedule_athena_refresh runs on the live event loop
    # instead of FastAPI's threadpool, where there is none to schedule onto.
    stale = _now_ms() - int(_athena_cache.get("updated") or 0) > ATHENA_STATUS_TTL_S * 1000
    if stale:
        _schedule_athena_refresh()
    return {
        "containers": _athena_cache.get("containers", []),
        "uptime": _athena_cache.get("uptime", ""),
        "disk": _athena_cache.get("disk", ""),
        "updated": _athena_cache.get("updated", 0),
    }


def _is_container_down(status: str) -> bool:
    status = status.strip()
    if not status.startswith("Up"):
        return True
    return "unhealthy" in status.lower()


async def _check_athena_containers() -> dict:
    exit_code, out, _ = await _run_athena_ssh(
        'docker ps -a --format "{{.Names}}|{{.Status}}"', ATHENA_STATUS_TIMEOUT_S
    )
    if exit_code != 0:
        return {"ok": False, "checked": 0, "filed": 0}
    filed = 0
    seen = set()
    for line in out.splitlines():
        line = line.strip()
        if not line or "|" not in line:
            continue
        name, status = (p.strip() for p in line.split("|", 1))
        if not name:
            continue
        seen.add(name)
        down = _is_container_down(status)
        was_down = _container_health_state.get(name)
        if was_down is not None and down != was_down:
            if down:
                title, body = f"{name} needs attention", f"Status: {status}"
            else:
                title, body = f"{name} is back up", f"Status: {status}"
            await report(
                Report(app="athena", title=title, body=body, priority="normal", key=f"athena-container:{name}")
            )
            filed += 1
        _container_health_state[name] = down
    return {"ok": True, "checked": len(seen), "filed": filed}


async def _athena_container_loop():
    while True:
        await asyncio.sleep(ATHENA_CONTAINER_CHECK_INTERVAL_S)
        try:
            await _check_athena_containers()
        except Exception:
            pass


def _schedule_athena_container_watch():
    global _athena_container_task
    if _athena_container_task is not None and not _athena_container_task.done():
        return
    try:
        loop = asyncio.get_running_loop()
    except RuntimeError:
        return
    _athena_container_task = loop.create_task(_athena_container_loop())


class AthenaContainerRestart(BaseModel):
    name: str


@app.post("/atlas/athena/containers/check", dependencies=[Depends(require_athena_token)])
async def athena_containers_check():
    return await _check_athena_containers()


@app.post("/atlas/athena/containers/restart", dependencies=[Depends(require_athena_token)])
async def athena_container_restart(r: AthenaContainerRestart):
    # Explicit, person-triggered only — the background watch above never
    # calls this itself. Name is validated against Docker's own naming
    # rule before it ever reaches the SSH command string (defense against
    # command injection through this field).
    name = r.name.strip()
    if not _CONTAINER_NAME_RE.match(name):
        raise HTTPException(status_code=400, detail="invalid container name")
    exit_code, out, err = await _run_athena_ssh(f"docker restart {name}", ATHENA_EXEC_TIMEOUT_S)
    try:
        title = f"Restarted {name}" if exit_code == 0 else f"Restart failed: {name}"
        await report(
            Report(app="athena", title=title, body=(out or err).strip()[:500] or f"exit {exit_code}", key=None)
        )
    except Exception:
        pass
    return {"ok": exit_code == 0, "exit": exit_code, "stdout": out, "stderr": err}


@app.get("/atlas/reports", dependencies=[Depends(require_token)])
def list_reports(app: Optional[str] = None, since: Optional[int] = None, limit: int = 50):
    query = "SELECT * FROM reports WHERE 1=1"
    params: list = []
    if app:
        query += " AND app = ?"
        params.append(app)
    if since is not None:
        query += " AND at >= ?"
        params.append(since)
    query += " ORDER BY at DESC LIMIT ?"
    params.append(limit)
    with hub_db() as conn:
        rows = conn.execute(query, params).fetchall()
    return {"reports": [dict(r) for r in rows]}


# ---------------------------------------------------------------------------
# MCP over Streamable HTTP at /mcp, so Hermes/ATLAS can report, brief, ask.
# ---------------------------------------------------------------------------


@mcp.tool
async def mcp_report(app: str, title: str, body: str = "", priority: str = "normal", key: str | None = None) -> dict:
    """File a report with the Atlas hub (app name, title, body, priority low/normal/urgent, optional dedupe key)."""
    return await report(Report(app=app, title=title, body=body, priority=priority, key=key))


@mcp.tool
def mcp_brief() -> dict:
    """Get the current spoken-English brief of everything reported to the hub, plus unacked urgent items."""
    return brief()


@mcp.tool
async def mcp_ask(question: str) -> dict:
    """Ask the hub a question; it answers from recent reports and the vault without using Claude tokens."""
    return await ask(Ask(question=question))


@mcp.tool
async def mcp_ask_vault(question: str) -> dict:
    """Ask the Obsidian vault a question; answers strictly from notes, not recent reports, citing which note."""
    return await ask_vault(Ask(question=question))


@mcp.tool
def mcp_open_loop(text: str, source: str = "voice", key: str | None = None) -> dict:
    """Capture an open loop (something the person still needs to follow up on); the hub resurfaces it in briefs until acked."""
    return open_loop(OpenLoop(text=text, source=source, key=key))


@mcp.tool
def mcp_open_loops(include_closed: bool = False, limit: int = 50) -> dict:
    """List open loops still waiting on the person, or with include_closed the full history."""
    return list_open_loops(include_closed=include_closed, limit=limit)


@mcp.tool
def mcp_open_loop_ack(ids: list[int]) -> dict:
    """Close one or more open loops by id."""
    return open_loop_ack(OpenLoopAck(ids=ids))


@mcp.tool
def mcp_capture(text: str, kind: str = "note", key: str | None = None) -> dict:
    """Quick-capture an idea, note, or reminder; sorted into shop/house/projects overnight."""
    return capture(Capture(text=text, kind=kind, key=key))


@mcp.tool
def mcp_captures(category: str | None = None, since: int | None = None, limit: int = 50) -> dict:
    """List quick-captures, optionally filtered by sorted category (shop/house/projects)."""
    return list_captures(category=category, since=since, limit=limit)


@mcp.tool
async def mcp_captures_sort() -> dict:
    """Run the nightly capture-sort job now instead of waiting for the scheduled hour."""
    return await captures_sort()


@mcp.tool
def mcp_money_subscriptions() -> dict:
    """List subscriptions/renewals read from the vault's subscriptions file."""
    return money_subscriptions()


@mcp.tool
async def mcp_money_check() -> dict:
    """Run the Money Sentinel check now and file hub reports for anything renewing soon."""
    return await money_check()


@mcp.tool
async def mcp_catch_me_up() -> dict:
    """Compose a short catch-up on what happened since the last time this was called (call it on arrival)."""
    return await catch_me_up()


@mcp.tool
def mcp_sunday_brief() -> dict:
    """Get the most recently composed Sunday Brief (the weekly look-back)."""
    return sunday_brief()


@mcp.tool
async def mcp_sunday_brief_compose() -> dict:
    """Compose the Sunday Brief now instead of waiting for Sunday morning."""
    return await sunday_brief_compose()


@mcp.tool
async def mcp_where_was_i() -> dict:
    """Remind the person what they were just doing or talking about."""
    return await where_was_i()


@mcp.tool
async def mcp_outage_check() -> dict:
    """Check Obelisk's own internet reachability now; reports if it just went down or came back."""
    return await outage_check()


@mcp.tool
async def mcp_account_check() -> dict:
    """Check for new account sign-in alerts logged in the vault; files any unseen ones as urgent."""
    return await account_check()


@mcp.tool
def mcp_house_list_add(item: str, list_name: str = "grocery") -> dict:
    """Add an item to a household list (grocery, supplies, etc.), built by voice."""
    return house_list_add(HouseListItem(item=item, list_name=list_name))


@mcp.tool
def mcp_house_list(list_name: str = "grocery", include_done: bool = False) -> dict:
    """List a household list's items, open by default."""
    return house_list(list_name=list_name, include_done=include_done)


@mcp.tool
def mcp_house_list_done(ids: list[int]) -> dict:
    """Mark household list items done/bought by id."""
    return house_list_done(HouseListDone(ids=ids))


@mcp.tool
def mcp_house_maintenance() -> dict:
    """List household maintenance clocks (HVAC filter, car service, etc.) read from the vault."""
    return house_maintenance()


@mcp.tool
async def mcp_house_maintenance_check() -> dict:
    """Check maintenance clocks now; reports anything due or overdue."""
    return await house_maintenance_check()


@mcp.tool
async def mcp_leave_home_check() -> dict:
    """Run the leave-home checklist (cameras, print area); composes a 'House is set' line, exceptions only."""
    return await leave_home_check()


@mcp.tool
def mcp_save_routine(name: str, steps: list[str]) -> dict:
    """Teach or replace a named routine (a flat list of free-text steps)."""
    return save_routine(Routine(name=name, steps=steps))


@mcp.tool
def mcp_list_routines() -> dict:
    """List all named routines and their steps."""
    return list_routines()


@mcp.tool
async def mcp_run_routine(name: str) -> dict:
    """Run a named routine: executes hub-actionable steps (printer status, brief mode), names the rest for app/HA."""
    return await run_routine(RoutineRun(name=name))


@mcp.tool
async def mcp_departure_check() -> dict:
    """Run the departure checklist (printer, open urgent items); composes a 'clear to go' line, exceptions only."""
    return await departure_check()


@mcp.tool
def mcp_create_trigger(condition: str, reminder: str) -> dict:
    """Create a context-bound reminder ('when I get home', 'when the printer's idle') instead of a clock-bound one."""
    return create_trigger(PlaceTrigger(condition=condition, reminder=reminder))


@mcp.tool
def mcp_list_triggers(include_fired: bool = False) -> dict:
    """List pending (or, with include_fired, all) place/person triggers."""
    return list_triggers(include_fired=include_fired)


@mcp.tool
async def mcp_triggers_check() -> dict:
    """Check hub-evaluable trigger conditions now (currently: printer idle)."""
    return await triggers_check()


@mcp.tool
async def mcp_triggers_event(event: str) -> dict:
    """Signal an app-detected event (e.g. 'home', 'jenna') to fire any matching pending triggers."""
    return await triggers_event(PlaceTriggerEvent(event=event))


@mcp.tool
def mcp_chronicle_note(text: str, session_id: str | None = None, kind: str = "note") -> dict:
    """Capture a whispered D&D session note (loot, a name, a ruling); defaults session_id to today's date."""
    return chronicle_note(ChronicleNote(text=text, session_id=session_id, kind=kind))


@mcp.tool
def mcp_chronicle_sessions() -> dict:
    """List D&D sessions with notes, newest first."""
    return chronicle_sessions()


@mcp.tool
async def mcp_chronicle_recap(session_id: str) -> dict:
    """Compose a session recap from its captured notes."""
    return await chronicle_recap(ChronicleRecapRequest(session_id=session_id))


@mcp.tool
async def mcp_chronicle_opener(exclude_session_id: str | None = None) -> dict:
    """Compose a 'Last time on...' opener from the most recent recap."""
    return await chronicle_opener(exclude_session_id)


@mcp.tool
def mcp_care_settings(movement_enabled: bool | None = None, weighin_enabled: bool | None = None) -> dict:
    """Get or set Care Nudges opt-in settings (both off by default)."""
    if movement_enabled is None and weighin_enabled is None:
        return get_care_settings()
    return set_care_settings(movement_enabled=movement_enabled, weighin_enabled=weighin_enabled)


@mcp.tool
async def mcp_care_sedentary() -> dict:
    """Signal a sedentary work stretch; nudges gently only if opted in and past the cooldown."""
    return await care_sedentary()


@mcp.tool
def mcp_log_weigh_in(weight: float) -> dict:
    """Log a weigh-in, used for the opt-in weekly Sunday Brief line."""
    return log_weigh_in(WeighIn(weight=weight))


@mcp.tool
async def mcp_camera_ask(camera: str, question: str) -> dict:
    """Ask a plain question about a named camera's current view (e.g. 'is the print bed clear')."""
    return await camera_ask(CameraAsk(camera=camera, question=question))


@mcp.tool
async def mcp_print_bed_clear() -> dict:
    """Check whether the print bed/build plate is visible and clear right now."""
    return await print_bed_clear()


@mcp.tool
async def mcp_camera_find(question: str) -> dict:
    """Search every enabled camera's current view for something (e.g. 'where is Fiona the dog')."""
    return await camera_find(CameraFind(question=question))


@mcp.tool
async def mcp_frigate_cameras() -> dict:
    """List Frigate's cameras and whether each is enabled."""
    return await frigate_cameras()


@mcp.tool
async def mcp_print_preflight() -> dict:
    """Pre-flight check before starting a print: is the print-area camera up and able to confirm the bed is clear."""
    return await print_preflight()


@mcp.tool
async def mcp_print_watch_check() -> dict:
    """Check the print-area camera's liveness now and report if it just went offline or came back."""
    return await print_watch_check()


@mcp.tool
async def mcp_gatekeeper_check() -> dict:
    """Check the gate cameras for new person sightings now and file reports for any found."""
    return await gatekeeper_check()


@mcp.tool
def mcp_filament_spools() -> dict:
    """List filament spools and remaining grams, read from the vault's filament file."""
    return filament_spools()


@mcp.tool
def mcp_print_queue() -> dict:
    """List the print queue, read from the vault's print-queue file."""
    return print_queue()


@mcp.tool
async def mcp_filament_check() -> dict:
    """Check whether on-hand filament covers the queued jobs; reports any material that's short."""
    return await filament_check()


@mcp.tool
def mcp_slicer_profile() -> dict:
    """Get the current slicer settings profile tracked by the hub."""
    return slicer_profile()


@mcp.tool
async def mcp_slicer_tune(symptom: str) -> dict:
    """Given a reported print symptom, propose one bounded slicer-setting tweak and record it."""
    return await slicer_tune(SlicerTune(symptom=symptom))


@mcp.tool
async def mcp_delegate(task: str) -> dict:
    """Hand a job to Hermes on this node and wait for its answer (up to a few minutes)."""
    return await delegate(Delegate(task=task))


@mcp.tool
async def mcp_athena_containers_check() -> dict:
    """Check Athena's containers for anything down or unhealthy now; reports on any change since last check."""
    return await athena_containers_check()


@mcp.tool
async def mcp_athena_container_restart(name: str) -> dict:
    """Restart a named container on Athena. Explicit action only — never run automatically."""
    return await athena_container_restart(AthenaContainerRestart(name=name))


@mcp.tool
def search_vault(q: str, limit: int = 8) -> dict:
    """Search the Obsidian vault for lines matching a query."""
    return {"results": _search_vault(q, limit)}


app.mount("/", _mcp_app)
