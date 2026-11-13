#!/bin/bash
# Starts the whole stack (primary, replica, app in containers), rebuilding the app image.
# Usage: scripts/start.sh                 background mode, waits until the API is healthy
#        scripts/start.sh --foreground    interactive mode, streams all logs to this terminal (Ctrl+C stops everything)
set -e
cd "$(dirname "$0")/.."

if [ "$1" = "--foreground" ] || [ "$1" = "-f" ]; then
    exec docker compose -f infra/docker-compose.yml up --build
fi

docker compose -f infra/docker-compose.yml up -d --build

echo "Waiting for the API to become healthy..."
for _ in $(seq 1 60); do
    if curl -sf http://localhost:8080/actuator/health >/dev/null; then
        echo "Up: API http://localhost:8080 | primary localhost:15432 | replica localhost:15433"
        echo "Logs: tail -f logs/app.log   or   docker compose -f infra/docker-compose.yml logs -f app"
        exit 0
    fi
    sleep 2
done

echo "API did not become healthy in time. Check: docker compose -f infra/docker-compose.yml logs app" >&2
exit 1
