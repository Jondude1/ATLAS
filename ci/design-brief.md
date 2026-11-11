You are the lead UI designer on ATLAS, a one-person Android app (Jetpack Compose, Material 3). It is the owner's personal always-on AI that lives in his earbuds and audio glasses: his JARVIS. I am the engineer and will implement exactly what you specify.

DIRECTION (from the owner, replacing the last round): ATLAS is a futuristic AI companion, an intelligence you live with, not mission control. Last round's "rover console" look is retracted: no monospace-everywhere type, telemetry grids, scopes, instrument readouts or panel-and-dial language on the main surfaces. Monospace survives only inside raw data views (command output, logs).

KEEP from your first review: one locked orange #FF5C00, flat design, strict grayscale surfaces, no box-in-box cards, 24dp margins, a flat ring instead of a 3D sphere.

NORTH STAR: companion presence.
- Home is a presence, not a dashboard: a breathing ring as a living entity at the centre, a greeting that knows the time and day and feels aware, not mechanical; conversation first.
- Data is what the companion knows, in its own words first ("Athena's fine, all 33 services up"), raw readouts one tap down.
- Type: a clean futuristic sans for the companion's voice and UI. I have Sora (Light 300, Regular 400, SemiBold 600) bundled; say if you want another OFL Google font instead.
- Motion is personality: the ring breathes, ripples when listening, settles when idle. Calm, cinematic, warm.

Real data available for home: time of day, day, what is worn (glasses / earbuds / nothing) and its battery, quiet mode, whether the home server hub and Home Assistant are reachable, Athena's container count and how many are down, the latest thing ATLAS said, and a one-line brief from the hub. Required functions on home: tap to talk, type a message, "Speak up" (ATLAS says something now), "Quiet 1h". Tabs: Home, Notes, Whisper, Settings.

Attached are the current screens (the retracted console look).

Give me a complete, concrete spec I can build without guessing:
1. Concept in two sentences.
2. Colour tokens (hex), dark and light.
3. Typography: font, sizes, weights, letter spacing, case for each role.
4. Home, top to bottom: every element, size in dp, position, exact content and copy templates (greeting by time of day; the "what I know" line). Describe the ring precisely enough to draw on a Compose Canvas, with motion per state: idle, listening, thinking, speaking (durations, easing, scale and opacity ranges).
5. Component rules: buttons, inputs, switches, list rows, dialogs, tab bar.
6. Notes, Whisper and Settings, and how a detail screen (e.g. Athena) presents summary first, raw data second.
7. Three things to avoid.
Be decisive. One answer per item. Under 900 words.
