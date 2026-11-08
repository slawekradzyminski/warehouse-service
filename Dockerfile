# syntax=docker/dockerfile:1.7
# Multi-stage GraalVM native build; native-image is not needed on the host.
#   docker build -t warehouse-service:local .
#   docker run --rm -p 4003:4003 -e SPRING_PROFILES_ACTIVE=docker \
#     -e SPRING_DATASOURCE_URL='jdbc:postgresql://pg:5432/testdb?currentSchema=warehouse' \
#     -e WAREHOUSE_SERVICE_TOKEN=... warehouse-service:local

FROM ghcr.io/graalvm/native-image-community:25 AS build
# PostgreSQL-only image: H2 is test/local-JVM scope and never compiled in.
# The Maven wrapper (only-script distribution) needs unzip to unpack Maven.
RUN microdnf install -y unzip findutils && microdnf clean all
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -Pnative dependency:go-offline || true
COPY config config
COPY src src
# Heap of the native-image builder (build time only, not the runtime heap); the build peaks at ~8.4 GB RSS.
ARG NATIVE_XMX=10g
RUN --mount=type=cache,target=/root/.m2 MAVEN_OPTS="-Xmx384m" ./mvnw -B -Pnative -DskipTests -Dnative.xmx=${NATIVE_XMX} native:compile \
    && cp target/warehouse-service /workspace/warehouse-service

# Small glibc base that keeps bash, so the Compose healthcheck can use /dev/tcp.
FROM debian:trixie-slim
RUN groupadd --system --gid 10001 warehouse \
    && useradd --system --uid 10001 --gid warehouse --no-create-home --shell /usr/sbin/nologin warehouse
WORKDIR /app
COPY --from=build --chown=warehouse:warehouse /workspace/warehouse-service /app/warehouse-service
USER 10001
EXPOSE 4003
ENTRYPOINT ["/app/warehouse-service"]
# Native runtime heap cap for a 160m container; override with Compose `command`, e.g. ["-Xmx96m"].
CMD ["-Xmx64m"]
