# syntax=docker/dockerfile:1.7
# Multi-stage GraalVM native build; native-image is not needed on the host.
#   docker build -t warehouse-service:local .
#   docker run --rm -p 4003:4003 -e SPRING_PROFILES_ACTIVE=docker \
#     -e SPRING_DATASOURCE_URL='jdbc:postgresql://pg:5432/testdb?currentSchema=warehouse' \
#     -e WAREHOUSE_SERVICE_TOKEN=... warehouse-service:local

FROM ghcr.io/graalvm/native-image-community:25@sha256:0d936f32bb8acb5bc60c41b33e05f064d7a6aaf36b726538296c54949bd4a3c0 AS build
# OpenJDK CVE-2026-91403 mitigation; pinned artifact provenance is in security/java/.
# The builder JDK's security files are what the native image is built against.
COPY security/java/blocked.certs ${JAVA_HOME}/lib/security/blocked.certs
RUN echo "2596bb6dda83c66d96c5a252d7b2ae1f24cc1ae3f1890223b4f3591a8d52bf10  ${JAVA_HOME}/lib/security/blocked.certs" | sha256sum --check -
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
FROM debian:trixie-slim@sha256:a72b4433de1cf7d6bae0c4cee74d0547f58601a643b3375f070c51600d63b6b2
RUN groupadd --system --gid 10001 warehouse \
    && useradd --system --uid 10001 --gid warehouse --no-create-home --shell /usr/sbin/nologin warehouse
WORKDIR /app
COPY --from=build --chown=warehouse:warehouse /workspace/warehouse-service /app/warehouse-service
USER 10001
EXPOSE 4003
ENTRYPOINT ["/app/warehouse-service"]
# Native runtime heap cap for a 160m container; override with Compose `command`, e.g. ["-Xmx96m"].
CMD ["-Xmx64m"]
