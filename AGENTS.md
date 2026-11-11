# warehouse-service contributor guide

## Communication language

Use English for all communication related to this repository: responses, plans,
documentation, code comments, test names, commit messages and handoff notes.

## Contract

- The service boundary is defined in
  `awesome-localstack/docs/WAREHOUSE_SERVICE_CONTRACT.md`. Implement exactly that
  boundary; business rules come from the Tier 2 section of
  `test-secure-backend/docs/COMMERCE_AND_FULFILMENT.md`.
- Public paths and JSON bodies must stay identical to the backend's former
  warehouse endpoints (the frontend calls them through the gateway).
- Order and product data always come from the backend internal API through
  `BackendClient`; never add local order or product tables.
- Keep the write order "validate locally, call the backend, then persist".
  Backend 4xx keep status and message; unavailability is 503 "Backend unavailable".
- `RestBackendClientContractTest` pins the internal-API requests. Change it only
  together with the contract document and the backend.

# Verification Commands

- Fast local verify: `./mvnw -Pfast-verify verify`
- Default verify (CI default, PMD + JaCoCo 90/75 gate): `./mvnw verify`
- Tests only: `./mvnw test`
- Targeted mutation feedback: `./mvnw -Pmutation-testing test-compile pitest:mutationCoverage`
- Native image (needs Docker with >= 12 GB memory, not a local GraalVM; builder peaks at ~8.4 GB):
  `docker build -t warehouse-service:local .` (JVM fallback: `docker build -f Dockerfile.jvm .`)
- Smoke the image against PostgreSQL (the image is PostgreSQL-only):
  start `postgres:16-alpine` on a user network, run the image with
  `SPRING_DATASOURCE_URL=jdbc:postgresql://<pg>:5432/<db>?currentSchema=warehouse`,
  then check `/actuator/health`, `/actuator/prometheus` and `/v3/api-docs`.

The default test run needs neither Docker nor a backend: flow tests use the
in-memory `FakeBackend`, which implements the contract's internal-API rules.

# Native-image rules

- The native image is PostgreSQL-only. H2 stays test-scoped (plus the JVM-only
  `local` Maven profile); never add it to the `native` profile or the Dockerfile.

- No runtime classpath scanning, no reflection without hints, no bean
  conditions that must change per deployment (Spring AOT fixes them at build
  time; use runtime properties such as `warehouse.seed-demo-data` instead).
- Add Jackson binding hints in `NativeRuntimeHints` for every new type sent
  to or read from the backend.
- After changing dependencies, hints or configuration, rebuild the image and
  smoke it with the `local` profile.

# Mutation-testing workflow

- PITest: run the profile above after normal verification when you
  change the shipment state machine, returns, refunds, purchasing receipts, the
  JWT validator or filter, or the backend client error mapping. Narrow it for
  small changes, for example
  `./mvnw -Pmutation-testing test-compile pitest:mutationCoverage -DtargetClasses=com.awesome.warehouse.service.returns.ReturnService -DtargetTests=com.awesome.warehouse.service.returns.ReturnServiceTest`.
- Treat `NO_COVERAGE` as a reachability gap and `SURVIVED` as an assertion gap.
  For each meaningful survivor add a test that passes on the original and fails
  on the mutant. Classify equivalent mutants explicitly in the handoff. Keep
  the run advisory; do not use the aggregate score as a gate.

# Change discipline

- Preserve unrelated working-tree changes.
- Keep README.md (run, env vars, profiles) in sync with `application*.yml`.
- Every controller endpoint needs `@Operation` (summary and description) and
  `@ApiResponse` entries; `OpenApiDocumentationTest` enforces it.

# Native smoke test

- `docker build -t warehouse-service:native . && scripts/native-smoke.sh warehouse-service:native` boots the native binary against a real Postgres (under a 160 MiB limit) and checks migrations, demo seeding, JWT validation, a JPA write, event feed consumption (cursor and processed event), health, metrics and OpenAPI. Run it after dependency bumps and entity changes: JVM tests cannot see missing native metadata or lazy-proxy limits. CI runs it on every push and pull request.
