#!/usr/bin/env bash
# Boots the warehouse-service image against a real Postgres and checks that the
# native binary migrates the schema, seeds demo data (suppliers, location stocking),
# serves the warehouse locations and parcel views, validates backend-style
# RS256 JWTs against a JWKS, consumes the event feed, propagates correlation ids
# (X-Request-Id / traceparent, ECS JSON logs) and serves health, metrics and OpenAPI. Instead of a backend, a busybox server publishes the JWKS at
# /internal/v1/jwks.json and a one-event feed at /internal/v1/events (a CGI script
# behind an httpd proxy rule); the other checks only touch endpoints answered from
# the service's database.
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
# Event feed: one order.cancelled event for an order without a shipment (sequence 1), then nothing.
# The event carries the correlation ids of the backend request that wrote it; the script
# records the X-Request-Id and traceparent headers of every feed request.
mkdir -p "$WORK/www/cgi-bin"
cat > "$WORK/www/cgi-bin/events" <<'FEED'
#!/bin/sh
echo "$HTTP_X_REQUEST_ID $HTTP_TRACEPARENT" >> /tmp/feed-headers
printf 'Content-Type: application/json\r\n\r\n'
case "$QUERY_STRING" in
  after=0\&*) printf '%s' '{"events":[{"sequence":1,"id":"8e0d5f8c-6b7a-4f43-9c1e-5a7d2b9e0c11","type":"order.cancelled","occurredAt":"2026-11-11T09:30:15.123Z","orderId":999999,"username":"smoke","data":{"orderId":999999,"status":"CANCELLED","totalAmount":10.00,"failureCode":null},"requestId":"smoke-feed-request","traceId":"0af7651916cd43dd8448eb211c80319c"}],"nextCursor":1,"hasMore":false}' ;;
  *) printf '%s' '{"events":[],"nextCursor":1,"hasMore":false}' ;;
esac
FEED
chmod 755 "$WORK/www/cgi-bin/events"
printf '.json:application/json\nP:/internal/v1/events:http://127.0.0.1:4001/cgi-bin/events\n' > "$WORK/httpd.conf"
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
  -e WAREHOUSE_EVENTS_POLL_INTERVAL=PT1S \
  -e LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs \
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
curl -sf -H 'Host: awesome.byst.re' -H 'X-Forwarded-Host: awesome.byst.re' -H 'X-Forwarded-Proto: https' \
  -H 'X-Forwarded-Port: 443' "$base/v3/api-docs" | grep -q '"url":"https://awesome.byst.re"' \
  && echo "PASS OpenAPI server URL follows the gateway's forwarded headers" \
  || { echo "FAIL OpenAPI server URL ignores X-Forwarded-Proto" >&2; exit 1; }
expect "missing token" "$(status_of "$base/api/v1/admin/suppliers")" 401
expect "client role forbidden" "$(status_of -H "Authorization: Bearer $(token ROLE_CLIENT)" "$base/api/v1/admin/suppliers")" 403
suppliers=$(curl -sf -H "Authorization: Bearer $(token ROLE_ADMIN)" "$base/api/v1/admin/suppliers")
grep -q 'Nordic Electronics' <<< "$suppliers" && grep -q 'Baltic Gadgets' <<< "$suppliers" && echo "PASS demo suppliers seeded"
expect "tampered token" "$(status_of -H "Authorization: Bearer $(token ROLE_ADMIN)x" "$base/api/v1/admin/suppliers")" 401
expect "HMAC token signed with the public modulus" "$(status_of -H "Authorization: Bearer $(hmac_token)" "$base/api/v1/admin/suppliers")" 401
created=$(curl -sf -X POST -H "Authorization: Bearer $(token ROLE_ADMIN)" -H 'Content-Type: application/json' \
  -d '{"name":"Smoke Supply","email":"smoke@supply.test","leadTimeDays":3}' "$base/api/v1/admin/suppliers")
grep -q 'Smoke Supply' <<< "$created" && echo "PASS create supplier (JPA write in native image)"
expect "public tracking of an unknown number (V3 schema, no token)" \
  "$(status_of "$base/api/v1/tracking/1234567890")" 404
locations=$(curl -sf -H "Authorization: Bearer $(token ROLE_WAREHOUSE)" "$base/api/v1/warehouse/locations")
grep -q '"name":"Krakow Central"' <<< "$locations" && grep -q '"primaryCategories":\["Books"' <<< "$locations" \
  && echo "PASS warehouse locations (V6 schema) with the demo stocking split" \
  || { echo "FAIL locations: $locations" >&2; exit 1; }
deactivated=$(curl -sf -X PUT -H "Authorization: Bearer $(token ROLE_ADMIN)" -H 'Content-Type: application/json' \
  -d '{"active":false}' "$base/api/v1/warehouse/locations/gdn")
grep -q '"code":"GDN"' <<< "$deactivated" && grep -q '"active":false' <<< "$deactivated" && echo "PASS deactivate a location (JPA write, request binding)" \
  || { echo "FAIL deactivate: $deactivated" >&2; exit 1; }

psql_value() { docker exec "$RUN_ID-db" psql -U warehouse -d warehouse -tAc "$1"; }
for _ in $(seq 1 30); do
  [[ "$(psql_value "select last_sequence from warehouse.event_cursors where consumer = 'warehouse-service'")" == 1 ]] && break
  sleep 1
done
expect "event feed cursor (V4 schema, feed JSON binding)" \
  "$(psql_value "select last_sequence from warehouse.event_cursors where consumer = 'warehouse-service'")" 1
expect "processed event recorded" \
  "$(psql_value "select count(*) from warehouse.processed_events where event_id = '8e0d5f8c-6b7a-4f43-9c1e-5a7d2b9e0c11'")" 1

# A shipment written before 1.5.0 (V6 moves such rows to KRK with pending lines). The fake
# backend has no order snapshots, so its lines stay pending and read as [].
legacy_id=$(psql_value "insert into warehouse.shipments (order_id, username, location_code, total_units, status, created_by, last_actor, created_at, updated_at, lines_pending) values (777, 'smoke', 'KRK', 1, 'AWAITING_PICK', 'smoke', 'smoke', now(), now(), true) returning id" | head -n 1)
legacy=$(curl -sf -H "Authorization: Bearer $(token ROLE_WAREHOUSE)" "$base/api/v1/warehouse/shipments/$legacy_id")
grep -q '"locationName":"Krakow Central"' <<< "$legacy" && grep -q '"parcelCount":1' <<< "$legacy" \
  && grep -q '"parcelIndex":1' <<< "$legacy" && grep -q '"lines":\[\]' <<< "$legacy" \
  && echo "PASS parcel view of a pre-1.5.0 shipment (lines pending)" \
  || { echo "FAIL legacy shipment: $legacy" >&2; exit 1; }

header_of() { curl -s -o /dev/null -D - "${@:2}" | tr -d '\r' | awk -v name="$1" 'tolower($1) == tolower(name) ":" { print $2 }'; }
expect "valid X-Request-Id is returned" "$(header_of X-Request-Id -H 'X-Request-Id: smoke-req.1' "$base/actuator/health")" smoke-req.1
generated=$(header_of X-Request-Id -H 'X-Request-Id: not valid!' "$base/actuator/health")
[[ "$generated" =~ ^[0-9a-f-]{36}$ ]] && echo "PASS invalid X-Request-Id is replaced ($generated)" \
  || { echo "FAIL invalid X-Request-Id came back as '$generated'" >&2; exit 1; }
feed_headers=$(docker exec "$RUN_ID-jwks" tail -n 1 /tmp/feed-headers)
[[ "$feed_headers" =~ ^[0-9a-f-]{36}\ 00-[0-9a-f]{32}-[0-9a-f]{16}-01$ ]] \
  && echo "PASS feed requests send X-Request-Id and traceparent ($feed_headers)" \
  || { echo "FAIL feed request headers: '$feed_headers'" >&2; exit 1; }
event_log=$(docker logs "$RUN_ID-app" 2>&1 | grep '"requestId":"smoke-feed-request"' | grep 'Handled event' | head -n 1)
grep -q '"traceId":"0af7651916cd43dd8448eb211c80319c"' <<< "$event_log" && grep -q '"spanId":"[0-9a-f]\{16\}"' <<< "$event_log" \
  && echo "PASS ECS JSON log of the feed event carries its requestId, traceId and spanId" \
  || { echo "FAIL no ECS log line with the event's correlation ids" >&2; exit 1; }
echo "$event_log"

docker stats --no-stream --format 'memory: {{.MemUsage}}' "$RUN_ID-app"
echo "native smoke test passed"
