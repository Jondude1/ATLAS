#!/usr/bin/env bash
# Installs and starts the Atlas server on this node as a systemd --user service,
# so it comes up on boot (via `loginctl enable-linger $USER`) and restarts if it dies.
# Re-run any time to pick up new requirements or move the venv to a new path.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV="$HERE/.venv"
VAULT="${ATLAS_VAULT:-$HOME/Documents/Hermes}"
PORT="${ATLAS_PORT:-8765}"
LLM_URL="${ATLAS_LLM_URL:-http://localhost:11434/v1}"
LLM_MODEL="${ATLAS_LLM_MODEL:-qwen3:14b-ha}"
UNIT_DIR="$HOME/.config/systemd/user"
UNIT="$UNIT_DIR/atlas-server.service"

echo "Vault:    $VAULT"
echo "Port:     $PORT"
echo "Repo dir: $HERE"

python3 -m venv "$VENV" --upgrade-deps 2>/dev/null || python3 -m venv "$VENV"
"$VENV/bin/pip" install -q -U pip
if ! "$VENV/bin/pip" install -q -r "$HERE/requirements.txt"; then
    echo "Full install failed (likely faster-whisper/numpy on this Python version)." >&2
    echo "Retrying with just the required web server deps; Whisper transcription will stay disabled." >&2
    "$VENV/bin/pip" install -q fastapi "uvicorn[standard]" pydantic
fi

mkdir -p "$UNIT_DIR"
cat > "$UNIT" <<EOF
[Unit]
Description=Atlas server (vault log/search, presence, Whisper fallback)
After=network-online.target

[Service]
Type=simple
WorkingDirectory=$HERE
Environment=ATLAS_VAULT=$VAULT
Environment=ATLAS_WHISPER=base.en
Environment=ATLAS_LLM_URL=$LLM_URL
Environment=ATLAS_LLM_MODEL=$LLM_MODEL
Environment=ATLAS_TOKEN=${ATLAS_TOKEN:-}
Environment=ATLAS_ATHENA_HOST=${ATLAS_ATHENA_HOST:-user@athena.tail8dab0b.ts.net}
ExecStart=$VENV/bin/uvicorn atlas_server:app --host 0.0.0.0 --port $PORT
Restart=on-failure
RestartSec=5

[Install]
WantedBy=default.target
EOF

systemctl --user daemon-reload
systemctl --user enable --now atlas-server.service

echo
echo "Done. Status:"
systemctl --user --no-pager status atlas-server.service || true
echo
echo "If this box has a firewall (ufw), make sure port $PORT is open on the tailnet, e.g.:"
echo "  sudo ufw allow from 100.64.0.0/10 to any port $PORT proto tcp"
