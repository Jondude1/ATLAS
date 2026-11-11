# Atlas's reach

JARVIS was useful because of what he could reach, not how he talked. In the films he ran Tony's house, workshop, cars, phones, the Stark Industries network and the open web through one voice. This is that map applied to Jon's world, and the route Atlas takes to each.

Atlas reaches things three ways:

- **Built in**: things the phone itself can do or sense.
- **Connections (MCP)**: any service that speaks the Model Context Protocol over HTTP. Add it by address under Settings → Connections and its tools become Atlas's. The phone is on the tailnet, so Tailscale names work and nothing has to be exposed to the internet.
- **Hermes**: anything without an MCP server goes to Hermes on Athena, which has its own tools and memory and reports back.

| What JARVIS reached | Jon's equivalent | How Atlas reaches it | Status |
|---|---|---|---|
| The house: lights, climate, doors, "welcome home" | Home Assistant (Zigbee, locks, thermostat, Google Home speakers) | Connections → Home Assistant's built-in MCP server (`/api/mcp`, long-lived token). Also built-in house alerts and summaries | One tap once the HA address and token are in Settings |
| Security cameras and who's home | Frigate cameras, ESPresense rooms | Through Home Assistant (camera, person and room entities) | Works once HA is connected and those entities are exposed to Assist |
| The workshop: fabricators, renders, diagnostics | 3D printers, Kinect/holodesk, Obelisk | An MCP server per machine (OctoPrint and Bambu both have community MCP servers), or through Home Assistant's printer integrations | Needs a server on Athena or Obelisk |
| Stark Industries' network and databases | Athena (files, Docker, Audiobookshelf, Jellyfin), Obelisk (Ollama), GitHub | Filesystem, Docker, GitHub and shell MCP servers on Athena; Hermes for anything multi-step | Needs servers on Athena; Hermes hand-off is built in |
| Pepper on the line, call screening | Phone, texts, contacts | Built in: calls and texts by contact with a spoken okay; calls and messages announced as they arrive | Built in |
| Schedules and reminders | Google Calendar, alarms, timers, places | Built in: agenda, new events, alarms, timers, place reminders, leave-now alerts | Built in |
| Email | Gmail | Gmail MCP server on Athena, or Hermes | Needs a server or Hermes |
| Memory, files, the lab notebook | Obsidian vault, Atlas's own log | Built in: vault search, read and daily notes on the phone; the Atlas server on Obelisk adds whole-vault search | Built in (vault folder) |
| The suit | The earbuds and glasses | Built in: always-on earbud mic, wake word, meetings, battery, per-wearable behavior | Built in |
| The car | Mazda CX-5 tablet, OBD scanner, ClearDash | ClearDash or the car tablet exposing an MCP server; driving detection and navigation are built in | Needs work in ClearDash |
| Research and the open web | Web, news | Built in: background research errands with web search | Built in |
| Tony's other projects | Orbit, NoteUp, TableForge, BookShelf and the rest | Each app gets a small MCP endpoint, or a shared data folder on Athena served by a filesystem MCP server | Needs a change in each app |

## Adding a connection

1. Run the MCP server somewhere on the tailnet (Athena or Obelisk), using its HTTP transport. Servers that only speak stdio can be bridged with `mcp-proxy` or `supergateway`.
2. In Atlas: Settings → Connections → Add a connection. Give it a name, the address (for example `http://athena:8000/mcp`) and a token if it uses one.
3. Atlas lists the tools it found. Ask for things in plain language; Atlas picks the tool.

Anything matching the "ask first" words under Home Assistant (unlock, garage, alarm and so on) needs a spoken okay, whichever connection it goes through, and the "always refuse" words are refused. Read-only lookups are not logged; anything that changes something is, under Notes → Done on its own.
