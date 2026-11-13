#!/bin/bash
# Starts the web UI (React + Vite dev server on http://localhost:5173) and opens it in the browser.
# The backend must already be running (scripts/start.sh). Ctrl+C stops the UI.
# Usage: scripts/ui.sh            API_URL=http://host:8080 scripts/ui.sh   to point at another backend
set -e
cd "$(dirname "$0")/../ui"

API_URL="${API_URL:-http://localhost:8080}"
if ! curl -sf "$API_URL/actuator/health" >/dev/null; then
    echo "The API is not reachable at $API_URL. Start it first: scripts/start.sh" >&2
    exit 1
fi

if ! command -v npm >/dev/null; then
    echo "Node.js (v18+) and npm are required to run the UI." >&2
    exit 1
fi

if [ ! -d node_modules ]; then
    echo "Installing UI dependencies (first run)..."
    npm install --no-audit --no-fund
fi

API_URL="$API_URL" exec npm run dev -- --open
