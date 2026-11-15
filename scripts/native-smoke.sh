#!/usr/bin/env bash
# Boots the warehouse-service image against a real Postgres and checks that the
# native binary migrates the schema, seeds demo data (suppliers, location stocking),
# serves the warehouse locations and parcel views, validates backend-style
# RS256 JWTs against a JWKS, consumes the event feed, propagates correlation ids
# (X-Request-Id / traceparent, ECS JSON logs), imports and serves the stock ledger
# (V7 schema, backend service token, reserve/confirm/adjust/items, restore from the
# feed, level push, reaper) and serves health, metrics and OpenAPI. Instead of a
# backend, a busybox server publishes the JWKS at /internal/v1/jwks.json and CGI
# scripts behind httpd proxy rules answer the feed (/internal/v1/events, with order.cancelled and
# stock.restore-requested), the ledger
# export (/internal/v1/inventory/export), the level push (/internal/v1/inventory/levels)
# and the reaper's order lookup (/internal/v1/orders/by-reservation/{id}, always 404);
# the other checks only touch endpoints answered from the service's database.
#   scripts/native-smoke.sh warehouse-service:native
set -euo pipefail

IMAGE="${1:?usage: native-smoke.sh IMAGE}"
BUSYBOX_IMAGE="busybox:1.37.0@sha256:a7dbc319b1438115a207e11930dc4bb3e643dcb3ec0bdd694bb36cd8fad289ec"
POSTGRES_IMAGE="postgres:16-alpine@sha256:57c72fd2a128e416c7fcc499958864df5301e940bca0a56f58fddf30ffc07777"
RUN_ID="warehouse-smoke-$$"
NETWORK="$RUN_ID"
PORT="${SMOKE_PORT:-14003}"
TRACING="${SMOKE_TRACING:-false}"
BACKEND_TOKEN="native-smoke-backend-token"
PRODUCT=501

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
# Event feed: one order.cancelled event for an order without a shipment (sequence 1) and the
# backend's internal stock.restore-requested of the same cancel (sequence 2), then nothing. The
# restore puts one unit of product 501 back once the ledger is imported.
# The event carries the correlation ids of the backend request that wrote it; the script
# records the X-Request-Id and traceparent headers of every feed request.
mkdir -p "$WORK/www/cgi-bin"
cat > "$WORK/www/cgi-bin/events" <<'FEED'
#!/bin/sh
echo "$HTTP_X_REQUEST_ID $HTTP_TRACEPARENT" >> /tmp/feed-headers
printf 'Content-Type: application/json\r\n\r\n'
case "$QUERY_STRING" in
  after=0\&*) printf '%s' '{"events":[{"sequence":1,"id":"8e0d5f8c-6b7a-4f43-9c1e-5a7d2b9e0c11","type":"order.cancelled","occurredAt":"2026-11-11T09:30:15.123Z","orderId":999999,"username":"smoke","data":{"orderId":999999,"status":"CANCELLED","totalAmount":10.00,"failureCode":null},"requestId":"smoke-feed-request","traceId":"0af7651916cd43dd8448eb211c80319c","spanId":"b7ad6b7169203331"},{"sequence":2,"id":"3c9a1f6e-2b7d-4e8a-9f10-6d5e4c3b2a19","type":"stock.restore-requested","occurredAt":"2026-11-11T09:30:15.124Z","orderId":999999,"username":"smoke","data":{"orderId":999999,"actor":"smoke","lines":[{"productId":501,"quantity":1}]},"requestId":"smoke-feed-request","traceId":"0af7651916cd43dd8448eb211c80319c","spanId":"b7ad6b7169203331"}],"nextCursor":2,"hasMore":false}' ;;
  *) printf '%s' '{"events":[],"nextCursor":2,"hasMore":false}' ;;
esac
FEED
chmod 755 "$WORK/www/cgi-bin/events"
# The backend's former ledger: product 501 with two movements (ids 41 and 42 are kept by the import).
cat > "$WORK/www/cgi-bin/export" <<'EXPORT'
#!/bin/sh
echo "$QUERY_STRING" >> /tmp/export-queries
printf 'Content-Type: application/json\r\n\r\n'
printf '%s' '{"items":[{"productId":501,"quantity":7,"movements":[{"id":41,"orderId":null,"type":"INITIAL_STOCK","delta":10,"quantityAfter":10,"actor":"admin","reason":"Initial stock","requestId":null,"createdAt":"2026-11-01T09:15:30.123456"},{"id":42,"orderId":null,"type":"ADMIN_ADJUSTMENT","delta":-3,"quantityAfter":7,"actor":"admin","reason":"Damaged","requestId":"6f0f8f8e-1b2c-4d5e-8f90-a1b2c3d4e5f6","createdAt":"2026-11-02T10:00:00"}]}],"nextAfterProductId":null}'
EXPORT
chmod 755 "$WORK/www/cgi-bin/export"
# Projection push: records each body with the service token it came with.
cat > "$WORK/www/cgi-bin/levels" <<'LEVELS'
#!/bin/sh
printf '%s %s\n' "$HTTP_X_SERVICE_TOKEN" "$(head -c "${CONTENT_LENGTH:-0}")" >> /tmp/levels
printf 'Content-Type: application/json\r\n\r\n'
printf '%s' '{"applied":1,"ignored":0}'
LEVELS
chmod 755 "$WORK/www/cgi-bin/levels"
# The reaper's order lookup: no order exists for any reservation.
cat > "$WORK/www/cgi-bin/by-reservation" <<'BYRESERVATION'
#!/bin/sh
echo "$REQUEST_URI" >> /tmp/by-reservation
printf 'HTTP/1.1 404 Not Found\r\nContent-Type: application/json\r\n\r\n'
printf '%s' '{"message":"Order not found"}'
BYRESERVATION
chmod 755 "$WORK/www/cgi-bin/by-reservation"
# OTLP HTTP receiver: appends every export request body to /tmp/traces (an empty body is a valid response).
cat > "$WORK/www/cgi-bin/traces" <<'TRACES'
#!/bin/sh
head -c "${CONTENT_LENGTH:-0}" >> /tmp/traces
printf 'Content-Type: application/x-protobuf\r\n\r\n'
TRACES
chmod 755 "$WORK/www/cgi-bin/traces"
{
  printf '.json:application/json\n'
  printf 'P:/internal/v1/events:http://127.0.0.1:4001/cgi-bin/events\n'
  printf 'P:/internal/v1/inventory/export:http://127.0.0.1:4001/cgi-bin/export\n'
  printf 'P:/internal/v1/inventory/levels:http://127.0.0.1:4001/cgi-bin/levels\n'
  printf 'P:/internal/v1/orders/by-reservation/:http://127.0.0.1:4001/cgi-bin/by-reservation/\n'
} > "$WORK/httpd.conf"
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
  -e BACKEND_SERVICE_TOKEN="$BACKEND_TOKEN" \
  -e WAREHOUSE_RESERVATION_CONFIRM_TIMEOUT=PT2S \
  -e WAREHOUSE_RESERVATION_REAPER_INTERVAL=PT1S \
  -e WAREHOUSE_STOCK_PUSH_RETRY_INTERVAL=PT1S \
  -e BACKEND_BASE_URL="http://$RUN_ID-jwks:4001" \
  -e WAREHOUSE_EVENTS_POLL_INTERVAL=PT1S \
  -e LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs \
  -e TRACING_ENABLED="$TRACING" \
  -e TRACING_OTLP_ENDPOINT="http://$RUN_ID-jwks:4001/cgi-bin/traces" \
  "$IMAGE" >/dev/null

base="http://localhost:$PORT"
for _ in $(seq 1 90); do
  [[ "$(status_of "$base/actuator/health")" == 200 ]] && break
  sleep 1
done
expect "health" "$(status_of "$base/actuator/health")" 200
echo "ready after $(( $(date +%s) - started ))s"

expect "prometheus metrics" "$(status_of "$base/actuator/prometheus")" 200
metrics=$(curl -sf "$base/actuator/prometheus")
grep -q '^warehouse_outbox_pending 0.0$' <<< "$metrics" && grep -q '^warehouse_outbox_failed_total 0.0$' <<< "$metrics" \
  && echo "PASS outbox metrics (pending gauge query, failed counter)" \
  || { echo "FAIL outbox metrics missing: $(grep warehouse_outbox <<< "$metrics")" >&2; exit 1; }
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
  [[ "$(psql_value "select last_sequence from warehouse.event_cursors where consumer = 'warehouse-service'")" == 2 ]] && break
  sleep 1
done
expect "event feed cursor (V4 schema, feed JSON binding)" \
  "$(psql_value "select last_sequence from warehouse.event_cursors where consumer = 'warehouse-service'")" 2
expect "processed events recorded (cancel and restore)" \
  "$(psql_value "select count(*) from warehouse.processed_events where event_id in ('8e0d5f8c-6b7a-4f43-9c1e-5a7d2b9e0c11', '3c9a1f6e-2b7d-4e8a-9f10-6d5e4c3b2a19')")" 2

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
# Tracing off: the hand-built child traceparent (sampled). Tracing on: the instrumented client's
# span inside the poll run, which is deliberately not recorded (flags 00).
[[ "$TRACING" == true ]] && feed_flags=00 || feed_flags=01
[[ "$feed_headers" =~ ^[0-9a-f-]{36}\ 00-[0-9a-f]{32}-[0-9a-f]{16}-$feed_flags$ ]] \
  && echo "PASS feed requests send X-Request-Id and traceparent ($feed_headers)" \
  || { echo "FAIL feed request headers: '$feed_headers'" >&2; exit 1; }
event_log=$(docker logs "$RUN_ID-app" 2>&1 | grep '"requestId":"smoke-feed-request"' | grep 'Handled event' | head -n 1)
grep -q '"traceId":"0af7651916cd43dd8448eb211c80319c"' <<< "$event_log" && grep -q '"spanId":"[0-9a-f]\{16\}"' <<< "$event_log" \
  && echo "PASS ECS JSON log of the feed event carries its requestId, traceId and spanId" \
  || { echo "FAIL no ECS log line with the event's correlation ids" >&2; exit 1; }
echo "$event_log"

if [[ "$TRACING" == true ]]; then
  expect "traced request" "$(status_of -H 'traceparent: 00-0af7651916cd43dd8448eb211c80319d-b7ad6b7169203332-01' "$base/actuator/health")" 200
  # The batch span processor exports every 5 seconds; protobuf keeps strings readable.
  for _ in $(seq 1 30); do
    exported=$(docker exec "$RUN_ID-jwks" sh -c 'cat /tmp/traces 2>/dev/null || true' | tr -c '[:print:]' ' ')
    grep -q 'process order.cancelled' <<< "$exported" && grep -q 'http get /actuator/health' <<< "$exported" && break
    sleep 1
  done
  grep -q 'warehouse-service' <<< "$exported" && grep -q 'process order.cancelled' <<< "$exported" \
    && grep -q 'http get /actuator/health' <<< "$exported" \
    && echo "PASS OTLP HTTP export of the consumer and server spans (service warehouse-service)" \
    || { echo "FAIL no OTLP export with the expected spans ($(wc -c <<< "$exported") bytes): $exported" >&2; exit 1; }
else
  sleep 6
  [[ -z "$(docker exec "$RUN_ID-jwks" sh -c 'cat /tmp/traces 2>/dev/null')" ]] \
    && echo "PASS tracing off: nothing exported" \
    || { echo "FAIL tracing off exported spans" >&2; exit 1; }
fi

# --- stock ledger (V7) ---------------------------------------------------------------
new_uuid() { cat /proc/sys/kernel/random/uuid 2>/dev/null || uuidgen | tr 'A-Z' 'a-z'; }
stock() { curl -s -H "Authorization: Bearer $BACKEND_TOKEN" -H 'Content-Type: application/json' "$@"; }
stock_status() { status_of -H "Authorization: Bearer $BACKEND_TOKEN" -H 'Content-Type: application/json' "$@"; }
for _ in $(seq 1 30); do
  [[ "$(stock_status "$base/internal/v1/stock/items/$PRODUCT")" == 200 ]] && break
  sleep 1
done
expect "readiness after the ledger import" "$(status_of "$base/actuator/health/readiness")" 200
grep -qx 'limit=500' <<< "$(docker exec "$RUN_ID-jwks" cat /tmp/export-queries)" \
  && echo "PASS ledger export requested (limit=500)" || { echo "FAIL no export request" >&2; exit 1; }
expect "stock API without token" "$(status_of "$base/internal/v1/stock/items/$PRODUCT")" 401
expect "stock API with a user JWT" "$(status_of -H "Authorization: Bearer $(token ROLE_ADMIN)" "$base/internal/v1/stock/items/$PRODUCT")" 401
for _ in $(seq 1 30); do
  [[ "$(psql_value "select count(*) from warehouse.stock_restores where order_id = 999999")" == 1 ]] && break
  sleep 1
done
item=$(stock "$base/internal/v1/stock/items/$PRODUCT")
[[ "$item" == '{"productId":501,"quantity":8,"ledgerVersion":2}' ]] \
  && echo "PASS ledger imported (7 units, version 1) and stock.restore-requested applied once (8, version 2)" \
  || { echo "FAIL stock item after import and restore: $item" >&2; exit 1; }
movements=$(stock "$base/internal/v1/stock/movements?productId=$PRODUCT&size=5")
grep -q '"id":41,"productId":501,"orderId":null,"type":"INITIAL_STOCK","delta":10,"quantityAfter":10,"actor":"admin","reason":"Initial stock","requestId":null,"createdAt":"2026-11-01T09:15:30.123456"}' <<< "$movements" \
  && grep -q '"orderId":999999,"type":"ORDER_RESTORED","delta":1,"quantityAfter":8,"actor":"smoke","reason":"Order cancellation"' <<< "$movements" \
  && grep -q '"totalElements":3' <<< "$movements" \
  && echo "PASS movements keep the imported ids and the backend's JSON (nulls, microseconds)" \
  || { echo "FAIL movements: $movements" >&2; exit 1; }
reservation=$(new_uuid)
reserve_body="{\"reservationId\":\"$reservation\",\"username\":\"smoke\",\"lines\":[{\"productId\":$PRODUCT,\"quantity\":2}]}"
reserved=$(stock -X POST -d "$reserve_body" "$base/internal/v1/stock/reservations")
[[ "$reserved" == "{\"reservationId\":\"$reservation\",\"lines\":[{\"productId\":501,\"quantityAfter\":6,\"ledgerVersion\":3}]}" ]] \
  && echo "PASS reserve (JPA lock and insert, record binding)" || { echo "FAIL reserve: $reserved" >&2; exit 1; }
expect "confirm" "$(stock_status -X POST -d '{"orderId":4242}' "$base/internal/v1/stock/reservations/$reservation/confirm")" 200
# JSON bodies go through variables: inline in "$(...)" bash would brace-expand them.
too_many="{\"reservationId\":\"$(new_uuid)\",\"username\":\"smoke\",\"lines\":[{\"productId\":$PRODUCT,\"quantity\":99}]}"
expect "insufficient stock" "$(stock_status -X POST -d "$too_many" "$base/internal/v1/stock/reservations")" 409
adjustment="{\"productId\":$PRODUCT,\"delta\":4,\"reason\":\"Recount\",\"requestId\":\"$(new_uuid)\",\"actor\":\"admin\"}"
adjusted=$(stock -X POST -d "$adjustment" "$base/internal/v1/stock/adjustments")
grep -q '"type":"ADMIN_ADJUSTMENT","delta":4,"quantityAfter":10,' <<< "$adjusted" && grep -q '"ledgerVersion":4}$' <<< "$adjusted" \
  && echo "PASS adjust" || { echo "FAIL adjust: $adjusted" >&2; exit 1; }
expect "create a stock item" "$(stock_status -X PUT -d '{"initialQuantity":3,"actor":"admin"}' "$base/internal/v1/stock/items/502")" 201
expect "existing stock item" "$(stock_status -X PUT -d '{"initialQuantity":3,"actor":"admin"}' "$base/internal/v1/stock/items/502")" 200
expect "delete a stock item" "$(stock_status -X DELETE "$base/internal/v1/stock/items/502")" 204
expect "release of an unknown reservation" "$(stock_status -X POST "$base/internal/v1/stock/reservations/$(new_uuid)/release")" 404
# A reservation that is never confirmed: the reaper asks the backend (404) and releases it.
orphan=$(new_uuid)
orphan_body="{\"reservationId\":\"$orphan\",\"username\":\"smoke\",\"lines\":[{\"productId\":$PRODUCT,\"quantity\":1}]}"
expect "reserve an orphan" "$(stock_status -X POST -d "$orphan_body" "$base/internal/v1/stock/reservations")" 201
for _ in $(seq 1 30); do
  [[ "$(psql_value "select status from warehouse.stock_reservations where reservation_id = '$orphan'")" == RELEASED ]] && break
  sleep 1
done
expect "reaper released the orphan" "$(psql_value "select status from warehouse.stock_reservations where reservation_id = '$orphan'")" RELEASED
# The proxy rule rewrites the path: the CGI sees /cgi-bin/by-reservation/<id>.
docker exec "$RUN_ID-jwks" grep -q "/by-reservation/$orphan" /tmp/by-reservation \
  && echo "PASS reaper asked the backend for the orphan's order" || { echo "FAIL no by-reservation lookup" >&2; exit 1; }
for _ in $(seq 1 30); do
  pushed=$(docker exec "$RUN_ID-jwks" sh -c 'cat /tmp/levels 2>/dev/null || true')
  grep -q '"ledgerVersion":6' <<< "$pushed" && break
  sleep 1
done
# Versions: 1 import, 2 feed restore, 3 reserve, 4 adjust, 5 orphan reserve, 6 reaper release.
# A push sends the newest level per product, so intermediate versions may be coalesced.
[[ -n "$pushed" ]] && ! grep -qv '^native-smoke-service-token {"levels":\[' <<< "$pushed" \
  && grep -q '{"productId":501,"quantity":10,"ledgerVersion":6}' <<< "$pushed" \
  && echo "PASS levels pushed to the backend projection with X-Service-Token (newest: 10 units, version 6)" \
  || { echo "FAIL level pushes: $pushed" >&2; exit 1; }
metrics=$(curl -sf "$base/actuator/prometheus")
grep -q '^stock_outbox_pending 0.0$' <<< "$metrics" && grep -q '^stock_reservations_reaped_total 1.0$' <<< "$metrics" \
  && echo "PASS stock metrics (outbox pending gauge, reaped counter)" \
  || { echo "FAIL stock metrics: $(grep '^stock_' <<< "$metrics")" >&2; exit 1; }
curl -sf "$base/v3/api-docs" | grep -q '"/internal/v1/stock/reservations"' && echo "PASS OpenAPI lists the stock API"

docker stats --no-stream --format 'memory: {{.MemUsage}}' "$RUN_ID-app"
echo "native smoke test passed"
