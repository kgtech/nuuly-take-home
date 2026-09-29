#!/usr/bin/env bash
# Local gate: the same steps as (the workflow always runs the e2e job; here it is opt-in with --e2e) ai/final/ci-workflow.yml (move that file to .github/workflows/ci.yml
# once the GitHub token has the `workflow` scope). Usage: scripts/gate.sh [--e2e] [--service-only|--frontend-only]
# Ports: the app is published on APP_PORT (default 8080), Vite on VITE_PORT (default 5173).
set -euo pipefail
cd "$(dirname "$0")/.."
E2E=0; SERVICE=1; FRONT=1
for a in "$@"; do
  case "$a" in
    --e2e) E2E=1 ;;
    --service-only) FRONT=0 ;;
    --frontend-only) SERVICE=0 ;;
    *) echo "unknown option $a" >&2; exit 2 ;;
  esac
done
if [ "$SERVICE" = 1 ]; then ./gradlew build --console=plain; fi
if [ "$FRONT" = 1 ]; then
  (cd frontend && npm ci && npm run lint && npm run typecheck && npm run check:api && npm test && npm run build)
fi
if [ "$E2E" = 1 ]; then
  export APP_PORT="${APP_PORT:-8080}" VITE_PORT="${VITE_PORT:-5173}"
  export API_URL="http://localhost:${APP_PORT}"
  for port in "$APP_PORT" "$VITE_PORT"; do
    if lsof -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
      echo "port $port is already in use; free it or set APP_PORT / VITE_PORT" >&2; exit 1
    fi
  done
  project="final-gate-$$"
  trap 'docker compose -p "$project" down -v >/dev/null 2>&1 || true' EXIT
  docker compose -p "$project" up --build -d --wait
  for _ in $(seq 1 60); do
    code=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:${APP_PORT}/actuator/health/readiness" || true)
    [ "$code" = 200 ] && break
    sleep 3
  done
  [ "$code" = 200 ] || { docker compose -p "$project" logs app | tail -100; exit 1; }
  (cd frontend && npm ci && npx playwright install chromium && npm run test:e2e)
fi
echo "gate ok"
