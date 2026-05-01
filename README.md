# Concern Probes Runtime

[![Java 21](https://img.shields.io/badge/Java-21-blue?logo=openjdk)](https://openjdk.org/)
[![ActiveMQ](https://img.shields.io/badge/ActiveMQ-6.2-red?logo=apache)](https://activemq.apache.org/)
[![Docker](https://img.shields.io/badge/Docker-ready-2496ED?logo=docker)](https://www.docker.com/)

Standalone, edge-deployable probe runtime for the [CONCERN Monitoring Infrastructure](https://github.com/acalabro/Concern_Monitoring_Infrastructure).

Probes are **declarative** (YAML files or REST API), run independently of the monitor, and publish `ConcernBaseEvent` messages to an ActiveMQ broker via JMS.

---

## 1. Build & startup

### Prerequisites

- Java 21+
- Maven 3.9+
- Docker + Docker Compose (for containerised deployment)

### Build

```bash
# Clone and build — produces both jars in one command
mvn -DskipTests package
```

This builds two modules in order:
1. `edge/` → `edge/target/concern-probe-edge.jar` (mini-runtime for edge nodes)
2. `runtime/` → `runtime/target/concern-probes-runtime.jar` + copies the edge jar to the project root

### Run locally (no Docker)

```bash
java -jar runtime/target/concern-probes-runtime.jar config/application.yml
```

### Run with Docker Compose

```bash
# All local (ActiveMQ + probes)
docker compose --profile local-broker up --build

# Everything external — set BROKER_URL in .env first
docker compose up --build
```

The interactive startup script handles configuration automatically:

```bash
./scripts/start.sh
```

### Environment variable overrides

| Variable | Description | Default |
|---|---|---|
| `PROBE_NODE_ID` | Physical node identity (→ `senderID`) | hostname |
| `HTTP_PORT` | HTTP listen port | `8080` |
| `PROBES_DIR` | Directory for probe YAML files | `config/probes` |
| `BUFFER_DB_PATH` | SQLite offline buffer path | `data/buffer.sqlite` |
| `ADMIN_TOKEN` | Bearer token for `/api/**` | *(none)* |
| `BROKER_URL` | ActiveMQ broker URL | from probe YAML |
| `EDGE_JAR_PATH` | Path to `concern-probe-edge.jar` for export | `concern-probe-edge.jar` |

---

## 2. Probes

A probe is a YAML file in `config/probes/` (or created via `POST /api/probes`). It defines:
- **where** to send events (broker, topic)
- **how** to acquire data (source adapter)
- **how** to shape the event (event template)

### Minimal example

```yaml
id:        temperature-probe
name:      "ConcernTemperatureProbe"
probeType: "ConcernTemperatureProbe"

broker:
  url:      "tcp://monitor-host:61616"
  username: "system"
  password: "manager"
  topic:    "DROOLS-InstanceOne"

source:
  type: synthetic
  config:
    intervalMs: 1000
    valueMin:   20
    valueMax:   80

eventTemplate:
  name:      "TemperatureReading"
  cepType:   "DROOLS"
  dataField: "${payload.value}"

buffer:
  enabled: true
autoStart: true
```

### Event schema

Every event sent to the monitor carries a structured `property` JSON field:

```json
{
  "schemaVersion": "1.0",
  "probeType":  "ConcernTemperatureProbe",
  "probeNode":  "edge-01",
  "producedAt": 1714000000000,
  "source":     "synthetic",
  "payload":    { "value": 42.7, "ts": 1714000000000 }
}
```

### HTTP ingest (push mode)

Any probe can also accept events pushed from outside:

```yaml
ingest:
  enabled:   true
  path:      "ConcernTemperatureProbe"   # POST /ingest/ConcernTemperatureProbe
  authToken: "my-secret"                 # omit = no auth
```

```bash
curl -X POST http://localhost:8080/ingest/ConcernTemperatureProbe \
     -H 'Content-Type: application/json' \
     -H 'Authorization: Bearer my-secret' \
     -d '{"value": 38.2, "sensorId": "DS-A1"}'
```

### Offline buffer

When the broker is unreachable, events are queued to a local SQLite database and flushed automatically when connectivity returns.

```yaml
buffer:
  enabled:              true
  maxSizeMB:            50
  maxAgeHours:          24
  retryIntervalSeconds: 15
```

### SSL broker connection

```yaml
broker:
  url:               "ssl://monitor-host:61617"
  ssl:               true
  trustStore:        "/app/certs/truststore.p12"
  trustStorePassword: "changeit"
```

---

## 2a. Source adapters

### `synthetic` — random values at fixed rate

Useful for wiring-up, smoke tests, and load simulation.

```yaml
source:
  type: synthetic
  config:
    intervalMs: 1000    # emit every N ms
    valueMin:   0       # random value lower bound
    valueMax:   100     # random value upper bound
```

Payload produced: `{ "value": <double>, "ts": <epoch ms> }`

---

### `csv-file` — replay a CSV row by row

Reads a CSV file and emits one event per row. Useful for replaying historical datasets.

```yaml
source:
  type: csv-file
  config:
    path:          "/data/readings.csv"   # required
    hasHeader:     true                   # first row = column names
    delimiter:     ","
    loop:          false                  # restart from top when EOF
    perRowDelayMs: 20                     # pause between rows
    columnFilter:  "ULSCH_Round_1"        # emit only this column as payload.value
```

When `columnFilter` is set, the payload is `{ "value": "<column value>" }`.
When omitted, all columns are included: `{ "col1": "v1", "col2": "v2", ... }`.

---

### `tail-file` — follow a growing file

Follows a log file as it grows (`tail -F` style), or polls a single-line file (e.g. a sysfs sensor).

```yaml
source:
  type: tail-file
  config:
    path:            "/var/log/sensor.log"   # required
    mode:            "tail"                  # "tail" (follow) | "poll" (re-read whole file)
    pollIntervalMs:  1000                    # check interval
    fromBeginning:   false                   # start from current EOF or file start
```

- `tail` mode: follows new lines appended to the file, handles log rotation.
- `poll` mode: reads the entire file content at each interval — suited for single-value sysfs files like `/sys/class/thermal/thermal_zone0/temp`.

Payload produced: `{ "value": "<line content>", "ts": <epoch ms> }`

---

### `http-poll` — periodically GET a URL

Polls an HTTP endpoint and emits the response as an event. Supports JSON path extraction.

```yaml
source:
  type: http-poll
  config:
    url:            "http://sensor-api/temperature"   # required
    intervalMs:     5000
    method:         "GET"
    timeoutMs:      10000
    responseFormat: "json"          # "json" | "text"
    jsonPath:       "data.value"    # dot-notation path into JSON response
    headers:
      Authorization: "Bearer token"
      Accept: "application/json"
```

- When `jsonPath` is set, `payload.value` contains the extracted field.
- When omitted, the entire JSON response is mapped to the payload.
- When `responseFormat: text`, the raw body is placed in `payload.value`.

---

### Event template — shaping the event

```yaml
eventTemplate:
  name:        "TemperatureReading"    # ConcernBaseEvent.name
  cepType:     "DROOLS"               # DROOLS | ESPER
  destinationId: "Monitoring"
  dataField:   "${payload.value}"     # ConcernBaseEvent.data — supports placeholders
  propertyPayload:                    # remap payload fields (template mode)
    celsius: "${payload.raw}"
    sensor:  "${payload.id}"
```

Supported placeholders in `dataField` and `propertyPayload` values:

| Placeholder | Resolves to |
|---|---|
| `${payload.field}` | Field from the raw payload |
| `${payload.nested.field}` | Nested field (dot-notation) |
| `${uuid}` | Fresh UUID |
| `${now}` | Current epoch ms |
| `${env.VAR}` | Environment variable |

---

## 3. API

All `/api/**` endpoints require `Authorization: Bearer $ADMIN_TOKEN` when a token is configured.

### Probe management

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/probes` | List all probes with status |
| `POST` | `/api/probes` | Create probe (JSON or YAML body) |
| `GET` | `/api/probes/{id}` | Detail + counters |
| `PUT` | `/api/probes/{id}` | Update (replaces definition) |
| `DELETE` | `/api/probes/{id}` | Delete |
| `POST` | `/api/probes/{id}/start` | Start |
| `POST` | `/api/probes/{id}/stop` | Stop |
| `GET` | `/api/probes/{id}/export` | Download edge ZIP package |
| `GET` | `/api/sources` | List available source adapter types |

### Event ingest

| Method | Path | Description |
|---|---|---|
| `POST` | `/ingest/{probeName}` | Push one event |
| `POST` | `/ingest/{probeName}/batch` | Push array of events |

Ingest response codes:

| Code | Meaning |
|---|---|
| `200` | Delivered to broker |
| `202` | Buffered locally (broker unreachable) |
| `401` | Invalid or missing bearer token |
| `404` | No probe with that ingest path |
| `429` | Rate limit exceeded |
| `503` | Delivery failed and could not buffer |

### System

| Method | Path | Description |
|---|---|---|
| `GET` | `/health` | Liveness, uptime, probe count |
| `GET` | `/metrics` | Prometheus text format |
| `GET` | `/ui/` | Admin web UI |

### Edge package export

The `GET /api/probes/{id}/export` endpoint generates a self-contained ZIP with everything needed to run the probe on an edge node with only Docker installed. The **⬇ Edge ZIP** button in the Admin UI calls this endpoint.

```bash
curl -H "Authorization: Bearer $ADMIN_TOKEN" \
     http://localhost:8080/api/probes/temperature-probe/export \
     -o temperature-probe.zip
```

On the edge node:
```bash
unzip temperature-probe.zip && cd temperature-probe-*/
nano .env          # set BROKER_URL
docker compose up --build -d
```

---

## 4. Project structure

```
concern-probes-runtime/
├── pom.xml                          # parent (multi-module): edge → runtime
├── edge/                            # mini-runtime for edge nodes
│   ├── pom.xml
│   └── src/main/java/.../probe/
│       ├── EdgeProbeMain.java
│       ├── jms/EdgePublisher.java   # JMS + exponential backoff retry
│       ├── model/EdgeProbeConfig.java
│       ├── source/                  # 4 adapters (synthetic, csv-file, tail-file, http-poll)
│       └── util/                    # EdgeEventBuilder, EdgePlaceholderResolver
├── runtime/                         # full runtime (REST API, UI, buffer)
│   ├── pom.xml                      # copies concern-probe-edge.jar to project root after build
│   └── src/main/java/.../
│       ├── cep/CepType.java         # wire-compatible copy
│       ├── event/                   # wire-compatible copies (ConcernBaseEvent, etc.)
│       └── probes/
│           ├── ProbesApplication.java
│           ├── api/                 # REST controllers + ProbeExportController
│           ├── buffer/              # OfflineBuffer, SqliteBuffer, NoopBuffer
│           ├── core/                # ProbeDefinition, ProbeManager, EventBuilder
│           ├── jms/ActiveMqPublisher.java
│           ├── persist/ProbeConfigStore.java
│           ├── source/              # SourceAdapter SPI + 4 built-in adapters
│           └── util/PlaceholderResolver.java
├── config/
│   ├── application.yml              # runtime config
│   └── probes/                      # probe YAML definitions
├── monitor-addon/
│   ├── rules-examples.drl
│   └── src/.../utils/ProbeUtils.java   # copy into monitor source tree
├── scripts/
│   ├── start.sh
│   └── smoke-test.sh
├── Dockerfile
├── docker-compose.yml
├── docker-compose.test-local.yml
├── .env
└── .env.test-local
```
