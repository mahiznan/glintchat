# Docker Compose commands (Module 0)

Run from the repo root. Optional shortcut for the current shell session:

```bash
alias dc='docker compose --env-file infra/.env -f infra/docker-compose.yml'
```

The long form is what the README documents: `docker compose --env-file infra/.env -f infra/docker-compose.yml <command>`.

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

## Experiment log (fill in what you observe)

- After `dc stop` / `dc start`:
- After `dc down` / `dc up -d --wait` (is the volume still listed?):
- After `dc down -v` / `dc up -d --wait` (does login still work? what is lost?):
