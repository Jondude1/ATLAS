# Corin for Razer Anzu

Corin is an AI companion that lives in your Razer Anzu audio glasses. It speaks up on its own through the day, you can talk back, and it handles the useful stuff around that: notifications, timers, notes, the house, research.

The Anzu has speakers, a microphone and touch controls, with no screen or camera, so everything here is voice-first.

## What it does

- **Speaks up on its own** at random intervals while the glasses are on, inside your active hours, never during calls.
- **Conversation window**: after Corin speaks it listens for your reply, so you can just answer.
- **Glasses gesture**: the Anzu voice-assistant gesture opens Corin (pick it when Android asks which assistant to use).
- **Its own identity**: on first run it writes its own personality and picks its own voice, then keeps them. The personality drifts slowly, one small step per day.
- **Memory**: durable facts it remembers about you, a full conversation log, and "what did I say about X" recall.
- **Evening wrap-up** that becomes a journal entry.
- **Notification triage**: people and reminders now, everything else held for "what did I miss?".
- **Home Assistant** control and awareness, with green / amber / red zones. Amber actions need a spoken okay; red are refused.
- **Undo** and a log of everything it did on its own.
- **Research errands** in the background, reported back when finished.
- **Timers, calendar heads-ups, battery callouts, eye breaks, find-my-glasses, typed input.**
- **Two tiers**: Claude for thinking, optional Ollama endpoint for small talk and as a fallback.
- **Updates itself** from this repo's releases.

## Setup

1. Install the APK from the latest release.
2. Open the app, grant permissions, allow notification access.
3. Settings: add your Anthropic API key. Optional: Home Assistant URL and token, Obelisk server URL, Ollama URL.
4. Put the glasses on. Corin wakes up, chooses who it is, and says hello.

## Obelisk (optional)

`obelisk/corin_server.py` is a small FastAPI server that writes every exchange into your Obsidian vault as daily notes, lets recall search the vault, and tracks which device you're on.

    pip install fastapi uvicorn
    CORIN_VAULT=/path/to/vault uvicorn corin_server:app --host 0.0.0.0 --port 8765

Keep it on Tailscale only; it has no login.

## Added in the second pass

- **House alerts**: tells you when a door, window, garage or lock has been left open, and when people come and go.
- **Activity aware**: no unprompted chatter while driving; opens a real conversation when you head out on a walk.
- **Places**: "this is home", then "remind me to take out the trash when I get home".
- **Checklists**: "add milk to my grocery list", "what's my next step?", "done".
- **Weather** in the morning hello and on request.
- **Companion screen** with a live orb, today's conversation, and Talk / Quiet buttons on the notification.
- **Mic test** and a daily token meter in Settings.
