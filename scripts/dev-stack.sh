#!/usr/bin/env bash
# Start (or restart) one service of the local stack, detached, with local.env loaded.
#
#   scripts/dev-stack.sh core|agent|image-gen|all
#
# Logs go to .dev/<service>.log. local.env holds the ports and credentials; it is
# gitignored and never contains anything that belongs to a deployed environment.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"
mkdir -p .dev

# shellcheck disable=SC1091
set -a; . ./local.env; set +a

start() {
  local name="$1" jar="$2" port="$3"
  pkill -f "$jar" >/dev/null 2>&1 || true
  sleep 1
  SERVER_PORT="$port" setsid java -jar "$ROOT/$jar" > "$ROOT/.dev/$name.log" 2>&1 < /dev/null &
  disown || true
  printf '%-10s starting on :%s (log .dev/%s.log)\n' "$name" "$port" "$name"
}

wait_healthy() {
  local name="$1" port="$2" tries=60
  until curl -fsS -o /dev/null "http://localhost:$port/actuator/health"; do
    tries=$((tries - 1))
    [ "$tries" -le 0 ] && { echo "$name did not come up; see .dev/$name.log" >&2; return 1; }
    sleep 2
  done
  echo "$name healthy on :$port"
}

case "${1:-all}" in
  core)      start core mercala-core/target/mercala-core-0.0.1-SNAPSHOT.jar "${SERVER_PORT_CORE:-8090}"; wait_healthy core "${SERVER_PORT_CORE:-8090}" ;;
  agent)     start agent mercala-agent/target/mercala-agent-0.0.1-SNAPSHOT.jar "${SERVER_PORT_AGENT:-8092}"; wait_healthy agent "${SERVER_PORT_AGENT:-8092}" ;;
  image-gen) start image-gen mercala-image-gen/target/mercala-image-gen-0.0.1-SNAPSHOT.jar 8083; wait_healthy image-gen 8083 ;;
  all)
    "$0" core
    "$0" agent
    "$0" image-gen
    ;;
  *) echo "usage: $0 core|agent|image-gen|all" >&2; exit 2 ;;
esac
