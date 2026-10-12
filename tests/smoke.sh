#!/usr/bin/env bash
# Smoke test for the local stack: Artemis, MariaDB, Redis.
# Run from anywhere: ./tests/smoke.sh   (the stack must be up: see README.md)
set -uo pipefail
cd "$(dirname "$0")/.."

[[ -f infra/.env ]] || { echo "FAIL: infra/.env missing (cp infra/.env.example infra/.env)"; exit 1; }
set -a; source infra/.env; set +a

dc() { docker compose --env-file infra/.env -f infra/docker-compose.yml "$@"; }
ARTEMIS=/var/lib/artemis-instance/bin/artemis
BROKER=tcp://localhost:61616
QUEUE="smoke.test.$$"
FAILED=0

pass() { echo "PASS  $1"; }
fail() { echo "FAIL  $1"; FAILED=1; }
check() { # check "description" command...
  local desc="$1"; shift
  if "$@" >/dev/null 2>&1; then pass "$desc"; else fail "$desc"; fi
}

# 1. every service is healthy
for svc in artemis mariadb redis; do
  health=$(dc ps --format '{{.Service}} {{.Health}}' | awk -v s="$svc" '$1==s {print $2}')
  [[ "$health" == "healthy" ]] && pass "$svc is healthy" || fail "$svc is healthy (got: '${health:-not running}')"
done

# 2. Artemis: pinned version, console, send and receive
out=$(dc exec -T artemis $ARTEMIS version 2>&1)
[[ "$out" == *"2.43.0"* ]] && pass "artemis version is 2.43.0" || fail "artemis version is 2.43.0"

code=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8161/console/ || true)
[[ "$code" == "200" || "$code" == "302" ]] \
  && pass "artemis console answers (HTTP $code)" || fail "artemis console answers (HTTP $code)"

MSG="hello-$$"
dc exec -T artemis $ARTEMIS producer --user "$ARTEMIS_USER" --password "$ARTEMIS_PASSWORD" \
  --url "$BROKER" --destination "queue://$QUEUE" --message-count 1 --message "$MSG" >/dev/null 2>&1 \
  && pass "artemis producer sent a message" || fail "artemis producer sent a message"
# capture the output first: piping straight into `grep -q` can close the pipe early and trip pipefail
out=$(dc exec -T artemis $ARTEMIS consumer --user "$ARTEMIS_USER" --password "$ARTEMIS_PASSWORD" \
  --url "$BROKER" --destination "queue://$QUEUE" --message-count 1 --verbose 2>&1)
[[ "$out" == *"Received $MSG"* ]] \
  && pass "artemis consumer received the same message" || fail "artemis consumer received the same message"
# clean up the temporary queue and address
dc exec -T artemis $ARTEMIS queue delete --name "$QUEUE" --user "$ARTEMIS_USER" --password "$ARTEMIS_PASSWORD" \
  --url "$BROKER" --removeConsumers >/dev/null 2>&1
dc exec -T artemis $ARTEMIS address delete --name "$QUEUE" --user "$ARTEMIS_USER" --password "$ARTEMIS_PASSWORD" \
  --url "$BROKER" >/dev/null 2>&1

# 3. MariaDB: app user can log in to its database
check "mariadb app user can query database '$MARIADB_DATABASE'" \
  dc exec -T mariadb mariadb -u"$MARIADB_USER" -p"$MARIADB_PASSWORD" "$MARIADB_DATABASE" -e "SELECT 1"

# 4. Redis: auth enforced, works with the password, persistence off
out=$(dc exec -T redis redis-cli ping 2>&1 || true)
[[ "$out" == *NOAUTH* ]] && pass "redis rejects unauthenticated commands" || fail "redis rejects unauthenticated commands (got: $out)"
out=$(dc exec -T redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning ping' 2>&1 || true)
[[ "$out" == "PONG" ]] && pass "redis answers PING with the password" || fail "redis answers PING with the password (got: $out)"
out=$(dc exec -T redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning config get appendonly' 2>&1 | tail -1)
[[ "$out" == "no" ]] && pass "redis append-only log is off" || fail "redis append-only log is off (got: $out)"

echo
if [[ $FAILED -eq 0 ]]; then echo "ALL PASS"; else echo "SOME CHECKS FAILED"; exit 1; fi
