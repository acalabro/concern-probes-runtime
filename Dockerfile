# syntax=docker/dockerfile:1.7
# ── build stage ───────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
# Copia i pom.xml di tutti i moduli per sfruttare il cache layer delle dipendenze
COPY pom.xml .
COPY edge/pom.xml edge/pom.xml
COPY runtime/pom.xml runtime/pom.xml
RUN mvn -q -B dependency:go-offline -pl edge,runtime --also-make || true
# Copia i sorgenti e builda tutto con un solo comando
# L'ordine è garantito dal parent pom.xml: edge → runtime
# Al termine, runtime/pom.xml copia concern-probe-edge.jar nella root /src/
COPY edge/src edge/src
COPY runtime/src runtime/src
RUN mvn -q -B -DskipTests package

# ── runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache wget tini \
 && addgroup -S probes && adduser -S probes -G probes

WORKDIR /app
# Fat-jar del runtime principale
COPY --from=build /src/runtime/target/concern-probes-runtime.jar /app/app.jar
# Fat-jar del mini-runtime edge (usato da ProbeExportController per generare gli ZIP)
COPY --from=build /src/concern-probe-edge.jar /app/concern-probe-edge.jar
RUN mkdir -p /app/config/probes /app/data /app/certs \
 && chown -R probes:probes /app

USER probes

ENV HTTP_PORT=8080 \
    PROBES_DIR=/app/config/probes \
    BUFFER_DB_PATH=/app/data/buffer.sqlite \
    EDGE_JAR_PATH=/app/concern-probe-edge.jar \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -Dfile.encoding=UTF-8"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD wget -qO- http://localhost:${HTTP_PORT}/health >/dev/null || exit 1

ENTRYPOINT ["/sbin/tini","--"]
CMD ["sh","-c","exec java $JAVA_OPTS -jar /app/app.jar"]
