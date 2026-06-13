# Build stage
FROM gradle:8.9-jdk21 AS builder

WORKDIR /app

COPY gradle/ gradle/
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties* ./
COPY koncerto-core/ koncerto-core/
COPY koncerto-logging/ koncerto-logging/
COPY koncerto-workflow/ koncerto-workflow/
COPY koncerto-workspace/ koncerto-workspace/
COPY koncerto-linear/ koncerto-linear/
COPY koncerto-agent/ koncerto-agent/
COPY koncerto-orchestrator/ koncerto-orchestrator/
COPY koncerto-dashboard/ koncerto-dashboard/
COPY koncerto-metrics/ koncerto-metrics/
COPY koncerto-app/ koncerto-app/
COPY koncerto-notifications/ koncerto-notifications/

RUN ./gradlew :koncerto-app:bootJar --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

RUN addgroup -g 1000 -S koncerto && \
    adduser -u 1000 -S koncerto -G koncerto

COPY --from=builder /app/koncerto-app/build/libs/koncerto-app-*.jar app.jar

RUN chown -R koncerto:koncerto /app

USER koncerto

EXPOSE 8080

ENV KONCERTO_WORKFLOW_PATH=/config/workflows \
    KONCERTO_LOGS_ROOT=/logs \
    KONCERTO_DB_PATH=/data/koncerto.db

HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]