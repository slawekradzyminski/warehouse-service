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
| `GET /api/v1/orders/{id}/shipment` | owner, or `ROLE_ADMIN` for any order |
| `POST /api/v1/orders/{orderId}/returns`, `GET /api/v1/returns`, `GET /api/v1/returns/{id}` | any authenticated user, own orders only |
| `/api/v1/admin/returns/**`, `/api/v1/admin/suppliers`, `/api/v1/admin/purchase-orders/**`, `GET /api/v1/admin/inventory/reorder-suggestions` | `ROLE_ADMIN` |

Authentication uses the backend's access tokens (HMAC JWT, same
`JWT_SECRET_KEY` and short-key expansion, roles in the `auth` claim). There is
no user database. 401/403 bodies are `{"message": ...}`.

## How it talks to the backend

`BackendClient` (Spring `RestClient`) calls `/internal/v1` on
`backend.base-url` with `X-Service-Token`. Writes follow "validate locally, call
the backend, then persist", so a failed backend call leaves no local change:

- create shipment: `GET order` → save shipment → `PUT fulfilment AWAITING_PICK`
  (the shipment row is removed again if the backend refuses);
- pick/pack/ship/deliver: `PUT fulfilment` first; ship/deliver then send an
  `ORDER_SHIPPED`/`ORDER_DELIVERED` notification after commit;
- cancel: `POST warehouse-cancel`, then the shipment becomes `CANCELLED`;
- receive return: `POST inventory/receipts` (`RETURN_RESTOCKED`,
  `requestId = UUID.nameUUIDFromBytes("return-" + id)`) for resellable lines;
- approve/reject/refund return: `RETURN_UPDATED` notification after commit;
- receive purchase order: `POST inventory/receipts` (`PURCHASE_RECEIVED`, the receipt's `requestId`).

Backend 4xx responses keep status and message. 5xx, connection failures and a
rejected service token become 503 `{"message": "Backend unavailable"}`.
Notification failures are logged and never undo the business change.

A customer may cancel an `AWAITING_PICK` order on the backend without telling
this service. The shipment follows lazily: reading it (`GET
/api/v1/warehouse/shipments/{id}`) or a refused transition marks it `CANCELLED`
(`lastActor = "system"`), and the transition answers 409 "Order was cancelled
by the customer".

Local tables (Flyway `V1`, schema `warehouse`): `shipments` (order id, owner and
unit count snapshot), `return_requests` (order id plus order total/subtotal
snapshot for the proportional refund), `return_items` (product id, name and
unit price snapshot), `suppliers`, `purchase_orders`, `purchase_order_lines`
(product id and name snapshot), `purchase_receipts`.

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
| `local` | Postgres `jdbc:postgresql://localhost:5432/testdb?currentSchema=warehouse` (user/password `postgres`) | `http://localhost:4001` | token `local-warehouse-token`, seeds demo suppliers |
| `docker` | Postgres `jdbc:postgresql://postgres:5432/testdb?currentSchema=warehouse` | `http://backend:4001` | `WAREHOUSE_SERVICE_TOKEN` required |
| `demo` | – | – | combine as `docker,demo` to seed the demo suppliers |

Demo suppliers (idempotent): "Nordic Electronics" (orders@nordic-electronics.test,
7 days) and "Baltic Gadgets" (purchasing@baltic-gadgets.test, 14 days).

### Environment variables

| Variable | Property | Default |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | active profiles | none (Postgres on localhost) |
| `JWT_SECRET_KEY` | `security.jwt.token.secret-key` | `local-development-jwt-key-change-me` (must match the backend) |
| `WAREHOUSE_SERVICE_TOKEN` | `warehouse.service-token` | `local-warehouse-token`; no default in `docker` |
| `BACKEND_BASE_URL` | `backend.base-url` | `http://localhost:4001` (`http://backend:4001` in `docker`) |
| `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` | datasource | see profiles |
| `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` | actuator exposure | `health,prometheus` |

## Test

```bash
./mvnw verify                 # unit, contract and flow tests + PMD + JaCoCo (90% lines / 75% branches)
./mvnw -Pfast-verify verify   # same tests without PMD and JaCoCo
```

No Docker, Postgres or backend is needed. Flow tests boot the service on H2
(`test` profile, test-scoped driver) and
replace the HTTP client with `FakeBackend`, an in-memory implementation of the
internal-API rules. `RestBackendClientContractTest` pins every internal-API
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

## Mutation testing

- Layer 1: `./mvnw -Pmutation-testing test-compile pitest:mutationCoverage`
  (scope: `Carrier`, `ShipmentService`, `ReturnService`, `RefundCalculator`,
  `PurchasingService`, `JwtTokenValidator`, `JwtTokenFilter`,
  `RestBackendClient`, `BackendErrorMapper`). Advisory, not a gate.
- Layer 2: semantic mutants, see the lab note below.

### Semantic mutation lab note

Ported from the localstack `experiments/llm-mutation` corpus. Customer
cancellation after picking now belongs to the backend's `fulfilment_state`
rules and is not ported.

| Id | Violated requirement | Mutation | Observable difference | Killing tests |
| --- | --- | --- | --- | --- |
| `refund-ignores-coupon` | Refund = Σ(unit price × quantity) × (order total ÷ order subtotal), so coupon discounts are returned proportionally | `ReturnService.estimatedRefund` returns `itemsValue.setScale(2, HALF_UP)` instead of calling `RefundCalculator.proportionalRefund(itemsValue, total, subtotal)` | A 10% coupon order (subtotal 140.00, total 126.00) returning 120.00 of goods refunds 120.00 instead of 108.00 | `ReturnFlowTest.returnOfADiscountedOrderRestocksResellableItemsAndRefundsProportionally`, `ReturnServiceTest.createSnapshotsOrderTotalsNamesAndPrices`, `ReturnServiceTest.refundPaysTheProportionalAmountAndNotifies` |

Last run (disposable copy): killed by the three tests above.
