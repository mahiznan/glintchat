#!/usr/bin/env bash
# One read-only picture of the whole system for the failure drills.
set -uo pipefail
cd "$(dirname "$0")/.."
set -a; source infra/.env; set +a
DB() { docker compose --env-file infra/.env -f infra/docker-compose.yml exec -T mariadb \
        mariadb -N -u"$MARIADB_USER" -p"$MARIADB_PASSWORD" "$MARIADB_DATABASE" -e "$1" 2>/dev/null; }

echo "== gateways: podId / sockets / users / threads"
for port in 8081 8083; do
  printf 'port %s: ' "$port"; curl -s --max-time 2 "localhost:$port/internal/diag" || printf 'down'; echo
done
echo "== messages (count, max seq) and outbox (unpublished, total)"
DB "SELECT COUNT(*), COALESCE(MAX(seq),0) FROM message;"
DB "SELECT COALESCE(SUM(published_at IS NULL),0), COUNT(*) FROM outbox;"
echo "== DB connections by command (pool size should come back after a drill)"
DB "SELECT command, COUNT(*) FROM information_schema.processlist WHERE user='$MARIADB_USER' GROUP BY command;"
echo "== DB transactions holding row locks (should be empty at rest)"
DB "SELECT trx_id, trx_state, trx_rows_locked, trx_mysql_thread_id FROM information_schema.innodb_trx;"
echo "== broker: queues on chat.events"
# Server-side filter on the address; queue stat shows only 50 rows and cuts names at 25 characters by default.
# A header with no rows below it means no pod queue exists.
./labs/art.sh queue stat --field ADDRESS --operation EQUALS --value chat.events --maxRows 200 --maxColumnSize -1 2>&1 \
  | grep -v "^Connection brokerURL"
