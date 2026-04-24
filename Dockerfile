# syntax=docker/dockerfile:1.7
# ── build stage ───────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline || true
COPY src ./src
RUN mvn -q -B -DskipTests package

# ── runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache wget tini \
 && addgroup -S probes && adduser -S probes -G probes

WORKDIR /app
COPY --from=build /src/target/concern-probes-runtime.jar /app/app.jar
RUN mkdir -p /app/config/probes /app/data /app/certs \
 && chown -R probes:probes /app

USER probes

ENV HTTP_PORT=8080 \
    PROBES_DIR=/app/config/probes \
    BUFFER_DB_PATH=/app/data/buffer.sqlite \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -Dfile.encoding=UTF-8"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD wget -qO- http://localhost:${HTTP_PORT}/health >/dev/null || exit 1

ENTRYPOINT ["/sbin/tini","--"]
CMD ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]
