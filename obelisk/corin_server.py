"""Corin server for Obelisk.

Gives the phone app a home on your node:
  - every exchange is appended to a daily note in your Obsidian vault
  - recall can search the whole vault
  - the node knows which device you're on (presence)

Run:
    pip install fastapi uvicorn
    CORIN_VAULT=/path/to/ObsidianVault uvicorn corin_server:app --host 0.0.0.0 --port 8765

Then put http://<obelisk-tailscale-name>:8765 in the app under
Settings -> Obelisk and local models. Keep it on Tailscale only: there is no login.
"""
import os
import re
import time
from datetime import datetime
from pathlib import Path

from fastapi import FastAPI
from pydantic import BaseModel

VAULT = Path(os.environ.get("CORIN_VAULT", str(Path.home() / "Obsidian"))).expanduser()
DAILY = VAULT / "Corin" / "Daily"
app = FastAPI(title="Corin on Obelisk")
state = {"device": "unknown", "since": 0.0, "started": time.time()}


class LogEntry(BaseModel):
    speaker: str
    text: str
    device: str = "phone"


class Presence(BaseModel):
    device: str


@app.post("/corin/log")
def log(entry: LogEntry):
    DAILY.mkdir(parents=True, exist_ok=True)
    now = datetime.now()
    path = DAILY / f"{now:%Y-%m-%d}.md"
    if not path.exists():
        path.write_text(f"# Corin log, {now:%A %B %d %Y}\n\n", encoding="utf-8")
    text = entry.text.replace("\n", " ").strip()
    with path.open("a", encoding="utf-8") as f:
        if entry.speaker == "journal":
            f.write(f"\n## Journal\n{text}\n\n")
        else:
            f.write(f"- {now:%H:%M} **{entry.speaker}** ({entry.device}): {text}\n")
    return {"ok": True}


@app.post("/corin/presence")
def presence(p: Presence):
    state["device"] = p.device
    state["since"] = time.time()
    return {"ok": True}


@app.get("/corin/presence")
def where():
    return {"device": state["device"], "since": state["since"]}


@app.get("/corin/search")
def search(q: str, limit: int = 8):
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
    return {"results": [h[1] for h in hits[:limit]]}


@app.get("/corin/status")
def status():
    return {
        "ok": True,
        "vault": str(VAULT),
        "vault_exists": VAULT.exists(),
        "device": state["device"],
        "uptime_s": int(time.time() - state["started"]),
    }
