#!/usr/bin/env bash
# Loads the demo conversations into the lab MariaDB. Lab only: the password is on the command line.
# Safe to run again: the SQL uses INSERT IGNORE.
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; source infra/.env; set +a
for sql in infra/seed-dev.sql infra/seed-dev2.sql; do
  docker compose --env-file infra/.env -f infra/docker-compose.yml exec -T mariadb \
    mariadb -u"$MARIADB_USER" -p"$MARIADB_PASSWORD" "$MARIADB_DATABASE" < "$sql"
done
echo "seeded conversation 018f6b2a-0000-7000-8000-00000000c001 (customer-1, rm-1)"
echo "seeded conversation 018f6b2a-0000-7000-8000-00000000c002 (customer-2, rm-2)"
