#!/usr/bin/env bash
# Run an Artemis CLI command inside the container, with credentials from infra/.env.
# Usage: ./labs/art.sh producer --destination queue://lab.x --message-count 3
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; source infra/.env; set +a
exec docker compose --env-file infra/.env -f infra/docker-compose.yml exec artemis \
  /var/lib/artemis-instance/bin/artemis "$@" \
  --user "$ARTEMIS_USER" --password "$ARTEMIS_PASSWORD" --url tcp://localhost:61616
