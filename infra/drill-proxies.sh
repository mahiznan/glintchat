#!/usr/bin/env bash
# Failure-drill network: nginx in front of two chat-service instances, Toxiproxy between every component pair.
set -euo pipefail
cd "$(dirname "$0")/.."

docker rm -f drill-lb toxiproxy >/dev/null 2>&1 || true

docker run -d --name drill-lb -p 8091:8091 \
  -v "$PWD/infra/drill-lb.conf:/etc/nginx/nginx.conf:ro" nginx:1.25

docker run -d --name toxiproxy -p 8474:8474 \
  -p 61617:61617 -p 61618:61618 -p 61619:61619 -p 3307:3307 -p 8090:8090 -p 18081:18081 -p 18083:18083 \
  ghcr.io/shopify/toxiproxy:2.9.0
sleep 2

# The image's entrypoint is the Toxiproxy server, so the CLI is run inside the running container.
tp() { docker exec toxiproxy /toxiproxy-cli "$@"; }
tp create -l 0.0.0.0:61617 -u host.docker.internal:61616 artemis-gw1
tp create -l 0.0.0.0:61619 -u host.docker.internal:61616 artemis-gw2
tp create -l 0.0.0.0:61618 -u host.docker.internal:61616 artemis-cs
tp create -l 0.0.0.0:3307  -u host.docker.internal:3306  mariadb
tp create -l 0.0.0.0:8090  -u host.docker.internal:8091  cs-lb
tp create -l 0.0.0.0:18081 -u host.docker.internal:8081  gw1-ws
tp create -l 0.0.0.0:18083 -u host.docker.internal:8083  gw2-ws
tp list
