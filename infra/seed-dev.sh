#!/usr/bin/env bash
# Loads the demo conversation into the lab MariaDB. Lab only: the password is on the command line.
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; source infra/.env; set +a
docker compose --env-file infra/.env -f infra/docker-compose.yml exec -T mariadb \
  mariadb -u"$MARIADB_USER" -p"$MARIADB_PASSWORD" "$MARIADB_DATABASE" < infra/seed-dev.sql
echo "seeded conversation 018f6b2a-0000-7000-8000-00000000c001 (customer-1, rm-1)"
