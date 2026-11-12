# warehouse-service

Warehouse fulfilment for the Awesome Testing stack: shipments, returns (RMA),
suppliers and purchase orders. Extracted from `test-secure-backend`; the
backend keeps users, JWT issuance, products, stock, carts, coupons and orders.

- Contract: `awesome-localstack/docs/WAREHOUSE_SERVICE_CONTRACT.md`
- Business rules: Tier 2 of `test-secure-backend/docs/COMMERCE_AND_FULFILMENT.md`
- Stack: Spring Boot 4.1 / Java 25, Spring AOT, GraalVM native image
- Port: 4003 · OpenAPI: `/v3/api-docs` · Health: `/actuator/health` · Metrics: `/actuator/prometheus`

## Public API

Paths and bodies are identical to the backend's former endpoints:

| Path | Roles |
| --- | --- |
| `/api/v1/warehouse/**` (queue, shipments, pick list, pick/pack/ship/deliver/cancel) | `ROLE_WAREHOUSE`, `ROLE_ADMIN` |
| `GET /api/v1/orders/{id}/shipment`, `GET /api/v1/orders/{id}/shipment/tracking` | owner, or `ROLE_ADMIN` for any order |
| `GET /api/v1/tracking/{trackingNumber}` | public (no token) |
| `POST /api/v1/orders/{orderId}/returns`, `GET /api/v1/returns`, `GET /api/v1/returns/{id}` | any authenticated user, own orders only |
| `/api/v1/admin/returns/**`, `/api/v1/admin/suppliers`, `/api/v1/admin/purchase-orders/**`, `GET /api/v1/admin/inventory/reorder-suggestions` | `ROLE_ADMIN` |

Authentication uses the backend's RS256 access tokens (roles in the `auth`
claim). The service holds no signing secret: it verifies tokens with the public
keys from the backend's `GET /internal/v1/jwks.json`, cached by `kid` and
refetched when an unknown `kid` appears (at most once per
`JWT_JWKS_REFRESH_INTERVAL`), so backend key rotations and restarts are picked
up. Other algorithms, including HMAC, are rejected. There is no user database.
401/403 bodies are `{"message": ...}`; while the backend is unreachable and the
key is not cached yet, requests get 503.

## How it talks to the backend

`BackendClient` (Spring `RestClient`) calls `/internal/v1` on
`backend.base-url` with `X-Service-Token`. Writes follow "validate locally, call
the backend, then persist", so a failed backend call leaves no local change:

- create shipment: `GET order` → save shipment → `PUT fulfilment AWAITING_PICK`
  (the shipment row is removed again if the backend refuses). The backend
  accepts a repeated `AWAITING_PICK`, so a release whose response was lost
  is recovered: on 503 the order is read again and the shipment is kept if
  the release reached the backend, and an order already `AWAITING_PICK`
  without a shipment can be released again (it is adopted);
- pick/pack/ship/deliver: `PUT fulfilment` first; ship/deliver then queue an
  `ORDER_SHIPPED`/`ORDER_DELIVERED` notification (and record a tracking scan,
  see "Shipment tracking");
- cancel: `POST warehouse-cancel`, then the shipment becomes `CANCELLED` (also
  when the backend reports the order as already cancelled, so a retried
  cancel succeeds);
- receive return: `POST inventory/receipts` (`RETURN_RESTOCKED`,
  `requestId = UUID.nameUUIDFromBytes("return-" + id)`) for resellable lines;
- refund return: `POST orders/{id}/refunds` with the proportional amount
  (`requestId = UUID.nameUUIDFromBytes("refund-" + id)`), then the return
  becomes `REFUNDED`; the backend rejects refunds above the order total;
- approve/reject/refund return: `RETURN_UPDATED` notification;
- receive purchase order: `POST inventory/receipts` (`PURCHASE_RECEIVED`, the receipt's `requestId`).

Backend 4xx responses keep status and message. 5xx, connection failures and a
rejected service token become 503 `{"message": "Backend unavailable"}`.

Customer notifications go through a transactional outbox
(`notification_outbox`): the row is written in the same transaction as the
shipment or return change, sent to `POST notifications` right after the
commit, and retried every 30 seconds with exponential backoff (up to 10
attempts) while the backend answers 503. Any other rejection is final. Each
row carries a `requestId`, and the backend sends one email per `requestId`, so
retries never duplicate emails. The notification carries only the template and
its properties; the backend renders the text. The after-commit send runs on a
single `notification-dispatch` thread, never on the committing request thread,
so the request releases its connection without waiting for the backend.

Connection budget: the pool has 3 connections, so a request never needs a
second one while it holds one (no `REQUIRES_NEW` under an open transaction, no
after-commit work on the request thread) and never waits on a lock or monitor
while holding one, except for the shipment, return or purchase order row lock
it took itself. Backend reads that need no lock (release snapshot, pick list,
order tracking, reconciliation, the order of a new return) run outside
transactions; only the backend mirror of a state transition runs while its
row lock, and so its single connection, is held (bounded by the 10 s backend
read timeout). `ConnectionPoolFlowTest` drives eight concurrent fulfilments
and returns against a pool of 3.

A customer may cancel an order on the backend; the backend never calls this
service, so cancellations reach it through the **event feed** (see below)
within seconds. `ShipmentReconciler` stays as a safety net and runs every ten
minutes: it cancels `AWAITING_PICK` shipments whose order is `CANCELLED`
(`lastActor = "system"`) and adopts orders the backend reports as
`AWAITING_PICK` without a shipment (older than two minutes, so in-flight
releases are left alone). Between runs, reading the shipment
(`GET /api/v1/warehouse/shipments/{id}`) or a refused transition reconciles it
immediately, and the transition answers 409 "Order was cancelled by the
customer".

### Event feed

`EventFeedPoller` reads the backend's durable domain event feed every
`warehouse.events.poll-interval` (default `PT5S`):
`GET /internal/v1/events?after={cursor}&limit=100&types=order.cancelled`, which
answers `{events: [{sequence, id, type, occurredAt, orderId, username, data}],
nextCursor, hasMore}` ordered by `sequence`. A run follows `hasMore` for up to
ten pages.

- `order.cancelled`: the order's shipment becomes `CANCELLED`
  (`lastActor = "system"`, `cancelledAt` set) when it is `AWAITING_PICK` or
  `PICKED`. The backend is not called, because the order is already cancelled
  there. `PACKED` and `SHIPPED` shipments are left alone and logged (the
  backend refuses those cancellations); finished shipments and orders without
  a shipment are ignored. A warehouse cancellation echoed by the feed is a no-op.
- Each event is handled in its own transaction that locks the consumer's row in
  `event_cursors`, skips event ids already in `processed_events`, applies the
  change, records the event id and advances the cursor, so handling is
  idempotent and several instances never process one event concurrently. After
  a page the cursor moves to `nextCursor` (past events of other types).
- Backend unavailable or a 4xx from the feed: logged, the run ends and the next
  one resumes from the stored cursor. An event whose handling fails (for
  example one without an `orderId`) is logged with its id and recorded as
  processed, so it never blocks the feed.

The feed needs backend 3.16.0 or later. Against an older backend the request
answers 404, which is logged on every run; the reconciler still cancels
shipments, only later.

Return requests for the same order are serialised with a database row lock
(`order_locks`, `SELECT ... FOR UPDATE`), so the returnable-quantity check holds
across several instances.

Local tables (Flyway `V1`–`V5`, schema `warehouse`): `shipments` (order id, owner and
unit count snapshot, `simulated` flag), `tracking_events` (carrier scans), `return_requests` (order id plus order total/subtotal
snapshot for the proportional refund), `return_items` (product id, name and
unit price snapshot), `suppliers`, `purchase_orders`, `purchase_order_lines`
(product id and name snapshot), `purchase_receipts`, `notification_outbox` (with the enqueuing request's `correlation_id` and `traceparent`), `order_locks`,
`event_cursors` (last feed sequence per consumer) and `processed_events` (handled feed event ids).

## Correlation IDs and logging

- `CorrelationFilter` runs before every other filter. It keeps a valid incoming
  `X-Request-Id` (1–128 characters of `[A-Za-z0-9._-]`) and the trace id of a
  valid W3C `traceparent` (`00-<32 hex>-<16 hex>-<2 hex>`, ids not all zeros);
  anything missing or invalid is generated (a UUID request id, a random trace
  id). Every request gets a new span id. `requestId`, `traceId` and `spanId` are
  in the MDC for the request, `X-Request-Id` is returned on every response
  (also 401/403), and the MDC is cleared afterwards.
- Every backend call (`RestBackendClient`) sends the current `X-Request-Id` and a
  child `traceparent` (same trace id, new parent id).
- Event feed: each event is handled (and logged) under the `requestId` and
  `traceId` of the backend request that wrote it (optional feed fields from
  backend 3.17.0); events without them get generated ids.
- Notification outbox: the ids are stored at enqueue time (`correlation_id`,
  `traceparent`) and restored for delivery, so the `POST
  /internal/v1/notifications` carries the request id of the request that
  caused it, also on a retry. Rows from before 1.4.0 get fresh ids.
- Scheduled jobs (event poll, reconciler, carrier simulator, notification
  retries) run each tick under a generated request and trace id.
- Logs: the default console lines include `[traceId,requestId]`.
  `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` (what the server Compose sets)
  writes one ECS JSON object per line with `requestId`, `traceId` and `spanId`
  as fields; it works in the native image (`scripts/native-smoke.sh` checks it).

## Shipment tracking

`POST /api/v1/warehouse/shipments/{id}/ship` with a `trackingNumber` is the
manual flow, unchanged. Omitting `trackingNumber` (or sending `null`) hands the
parcel to the **simulated carrier**: the service generates a tracking number in
the carrier's format and marks the shipment `simulated`. A blank string is
still rejected with 400.

Every shipped shipment records scans in `tracking_events`: `LABEL_CREATED` at
the hand-over and `DELIVERED` on a manual delivery confirmation. For simulated
shipments, `CarrierSimulator` runs every `warehouse.carrier.step-interval`
(default `PT2M`) and advances each simulated `SHIPPED` shipment whose latest
scan is at least one interval old by one scan (batches of 100, each under a row
lock in its own transaction, so a repeated or concurrent run is a no-op):

```
LABEL_CREATED -> IN_TRANSIT (carrier hub) -> IN_TRANSIT (delivery depot)
  -> OUT_FOR_DELIVERY -> DELIVERED
```

If `abs(trackingNumber.hashCode()) % 5 == 0`, the first `OUT_FOR_DELIVERY` is
followed by `DELIVERY_FAILED` and the next step goes `OUT_FOR_DELIVERY` again.
`OUT_FOR_DELIVERY` and `DELIVERY_FAILED` queue `SHIPMENT_OUT_FOR_DELIVERY` /
`SHIPMENT_DELIVERY_FAILED` notifications (`orderId`, `carrier`,
`trackingNumber`). The `DELIVERED` step is the regular delivery (backend
`PUT fulfilment DELIVERED`, `ORDER_DELIVERED` email, `lastActor = "carrier"`); a
backend outage leaves the parcel `OUT_FOR_DELIVERY` and ends the run. Scan
locations and texts are deterministic (each carrier's hub; the delivery city is
derived from the tracking number).

`estimatedDelivery`: the delivery time once delivered; for a simulated parcel
the latest scan plus one step interval per remaining scan; for a manual parcel
the hand-over plus the carrier's nominal transit days (InPost 1, DPD 2, DHL 2,
UPS 3); `null` before the hand-over.

| Endpoint | Access | Response |
| --- | --- | --- |
| `GET /api/v1/orders/{id}/shipment/tracking` | owner, `ROLE_ADMIN` | `TrackingDto`; 404 like `GET .../shipment` |
| `GET /api/v1/tracking/{trackingNumber}` | public | `PublicTrackingDto` (no `orderId`, owner or address); 404 "Tracking number not found" |
| `POST /api/v1/warehouse/shipments/{id}/carrier/advance` | `ROLE_WAREHOUSE`, `ROLE_ADMIN` | one scan now, ignoring the interval; `TrackingDto`; 409 unless simulated and `SHIPPED` |

```json
{
  "shipmentId": 1, "orderId": 42, "carrier": "DHL", "trackingNumber": "1234567890",
  "simulated": true, "currentStatus": "IN_TRANSIT", "estimatedDelivery": "2026-11-10T12:08:00",
  "events": [
    {"status": "LABEL_CREATED", "location": "Awesome Testing Warehouse, Krakow",
     "description": "Shipping label created; the parcel is waiting for DHL pickup", "occurredAt": "2026-11-10T12:00:00"},
    {"status": "IN_TRANSIT", "location": "DHL Hub, Leipzig",
     "description": "Collected from the sender and sorted at the hub", "occurredAt": "2026-11-10T12:02:00"}
  ]
}
```

## Run

Requires Java 25 (Maven comes from the wrapper). The service is
PostgreSQL-only in every profile; H2 is a test-scope dependency for the fast
JVM unit tests only.

```bash
# Next to the backend's local profile (same localhost:5432/testdb, schema "warehouse"),
# backend at http://localhost:4001, demo suppliers seeded
./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# Against PostgreSQL (schema "warehouse" is created by Flyway)
SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5432/testdb?currentSchema=warehouse' ./mvnw spring-boot:run
```

Profiles:

| Profile | Database | Backend | Notes |
| --- | --- | --- | --- |
| (none) | Postgres `jdbc:postgresql://localhost:5432/testdb?currentSchema=warehouse` | `http://localhost:4001` | token `local-warehouse-token` |
| `local` | Postgres `jdbc:postgresql://localhost:5432/testdb?currentSchema=warehouse` (user/password `postgres`) | `http://localhost:4001` | token `local-warehouse-token`, seeds demo suppliers, carrier step `PT30S` |
| `docker` | Postgres `jdbc:postgresql://postgres:5432/testdb?currentSchema=warehouse` | `http://backend:4001` | `WAREHOUSE_SERVICE_TOKEN` required |
| `demo` | – | – | combine as `docker,demo` to seed the demo suppliers; carrier step `PT30S` |

Demo suppliers (idempotent): "Nordic Electronics" (orders@nordic-electronics.test,
7 days) and "Baltic Gadgets" (purchasing@baltic-gadgets.test, 14 days).

### Environment variables

| Variable | Property | Default |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | active profiles | none (Postgres on localhost) |
| `JWT_JWKS_REFRESH_INTERVAL` | `security.jwt.jwks-refresh-interval` | `10s` (minimum time between JWKS refetches for unknown key ids) |
| `WAREHOUSE_EVENTS_ENABLED` / `WAREHOUSE_EVENTS_POLL_INTERVAL` | `warehouse.events.enabled` / `.poll-interval` | `true` / `PT5S` |
| `WAREHOUSE_RECONCILE_ENABLED` / `_INTERVAL` | `warehouse.reconcile.*` | `true` / `PT10M` |
| `WAREHOUSE_NOTIFICATIONS_RETRY_ENABLED` / `_RETRY_INTERVAL` | `warehouse.notifications.*` | `true` / `PT30S` |
| `WAREHOUSE_CARRIER_ENABLED` / `_STEP_INTERVAL` | `warehouse.carrier.enabled` / `.step-interval` | `true` / `PT2M` (`PT30S` in `local` and `demo`) |
| `WAREHOUSE_SERVICE_TOKEN` | `warehouse.service-token` | `local-warehouse-token`; no default in `docker` |
| `BACKEND_BASE_URL` | `backend.base-url` | `http://localhost:4001` (`http://backend:4001` in `docker`) |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | datasource | see profiles |
| `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` | actuator exposure | `health,prometheus` |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | `logging.structured.format.console` | unset (human-readable lines with `[traceId,requestId]`); `ecs` for JSON lines |

## Test

```bash
./mvnw verify                 # unit, contract and flow tests + PMD + JaCoCo (90% lines / 75% branches)
./mvnw -Pfast-verify verify   # same tests without PMD and JaCoCo
```

No Docker, Postgres or backend is needed. Flow tests boot the service on H2
(`test` profile, test-scoped driver) and
replace the HTTP client with `FakeBackend`, an in-memory implementation of the
internal-API rules (including the event feed; scheduled jobs are disabled in the
`test` profile and driven explicitly). `RestBackendClientContractTest` pins every internal-API
request (method, path, query, `X-Service-Token`, JSON body) with
`MockRestServiceServer`.

## Native image

```bash
docker build -t warehouse-service:local .          # needs ~9 GB for the builder (Docker VM >= 12 GB)
docker run --rm -p 4003:4003 \
  -e SPRING_PROFILES_ACTIVE=docker \
  -e SPRING_DATASOURCE_URL='jdbc:postgresql://<postgres-host>:5432/testdb?currentSchema=warehouse' \
  -e WAREHOUSE_SERVICE_TOKEN=<token> warehouse-service:local
```

The image is PostgreSQL-only (H2 is never compiled in, so the `local` profile
does not work in it). Flyway creates the `warehouse` schema on first start.
`docker build --build-arg NATIVE_XMX=...` changes the builder heap (default 10g).

The build stage (`ghcr.io/graalvm/native-image-community:25`) runs
`./mvnw -Pnative native:compile`; the runtime stage is `debian:trixie-slim`
with the single binary, running as UID 10001. The image contains `bash`, so the
Compose healthcheck can probe `/actuator/health` through `/dev/tcp` (there is
no curl). Proposed published name: `slawekradzyminski/warehouse-service`.

`Dockerfile.jvm` builds a plain JVM image (Temurin 25 JRE) as a fallback when
no machine with enough memory for the native build is available.

Native-image hints live in `config/NativeRuntimeHints` (internal-API JSON types
and JJWT implementation classes); Flyway, PostgreSQL, H2 and Micrometer are
covered by Spring Boot and the GraalVM reachability-metadata repository.

### Footprint (measured 2026-11-08, arm64, Docker `--memory 160m`, Postgres 16)

| Metric | Value |
| --- | --- |
| Native build | ~100 s on 8 threads, builder peak RSS 8.4 GB (`-Xmx10g`) |
| Binary / image | 200 MB binary; image 99 MB compressed (`docker images` shows 408 MB unpacked) |
| Startup | Spring context 0.3 s; healthy 0.54 s after `docker run` |
| Memory idle | 55.5 MiB (`docker stats`, cgroup working set) |
| After 210 sequential + 600 concurrent (30 parallel) authenticated requests | 55–56 MiB, no OOM; process RSS 197 MB of which 54 MB anonymous and 143 MB file-mapped binary pages (reclaimable) |
| Postgres connections | at most 3 (Hikari `maximum-pool-size: 3`, `minimum-idle: 1`, idle connections retired after 2 min) |

Runtime tuning: the image runs with `CMD ["-Xmx64m"]` (native heap cap,
override with Compose `command`), Tomcat `threads.max: 20`, `accept-count: 50`,
JMX off, open-in-view off, no devtools. Recommended Compose `mem_limit`:
128m is enough for the measured load; 160m (the contract value) leaves headroom
for the 64 MB heap cap plus native and thread memory.

## Release pipeline

Pushing a `vX.Y.Z` tag runs `.github/workflows/publish-image.yml`:

1. `validate` checks that the commit is on `master` and that the tag matches the project version.
2. `check-prior-verification` reads the git tree of the tagged commit and compares it with
   the trees of the commits behind the last 100 successful `ci.yml` runs (GitHub API,
   `actions: read`). PRs are squash-merged, so the tag commit is new but its tree is the
   tree of the PR head that passed CI (when the branch was up to date with `master`), or
   of the `master` push whose run has already finished. For `pull_request` runs GitHub
   tests the merge commit, which has that same tree when the branch is up to date.
3. When a matching run exists, the `verify` job (`./mvnw verify` plus the amd64 native
   build and smoke test) is skipped and the run summary links the CI run that verified
   the tree. Otherwise it runs as before. Manual (`workflow_dispatch`) prereleases always
   run it, and any API error falls back to the full verification. A failed or cancelled
   `verify` still blocks the release.
4. `build` compiles the native image on an amd64 and an arm64 runner, pushes each by
   digest and runs `scripts/native-smoke.sh` against that pushed image on both paths, so
   every published architecture is booted against Postgres; `publish` merges the digests.

A tag whose tree already passed `ci.yml` publishes in about 14 minutes instead of 20.

The release does not reuse the image from the PR: `ci.yml` builds `warehouse-service:native`
locally and never pushes it, and the published image carries the release commit in its
labels and is built on both architectures.

## Mutation testing

- PITest: `./mvnw -Pmutation-testing test-compile pitest:mutationCoverage`
  (scope: `Carrier`, `ShipmentService`, `ReturnService`, `RefundCalculator`,
  `PurchasingService`, `JwtTokenValidator`, `JwtTokenFilter`,
  `RestBackendClient`, `BackendErrorMapper`, `service.tracking.*`, `service.event.*`, `correlation.*`). Advisory, not a gate.
