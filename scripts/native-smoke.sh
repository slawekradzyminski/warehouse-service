#!/usr/bin/env bash
# Boots the warehouse-service image against a real Postgres and checks that the
# native binary migrates the schema, seeds demo data, validates backend-style
# RS256 JWTs against a JWKS and serves health, metrics and OpenAPI. Instead of a
# backend, a static busybox server publishes the JWKS at /internal/v1/jwks.json;
# the other checks only touch endpoints answered from the service's database.
#   scripts/native-smoke.sh warehouse-service:native
set -euo pipefail

IMAGE="${1:?usage: native-smoke.sh IMAGE}"
BUSYBOX_IMAGE="busybox:1.37.0@sha256:a7dbc319b1438115a207e11930dc4bb3e643dcb3ec0bdd694bb36cd8fad289ec"
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
  docker rm -f "$RUN_ID-app" "$RUN_ID-db" "$RUN_ID-jwks" >/dev/null 2>&1 || true
  rm -rf "$WORK"
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
  exit $status
}
WORK=$(mktemp -d)
trap cleanup EXIT

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
hex_b64url() { tr -d '\n' | perl -e 'local $/; print pack("H*", <STDIN>)' | b64url; }

# RS256 signing key and its JWKS, published like the backend's /internal/v1/jwks.json.
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$WORK/key.pem" 2>/dev/null
MODULUS=$(openssl rsa -in "$WORK/key.pem" -noout -modulus | cut -d= -f2 | hex_b64url)
EXPONENT=$(printf '010001' | hex_b64url)
KID="native-smoke-key"
mkdir -p "$WORK/www/internal/v1"
printf '{"keys":[{"kty":"RSA","use":"sig","alg":"RS256","kid":"%s","n":"%s","e":"%s"}]}' \
  "$KID" "$MODULUS" "$EXPONENT" > "$WORK/www/internal/v1/jwks.json"
printf '.json:application/json\n' > "$WORK/httpd.conf"
chmod -R a+rX "$WORK/www" "$WORK/httpd.conf"

token() {
  local now header payload signature
  now=$(date +%s)
  header=$(printf '{"alg":"RS256","kid":"%s"}' "$KID" | b64url)
  payload=$(printf '{"sub":"smoke-admin","auth":[{"authority":"%s"}],"iat":%d,"exp":%d}' "$1" "$now" $((now + 600)) | b64url)
  signature=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -sign "$WORK/key.pem" -binary | b64url)
  printf '%s.%s.%s' "$header" "$payload" "$signature"
}
hmac_token() {
  local header payload signature
  header=$(printf '{"alg":"HS256","kid":"%s"}' "$KID" | b64url)
  payload=$(printf '{"sub":"smoke-admin","auth":[{"authority":"ROLE_ADMIN"}],"exp":%d}' $(( $(date +%s) + 600 )) | b64url)
  signature=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -hmac "$MODULUS" -binary | b64url)
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
docker run -d --name "$RUN_ID-jwks" --network "$NETWORK" -v "$WORK/www:/www:ro" \
  -v "$WORK/httpd.conf:/etc/httpd.conf:ro" "$BUSYBOX_IMAGE" httpd -f -p 4001 -h /www -c /etc/httpd.conf >/dev/null
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
  -e WAREHOUSE_SERVICE_TOKEN=native-smoke-service-token \
  -e BACKEND_BASE_URL="http://$RUN_ID-jwks:4001" \
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
expect "HMAC token signed with the public modulus" "$(status_of -H "Authorization: Bearer $(hmac_token)" "$base/api/v1/admin/suppliers")" 401
created=$(curl -sf -X POST -H "Authorization: Bearer $(token ROLE_ADMIN)" -H 'Content-Type: application/json' \
  -d '{"name":"Smoke Supply","email":"smoke@supply.test","leadTimeDays":3}' "$base/api/v1/admin/suppliers")
grep -q 'Smoke Supply' <<< "$created" && echo "PASS create supplier (JPA write in native image)"

docker stats --no-stream --format 'memory: {{.MemUsage}}' "$RUN_ID-app"
echo "native smoke test passed"
