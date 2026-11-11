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
    ATLAS_LLM_MODEL  model name for that endpoint (default mistral-nemo)
    ATLAS_TOKEN      if set, every /atlas/* request needs Authorization: Bearer <token>

Optional, for transcribing the Buddie earbud stream with Whisper on the node
(the phone only falls back to this when Android's own recognizer can't do it):
    pip install faster-whisper numpy
    ATLAS_WHISPER=base.en   (model size; default base.en)

Then put http://<obelisk-tailscale-name>:8765 in the app under
Settings -> Obelisk and local models. Keep it on Tailscale only: there is no login.
"""
import asyncio
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
LLM_MODEL = os.environ.get("ATLAS_LLM_MODEL", "mistral-nemo")
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
        conn.commit()


init_hub_db()

_brief_cache = {"brief": "", "updated": 0}
_brief_lock = asyncio.Lock()
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


def _now_ms() -> int:
    return int(time.time() * 1000)


def _prune_old_reports(conn: sqlite3.Connection):
    cutoff = _now_ms() - REPORT_TTL_DAYS * 86400 * 1000
    conn.execute("DELETE FROM reports WHERE at < ?", (cutoff,))


async def _chat(messages: list[dict], max_tokens: int = 300, timeout_s: float = 30) -> Optional[str]:
    """Calls the OpenAI-style endpoint. Returns None (never raises) if it's down."""
    try:
        async with httpx.AsyncClient(timeout=timeout_s) as client:
            resp = await client.post(
                f"{LLM_URL.rstrip('/')}/chat/completions",
                json={"model": LLM_MODEL, "messages": messages, "max_tokens": max_tokens},
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
            max_tokens=220,
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
        max_tokens=5,
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
        max_tokens=220,
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
    reply = await _chat(
        [
            {
                "role": "system",
                "content": (
                    "Write a short, warm weekly look-back covering the last 7 days, drawn "
                    "only from the reports given. If the week was quiet, say so briefly and "
                    "warmly. Plain spoken English, at most 120 words, no markdown, no lists."
                ),
            },
            {
                "role": "user",
                "content": reports_text or "(a quiet week, nothing reported)",
            },
        ],
        max_tokens=220,
        timeout_s=90,
    )
    summary = reply if reply else _fallback_brief(rows)
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
        max_tokens=160,
        timeout_s=25,
    )
    if reply is None:
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
        max_tokens=160,
        timeout_s=25,
    )
    if reply is None:
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
