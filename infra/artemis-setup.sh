#!/usr/bin/env bash
# Creates the multicast-only address chat.events on the lab broker. Safe to run twice.
set -euo pipefail
cd "$(dirname "$0")/.."
./labs/art.sh address create --name chat.events --multicast --no-anycast --silent \
  || echo "chat.events probably exists already (check: ./labs/art.sh address show --name chat.events)"
