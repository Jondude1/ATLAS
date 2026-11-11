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
from datetime import datetime
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
            yield
    except RuntimeError:
        _schedule_athena_refresh()
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


def _search_vault(q: str, limit: int = 8):
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
                        hits.append((score, f"{path.stem}: {line.strip()[:260]}"))
            except OSError:
                continue
    hits.sort(key=lambda h: -h[0])
    return [h[1] for h in hits[:limit]]


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
        conn.commit()


init_hub_db()

_brief_cache = {"brief": "", "updated": 0}
_brief_lock = asyncio.Lock()
_brief_task: Optional[asyncio.Task] = None


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
async def mcp_delegate(task: str) -> dict:
    """Hand a job to Hermes on this node and wait for its answer (up to a few minutes)."""
    return await delegate(Delegate(task=task))


@mcp.tool
def search_vault(q: str, limit: int = 8) -> dict:
    """Search the Obsidian vault for lines matching a query."""
    return {"results": _search_vault(q, limit)}


app.mount("/", _mcp_app)
