# Docker Compose commands (Module 0)

Run from the repo root. Optional shortcut for the current shell session:

```bash
alias dc='docker compose --env-file infra/.env -f infra/docker-compose.yml'
```

The long form is what the README documents: `docker compose --env-file infra/.env -f infra/docker-compose.yml <command>`.

### About the `dc` alias
- It uses relative paths (`infra/.env`), so it only works from the repo root. The README documents the long form for that reason.
- An alias typed in a terminal is lost when the terminal closes. To keep it, add the line to `~/.zshrc`, then `source ~/.zshrc`.
- Aliases are not environment variables. List aliases with `alias` (one: `alias dc`, what a name resolves to: `type dc`, remove: `unalias dc`). List environment variables with `env` or `printenv`.
- `infra/.env` is a third thing: a file Docker Compose reads for `${...}` values. It is not part of your shell environment.
- In scripts and in the exec commands below, `$$VAR` / single quotes make the **container's** shell read the variable. Without the quotes your Mac's shell would try to expand it and find nothing.

## Lifecycle

| Command | Container | Volume (data) | Use |
|---|---|---|---|
| `dc up -d --wait` | created and started | created if missing | Start everything and wait until healthy |
| `dc stop` | kept, stopped | kept | Pause |
| `dc start` | same container restarted | kept | Resume |
| `dc restart <svc>` | restarted | kept | Quick bounce of one service |
| `dc down` | removed | **kept** | Clean shutdown, data survives |
| `dc down -v` | removed | **deleted** | Full reset, data lost |

## Inspecting

```bash
dc ps            # running services and health
dc ps -a         # include stopped ones
dc logs artemis  # all logs
dc logs -f artemis   # follow (Ctrl-C stops following, not the container)
dc config -q     # validate the compose file, prints nothing if OK
docker volume ls | grep glint   # list our volumes
```

## Artemis specifics

- Console: http://localhost:8161 (login from `infra/.env`, default `artemis` / `artemis`)
- Version check: `dc exec artemis /var/lib/artemis-instance/bin/artemis version`

## MariaDB specifics

- SQL prompt as the app user: `dc exec mariadb mariadb -uglint -pglintpw glint` (type `exit` to leave).
- The healthcheck script is inside the image: `dc exec mariadb cat /usr/local/bin/healthcheck.sh`.
- Transaction demo: `START TRANSACTION; INSERT ...; ROLLBACK;` leaves nothing; `COMMIT;` makes it permanent.

## Redis specifics

Connection details: host `127.0.0.1`, port `6379`, password `REDIS_PASSWORD` from `infra/.env`, default user, database `0`. The port is published on loopback only (`127.0.0.1:6379`), so only this machine can connect.

Run `PING` (expect `PONG`):
```bash
dc exec redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning ping'
```
Without the password (expect `NOAUTH Authentication required.`, which proves auth is enforced):
```bash
dc exec redis redis-cli ping
```
Interactive prompt (type `exit` to leave):
```bash
dc exec -it redis sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning'
```
From the Mac, using the locally installed CLI:
```bash
redis-cli -h 127.0.0.1 -p 6379 -a "<REDIS_PASSWORD>" --no-auth-warning ping
```

Useful commands inside the prompt: `set user:123 pod-2`, `set session:abc pod-1 EX 30`, `get user:123`, `ttl session:abc`, `keys *` (small labs only), `config get save`, `config get appendonly`.

Flags explained
| Flag / part | Meaning |
|---|---|
| `exec redis` | Run a command inside the running `redis` container |
| `sh -c '...'` | Run the quoted text through the container's shell so `$REDIS_PASSWORD` comes from the container environment |
| `-it` | Interactive terminal (needed for a prompt) |
| `-a "$REDIS_PASSWORD"` | Authenticate with the password |
| `--no-auth-warning` | Only suppresses the warning that a password on the command line is not safe (visible in `ps`, shell history, logs). Fine for this local playground; in real systems prefer the `REDISCLI_AUTH` environment variable or `AUTH` at the prompt |
| `--save ''` (server) | Disable RDB snapshots |
| `--appendonly no` (server) | Disable the append-only log (AOF) |

Why persistence is off: presence data has TTLs and is rewritten by heartbeats, so a restart should start empty. A restored snapshot could bring back stale "user is on pod X" entries. The database is the source of truth (D2). If AOF were on, a rewrite (`BGREWRITEAOF`, automatic when the file doubles and exceeds `auto-aof-rewrite-min-size`, here 64 MB) would compact the log; with it off there is nothing to clean. Check with `config get auto-aof-rewrite-*`.

Ways to access Redis
| Way | Notes |
|---|---|
| `redis-cli` in the container | Nothing to install |
| `redis-cli` on the Mac | Installed (`brew install redis`); works because the port is published on localhost |
| GUI (RedisInsight) | Installed (a cask: `brew install --cask redis-insight`, if that is the name; `brew search redis` to check). Connect to `127.0.0.1:6379` with the password. Useful in Module 8 to watch presence keys appear and expire |
| Application code | Spring Data Redis (Lettuce) in the services; password from the environment, never hard-coded |
| Raw TCP | `printf 'AUTH <pw>\r\nPING\r\n' \| nc 127.0.0.1 6379` - shows Redis speaks a simple text protocol |

Homebrew: no flag installs a formula (command-line tools, e.g. `redis`); `--cask` installs a macOS application (GUI apps such as RedisInsight). `brew info <name>` tells you which one a name is.

Restart experiment: `set user:123 pod-2`, then `dc restart redis`, then `get user:123` (expect nothing). Compare with a MariaDB table, which survives a restart.

## Experiment log (fill in what you observe)

- After `dc stop` / `dc start`:
- After `dc down` / `dc up -d --wait` (is the volume still listed?):
- After `dc down -v` / `dc up -d --wait` (does login still work? what is lost?):
