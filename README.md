# Atlas for Razer Anzu

Atlas is an AI companion that lives in your Razer Anzu audio glasses. It speaks up on its own through the day, you can talk back, and it handles the useful stuff around that: notifications, timers, notes, the house, research.

The Anzu has speakers, a microphone and touch controls, with no screen or camera, so everything here is voice-first.

## What it does

- **Speaks up on its own** at random intervals while the glasses are on, inside your active hours, never during calls.
- **Conversation window**: after Atlas speaks it listens for your reply, so you can just answer.
- **Glasses gesture**: the Anzu voice-assistant gesture opens Atlas (pick it when Android asks which assistant to use).
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
4. Put the glasses on. Atlas wakes up, chooses who it is, and says hello.

## Obelisk (optional)

`obelisk/atlas_server.py` is a small FastAPI server that writes every exchange into your Obsidian vault as daily notes, lets recall search the vault, and tracks which device you're on. It also transcribes the Buddie earbud stream with Whisper when Android's own recognizer can't.

Run `obelisk/install.sh` on the node. It creates a venv, installs the requirements, and installs + starts a `systemd --user` service (`atlas-server.service`) so the server comes up on boot and restarts if it dies — no root needed, as long as `loginctl enable-linger $USER` is set. Defaults to `$HOME/Documents/Hermes` as the vault and port 8765; override with `ATLAS_VAULT=` / `ATLAS_PORT=` env vars before running it.

Then put `http://<obelisk-tailscale-name>:8765` in the app under Settings → Obelisk and local models. Keep it on Tailscale only; it has no login. If the node runs a firewall, open the port on the tailnet only, e.g. `sudo ufw allow from 100.64.0.0/10 to any port 8765 proto tcp`.

## Added in the second pass

- **House alerts**: tells you when a door, window, garage or lock has been left open, and when people come and go.
- **Activity aware**: no unprompted chatter while driving; opens a real conversation when you head out on a walk.
- **Places**: "this is home", then "remind me to take out the trash when I get home".
- **Checklists**: "add milk to my grocery list", "what's my next step?", "done".
- **Weather** in the morning hello and on request.
- **Companion screen** with a live orb, today's conversation, and Talk / Quiet buttons on the notification.
- **Mic test** and a daily token meter in Settings.

## Buddie earbuds

Atlas treats the Buddie earbuds as a second wearable. When they connect it also opens their always-on audio stream (Bluetooth LE service `ae00`, Opus at 16 kHz, per the open-source Buddie app) and uses it for:

- **Wake word**: say "Atlas" and then what you want. For 30 seconds after Atlas speaks you can just answer.
- **Only your voice by default**: the earbuds flag when the wearer is speaking, and nothing else is transcribed unless you turn on Ambient context.
- **Meeting notes**: "Atlas, start meeting notes" / "Atlas, stop", or the earbuds' own meeting mode. The transcript is summarized into your notes.

Both Buddie audio formats are decoded: the default compressed-spectrum stream (244-byte packets, decoder derived from the MIT-licensed Buddie firmware, whose PCA table is bundled as `buddie_pca.bin`) and the Opus stream (84-byte packets). Call audio is dropped unless "Take notes on phone calls" is on. Settings → Buddie earbuds shows a diagnostics line with packets received, speech segments, and what was last understood.

## Verification

Every push runs unit tests for the decision logic, then installs the app on an Android emulator, runs on-device audio tests, opens every screen and fails the build on any crash. Bluetooth hardware, real speech recognition and the model API are not covered by that and need a real wear test.

## Voices

Settings → Voice has three sources:

- **Phone**: every English voice from the phone's speech engine, best quality first, tap to hear. You can switch engines and download more voices.
- **ElevenLabs**: paste your API key, load your voices (including designed or cloned ones) and pick one.
- **Custom**: any server with an OpenAI-style `/audio/speech` endpoint, such as OpenAI or a Kokoro or Piper server on Obelisk.

If a premium voice can't be reached, Atlas falls back to the phone voice.

## Glasses vs earbuds

Atlas notices which wearable you put on (the most recent one wins if both are connected) and behaves differently in each:

| | Glasses (open-ear) | Earbuds (private) |
|---|---|---|
| Replies | One or two short sentences | More room when it's worth it |
| Private details | Not said out loud when Discreet is on | Fine |
| Notifications | Sender only by default | Whole message by default |
| Talking back | Gesture, or the conversation window | Hands-free through the earbuds' own stream |
| Eye-break nudges | Yes | No |
| Volume and how often it speaks | Own settings | Own settings |

It says a short line when you switch.
