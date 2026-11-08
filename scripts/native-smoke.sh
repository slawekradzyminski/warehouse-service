#!/usr/bin/env bash
# Boots the warehouse-service image against a real Postgres and checks that the
# native binary migrates the schema, seeds demo data, validates backend-style
# JWTs and serves health, metrics and OpenAPI. No backend is needed: the checks
# only touch endpoints that are answered from the service's own database.
#   scripts/native-smoke.sh warehouse-service:native
set -euo pipefail

IMAGE="${1:?usage: native-smoke.sh IMAGE}"
SECRET="native-smoke-jwt-key-with-at-least-32-bytes"
POSTGRES_IMAGE="postgres:16-alpine@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777"
RUN_ID="warehouse-smoke-$$"
NETWORK="$RUN_ID"
PORT="${SMOKE_PORT:-14003}"

cleanup() {
  status=$?
  if [[ $status -ne 0 ]]; then
    echo "--- warehouse-service log ---" >&2
    docker logs "$RUN_ID-app" >&2 2>&1 || true
  fi
  docker rm -f "$RUN_ID-app" "$RUN_ID-db" >/dev/null 2>&1 || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
  exit $status
}
trap cleanup EXIT

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
token() {
  local now header payload signature
  now=$(date +%s)
  header=$(printf '{"alg":"HS256"}' | b64url)
  payload=$(printf '{"sub":"smoke-admin","auth":[{"authority":"%s"}],"iat":%d,"exp":%d}' "$1" "$now" $((now + 600)) | b64url)
  signature=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -hmac "$SECRET" -binary | b64url)
  printf '%s.%s.%s' "$header" "$payload" "$signature"
}
status_of() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
expect() {
  if [[ "$2" != "$3" ]]; then
    echo "FAIL $1: expected $3, got $2" >&2
    exit 1
  fi
  echo "PASS $1"
}

docker network create "$NETWORK" >/dev/null
docker run -d --name "$RUN_ID-db" --network "$NETWORK" \
  -e POSTGRES_DB=warehouse -e POSTGRES_USER=warehouse -e POSTGRES_PASSWORD=warehouse \
  "$POSTGRES_IMAGE" >/dev/null
for _ in $(seq 1 60); do
  docker exec "$RUN_ID-db" pg_isready -U warehouse -d warehouse >/dev/null 2>&1 && break
  sleep 1
done

started=$(date +%s)
docker run -d --name "$RUN_ID-app" --network "$NETWORK" --memory 160m -p "$PORT:4003" \
  -e SPRING_PROFILES_ACTIVE=docker,demo \
  -e SPRING_DATASOURCE_URL="jdbc:postgresql://$RUN_ID-db:5432/warehouse?currentSchema=warehouse" \
  -e SPRING_DATASOURCE_USERNAME=warehouse -e SPRING_DATASOURCE_PASSWORD=warehouse \
  -e JWT_SECRET_KEY="$SECRET" -e WAREHOUSE_SERVICE_TOKEN=native-smoke-service-token \
  -e BACKEND_BASE_URL=http://backend.invalid:4001 \
  "$IMAGE" >/dev/null

base="http://localhost:$PORT"
for _ in $(seq 1 90); do
  [[ "$(status_of "$base/actuator/health")" == 200 ]] && break
  sleep 1
done
expect "health" "$(status_of "$base/actuator/health")" 200
echo "ready after $(( $(date +%s) - started ))s"

expect "prometheus metrics" "$(status_of "$base/actuator/prometheus")" 200
curl -sf "$base/v3/api-docs" | grep -q '"/api/v1/warehouse/queue"' && echo "PASS OpenAPI lists warehouse paths"
expect "missing token" "$(status_of "$base/api/v1/admin/suppliers")" 401
expect "client role forbidden" "$(status_of -H "Authorization: Bearer $(token ROLE_CLIENT)" "$base/api/v1/admin/suppliers")" 403
suppliers=$(curl -sf -H "Authorization: Bearer $(token ROLE_ADMIN)" "$base/api/v1/admin/suppliers")
grep -q 'Nordic Electronics' <<< "$suppliers" && grep -q 'Baltic Gadgets' <<< "$suppliers" && echo "PASS demo suppliers seeded"
expect "tampered token" "$(status_of -H "Authorization: Bearer $(token ROLE_ADMIN)x" "$base/api/v1/admin/suppliers")" 401
created=$(curl -sf -X POST -H "Authorization: Bearer $(token ROLE_ADMIN)" -H 'Content-Type: application/json' \
  -d '{"name":"Smoke Supply","email":"smoke@supply.test","leadTimeDays":3}' "$base/api/v1/admin/suppliers")
grep -q 'Smoke Supply' <<< "$created" && echo "PASS create supplier (JPA write in native image)"

docker stats --no-stream --format 'memory: {{.MemUsage}}' "$RUN_ID-app"
echo "native smoke test passed"
