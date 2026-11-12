#!/bin/bash
# Stops the whole stack. Database data is kept, so the next start.sh resumes with the same data.
# Usage: scripts/stop.sh          stop and keep data
#        scripts/stop.sh --clean  stop and delete all data (volumes), next start re-creates primary and replica
set -e
cd "$(dirname "$0")/.."

if [ "$1" = "--clean" ]; then
    docker compose down -v
    echo "Stopped and removed all data."
else
    docker compose down
    echo "Stopped. Data kept (use --clean to delete it)."
fi
