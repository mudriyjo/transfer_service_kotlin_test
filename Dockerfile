# syntax=docker/dockerfile:1.7
FROM gradle:8.14.4-jdk21-alpine AS builder

WORKDIR /workspace
COPY --chown=gradle:gradle . .
RUN ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S transfer && adduser -S -G transfer transfer
WORKDIR /app
COPY --from=builder --chown=transfer:transfer /workspace/build/libs/transfer-service.jar /app/transfer-service.jar

USER transfer
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=12 \
    CMD wget --quiet --spider http://127.0.0.1:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Djava.security.egd=file:/dev/urandom", "-jar", "/app/transfer-service.jar"]
