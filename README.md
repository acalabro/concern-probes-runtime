# Concern Probes Runtime

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-blue?logo=openjdk" alt="Java 21">
  <img src="https://img.shields.io/badge/ActiveMQ-6.2-red?logo=apache" alt="ActiveMQ">
  <img src="https://img.shields.io/badge/Docker-ready-2496ED?logo=docker" alt="Docker">
  <img src="https://img.shields.io/badge/edge-deployable-brightgreen" alt="Edge">
</p>

Standalone, edge-deployable probe runtime for the [CONCERN Monitoring Infrastructure](https://github.com/acalabro/Concern_Monitoring_Infrastructure).

Probes are **declarative** (YAML files or REST API), run completely independently of the monitor, and can be deployed on any node — datacenter, edge, embedded device — that has network access to the ActiveMQ broker.

---

## Contents

- [Architecture](#architecture)
- [Key design choices](#key-design-choices)
- [Quick start](#quick-start)
- [Configuration](#configuration)
  - [Runtime config (application.yml)](#runtime-config-applicationyml)
  - [Probe definitions](#probe-definitions)
  - [Environment variable overrides](#environment-variable-overrides)
- [Sending events via the HTTP API](#sending-events-via-the-http-api)
- [Active source adapters](#active-source-adapters)
- [Event schema](#event-schema)
- [REST API reference](#rest-api-reference)
- [Edge deployment](#edge-deployment)
- [Monitor-side integration](#monitor-side-integration)
- [Adding a custom source adapter](#adding-a-custom-source-adapter)
- [Smoke test](#smoke-test)
- [Wire-format compatibility note](#wire-format-compatibility-note)

---

## Architecture

```
┌─ datacenter / cloud ─────────────────────────────────────┐
│                                                          │
│   ┌──────────────────────────────────────────────────┐  │
│   │  Concern Monitoring Infrastructure               │  │
│   │  Drools / Esper · REST API · MySQL               │  │
│   └────────────────────┬─────────────────────────────┘  │
│                        │ deserialises ConcernBaseEvent   │
│                   ╔════╧════╗                            │
│                   ║ ActiveMQ║ :61616 (OpenWire/JMS)      │
│                   ╚════╦════╝                            │
└────────────────────────┼─────────────────────────────────┘
                         │  TCP (plain or SSL)
          ┌──────────────┼──────────────────────────────┐
          │  edge node   │                              │
          │  ┌───────────┴──────────────────────────┐  │
          │  │  concern-probes-runtime               │  │
          │  │                                       │  │
          │  │  ┌─────────────┐  ┌────────────────┐  │  │
          │  │  │ Source      │  │ HTTP Ingest    │  │  │
          │  │  │ adapters    │  │ POST /ingest/  │  │  │
          │  │  │ (csv, tail, │  │ {probeName}    │  │  │
          │  │  │  synthetic, │  │                │  │  │
          │  │  │  http-poll) │  └────────────────┘  │  │
          │  │  └──────┬──────┘           │           │  │
          │  │         └──────────────────┘           │  │
          │  │              ▼                          │  │
          │  │   EventBuilder → ConcernBaseEvent       │  │
          │  │              ▼                          │  │
          │  │   ActiveMqPublisher ──► broker          │  │
          │  │              ▼                          │  │
          │  │   SQLite offline buffer (if broker down)│  │
          │  │                                         │  │
          │  │  Admin UI :8080/ui  ·  REST :8080/api   │  │
          │  └─────────────────────────────────────────┘  │
          └──────────────────────────────────────────────-┘

          (N identical containers on N nodes, each independent)
```

---

## Key design choices

| Choice | Rationale |
|---|---|
| **Zero dependency on monitor JAR** | The probe container builds and runs without the monitor codebase. Wire-format compatibility is achieved via byte-identical copies of the three serialisable classes (`ConcernAbstractEvent`, `ConcernBaseEvent`, `CepType`). |
| **Declarative probes (YAML + REST)** | No Java class per probe type. The logical type is recorded in `property.probeType` of each event. |
| **All payload in `property` JSON** | Server-side classes are never extended. One `ProbeUtils` helper (one file) is added to the monitor and lets Drools rules read any field. |
| **Lazy JMS connect** | Probes are created instantly; the broker connection is established on the first send. If unreachable, events buffer locally. |
| **SQLite offline buffer** | Events produced during broker outages are queued to disk and flushed automatically when connectivity returns. Essential for edge nodes. |
| **Stop/restart** | Each `start()` creates a fresh source runner, so a probe can be stopped and restarted any number of times without redeployment. |

---

## Quick start

### Prerequisites

- Docker + Docker Compose
- The host `./config` directory must be writable by the container user:

```bash
mkdir -p config/probes
export UID GID        # ensures docker compose inherits your UID/GID
```

### Interactive startup (recommended)

```bash
./scripts/start.sh
```

The script asks three questions and configures everything:

```
1/3  ActiveMQ broker
  ?  Start ActiveMQ locally (inside this compose stack)? [Y/n]  →  y / n
       If n: enter the external broker URL

2/3  MySQL
  ?  Start MySQL locally (inside this compose stack)? [Y/n]  →  y / n
       If n: enter host, port, database, credentials

3/3  Probe runtime
  ?  Node ID, admin token, HTTP port
```

It writes `.env` and launches the right `docker compose --profile ...` command.

### Manual startup with profiles

```bash
# All local (ActiveMQ + MySQL + probes)
docker compose --profile local-broker --profile local-mysql up --build

# Only local broker, MySQL external
docker compose --profile local-broker up --build

# Only local MySQL, broker external (set BROKER_URL in .env first)
docker compose --profile local-mysql up --build

# Everything external (only probes container starts)
docker compose up --build
```

### Non-interactive (CI / automation)

```bash
./scripts/start.sh --auto     # uses .env defaults, asks nothing
```

### `.env` — connection addresses

All addresses are configured in `.env` (auto-created by `start.sh`, or edit manually):

```bash
# Broker
BROKER_URL=tcp://192.168.1.10:61616
BROKER_USER=system
BROKER_PASSWORD=manager

# MySQL (used by the monitor — the probe runtime exposes these as env vars
# for future source adapters, but does not connect itself)
MYSQL_HOST=192.168.1.10
MYSQL_PORT=3306
MYSQL_DATABASE=concern
MYSQL_USER=concern
MYSQL_PASSWORD=concern
```

### Build the fat-jar directly (no Docker)

```bash
mvn -DskipTests package
java -jar target/concern-probes-runtime.jar config/application.yml
```

---

## Configuration

### Runtime config (`config/application.yml`)

```yaml
nodeId:               local-dev-01   # physical identity → senderID of every event
httpPort:             8080
probesDir:            config/probes  # directory of probe YAML files
bufferDbPath:         data/buffer.sqlite
retryIntervalSeconds: 15             # how often to flush the offline buffer
adminToken:           change-me-admin
uiEnabled:            true
```

### Probe definitions

Each probe is a YAML file in `config/probes/` (or sent via `POST /api/probes`).

#### HTTP-ingest only (gateway style)

A caller (sensor, script, another service) pushes data to the probe via HTTP. The probe turns it into a `ConcernBaseEvent` and forwards it to the monitor.

```yaml
id:        temperature-http
name:      "ConcernTemperatureProbe"
probeType: "ConcernTemperatureProbe"

broker:
  url:      "tcp://monitor-host:61616"
  username: "system"
  password: "manager"
  topic:    "DROOLS-InstanceOne"

ingest:
  enabled:     true
  path:        "ConcernTemperatureProbe"   # POST /ingest/ConcernTemperatureProbe
  authToken:   "my-secret"                 # omit = no auth
  payloadMode: passthrough                 # whole body → property.payload

eventTemplate:
  name:      "TemperatureReading"
  cepType:   "DROOLS"
  dataField: "${payload.value}"            # → ConcernBaseEvent.data

buffer:
  enabled: true
autoStart: true
```

```bash
curl -X POST http://localhost:8080/ingest/ConcernTemperatureProbe \
     -H 'Content-Type: application/json' \
     -H 'Authorization: Bearer my-secret' \
     -d '{"value": 38.2, "sensorId": "DS-A1"}'
```

#### Active source (probe reads data autonomously)

```yaml
id:        gnb-mac-replay
name:      "ConcernGnbMacProbe"
probeType: "ConcernGnbMacProbe"

broker:
  url:   "tcp://monitor-host:61616"
  topic: "DROOLS-InstanceOne"

source:
  type: csv-file
  config:
    path:          "/data/GNB_MacScheduler.csv"
    hasHeader:     true
    perRowDelayMs: 20
    columnFilter:  "ULSCH_Round_1"   # only this column → payload.value

eventTemplate:
  name:    "GnbMacReading"
  cepType: "DROOLS"

buffer:
  enabled: true
autoStart: false    # start manually via POST /api/probes/gnb-mac-replay/start
```

#### Probe with both ingest and source

```yaml
# Emits synthetic events every second AND accepts external pushes.
source:
  type: synthetic
  config:
    intervalMs: 1000
    valueMin:   0
    valueMax:   100
ingest:
  enabled: true
  path:    "ConcernSyntheticProbe"
```

### Environment variable overrides

| Variable | Description | Default |
|---|---|---|
| `PROBE_NODE_ID` | Physical node identity (→ `senderID`) | hostname |
| `HTTP_PORT` | HTTP listen port | `8080` |
| `PROBES_DIR` | Directory for probe YAML files | `config/probes` |
| `BUFFER_DB_PATH` | SQLite buffer file path | `data/buffer.sqlite` |
| `ADMIN_TOKEN` | Bearer token for `/api/**` | *(none)* |
| `APP_CONFIG` | Path to `application.yml` | `config/application.yml` |

---

## Sending events via the HTTP API

Every probe with `ingest.enabled: true` exposes two endpoints:

```
POST /ingest/{probeName}          → single event
POST /ingest/{probeName}/batch    → array of events
```

**Single event:**

```bash
curl -X POST http://localhost:8080/ingest/ConcernTemperatureProbe \
     -H 'Content-Type: application/json' \
     -H 'Authorization: Bearer my-secret' \
     -d '{"value": 38.2, "unit": "celsius", "sensorId": "DS-A1"}'
```

**Response codes:**

| Code | Meaning |
|---|---|
| `200` | Delivered to broker immediately |
| `202` | Buffered locally (broker unreachable) — will retry automatically |
| `401` | Missing or invalid bearer token |
| `404` | No probe with that ingest path |
| `429` | Rate limit exceeded |
| `503` | Delivery failed and could not buffer |

**Batch:**

```bash
curl -X POST http://localhost:8080/ingest/ConcernTemperatureProbe/batch \
     -H 'Content-Type: application/json' \
     -H 'Authorization: Bearer my-secret' \
     -d '[{"value": 38.2}, {"value": 39.1}, {"value": 40.5}]'
# → {"received":3,"sent":3,"buffered":0,"failed":0}
```

---

## Active source adapters

| Type | Description | Key parameters |
|---|---|---|
| `synthetic` | Random values at fixed rate | `intervalMs`, `valueMin`, `valueMax` |
| `csv-file` | Replays a CSV row-by-row | `path`, `hasHeader`, `delimiter`, `loop`, `perRowDelayMs`, `columnFilter` |
| `tail-file` | Follows a growing file (`tail -F`) or polls a single-line sysfs file | `path`, `mode` (`tail`\|`poll`), `pollIntervalMs`, `fromBeginning` |
| `http-poll` | Periodically GETs a URL, optional JSON-path extraction | `url`, `intervalMs`, `method`, `headers`, `responseFormat`, `jsonPath`, `timeoutMs` |

---

## Event schema

Every event sent to the monitor is a `ConcernBaseEvent<String>`. The `property` field carries a structured JSON string:

```json
{
  "schemaVersion": "1.0",
  "probeType":     "ConcernTemperatureProbe",
  "probeNode":     "edge-torino-01",
  "producedAt":    1714000000000,
  "source":        "http-ingest",
  "payload": {
    "value":    38.2,
    "unit":     "celsius",
    "sensorId": "DS-A1"
  }
}
```

- `senderID` of the JMS message = `nodeId` (physical node identity)
- `probeType` = the logical probe name declared in the YAML
- `source` = `"http-ingest"` | `"csv-file"` | `"synthetic"` | `"tail-file"` | `"http-poll"`

---

## REST API reference

All `/api/**` endpoints require `Authorization: Bearer $ADMIN_TOKEN` when a token is configured.

| Method | Path | Description |
|---|---|---|
| `GET` | `/health` | Liveness, uptime, probe count |
| `GET` | `/metrics` | Prometheus text format |
| `GET` | `/ui/` | Admin web interface |
| `GET` | `/api/probes` | List all probes with status |
| `POST` | `/api/probes` | Create probe (JSON or YAML body) |
| `GET` | `/api/probes/{id}` | Detail + counters |
| `PUT` | `/api/probes/{id}` | Update (replaces definition) |
| `DELETE` | `/api/probes/{id}` | Delete |
| `POST` | `/api/probes/{id}/start` | Start |
| `POST` | `/api/probes/{id}/stop` | Stop |
| **`GET`** | **`/api/probes/{id}/export`** | **Download ZIP per edge node** |
| `GET` | `/api/sources` | Available source adapter types |
| **`POST`** | **`/ingest/{probeName}`** | **Push one event** |
| **`POST`** | **`/ingest/{probeName}/batch`** | **Push array of events** |

---

## Edge Package Export

Il runtime principale può esportare una probe come pacchetto ZIP self-contained,
pronto per essere eseguito su un nodo edge con solo Docker installato.

Il pulsante **⬇ Edge ZIP** appare nella colonna azioni della tabella probe nell'Admin UI,
oppure via REST: `GET /api/probes/{id}/export`.

### Contenuto dello ZIP

```
concern-probe-{id}-{timestamp}.zip
├── Dockerfile                    ← FROM eclipse-temurin:21-jre-alpine
├── concern-probe-edge.jar        ← mini-runtime (no Spring, no UI, no buffer)
├── config/probes/probe.yaml      ← definizione esatta della probe
├── .env                          ← BROKER_URL da editare
├── docker-compose.yml
└── README.md
```

### Build del mini-runtime edge (una tantum)

```bash
cd edge
mvn -DskipTests package
cp target/concern-probe-edge.jar ../concern-probe-edge.jar
```

Oppure configurare il percorso via variabile d'ambiente:

```bash
EDGE_JAR_PATH=/path/to/concern-probe-edge.jar docker compose up
```

### Deploy sull'edge node

```bash
# Sul nodo edge
unzip concern-probe-temperature-20240101-120000.zip
cd concern-probe-temperature-20240101-120000/
nano .env          # modifica solo BROKER_URL
docker compose up --build -d
docker compose logs -f
```

### Comportamento del mini-runtime edge

| Funzionalità | Runtime completo | Mini-runtime edge |
|---|---|---|
| Source adapter (csv, tail, synthetic, http-poll) | ✓ | ✓ |
| Pubblicazione JMS (OpenWire, SSL) | ✓ | ✓ |
| Wire-format ConcernBaseEvent | ✓ | ✓ (byte-identical) |
| PlaceholderResolver (`${payload.value}`) | ✓ | ✓ |
| Buffer SQLite offline | ✓ | ✗ (retry+backoff) |
| REST API / Admin UI | ✓ | ✗ |
| HTTP ingest | ✓ | ✗ |
| Dimensione immagine Docker | ~300 MB | ~200 MB |
| Dimensione ZIP esportato | — | ~8-12 MB |

---

## Edge deployment

```bash
docker run -d \
  --name concern-probes-edge-01 \
  --restart unless-stopped \
  -p 8080:8080 \
  -e PROBE_NODE_ID=edge-torino-01 \
  -e ADMIN_TOKEN=my-secret \
  -v /etc/concern-probes/config:/app/config \
  -v /var/lib/concern-probes/data:/app/data \
  your-registry/concern-probes-runtime:0.2.0
```

Key points:

- **`PROBE_NODE_ID` must be unique per physical node.** It becomes the `senderID` of every JMS event and is embedded in `property.probeNode`.
- Mount `/app/data` on **persistent storage**. It holds the SQLite offline buffer. Without it, buffered events are lost on container restart.
- Mount `/app/config` writable (no `:ro`) if you want to create or modify probes from the UI or REST API.
- If the broker is unreachable, events queue locally and flush automatically when connectivity returns.
- For SSL connections, mount certificates into `/app/certs` and reference them in the broker config:

```yaml
broker:
  url: "ssl://monitor-host:61617"
  ssl: true
  trustStore: "/app/certs/truststore.p12"
  trustStorePassword: "changeit"
```

---

## Monitor-side integration

Copy the single file `monitor-addon/src/main/java/it/cnr/isti/labsedc/concern/utils/ProbeUtils.java` into the monitor's source tree:

```bash
cp monitor-addon/src/main/java/it/cnr/isti/labsedc/concern/utils/ProbeUtils.java \
   ../Concern_Monitoring_Infrastructure/src/main/java/it/cnr/isti/labsedc/concern/utils/
```

`ProbeUtils` depends only on `org.json`, already present in the monitor's `pom.xml`. No other change required.

### Drools rule examples

```drools
import it.cnr.isti.labsedc.concern.utils.ProbeUtils;
import it.cnr.isti.labsedc.concern.event.ConcernBaseEvent;

// Threshold on a field
rule "high-temperature-alert"
when
    $e : ConcernBaseEvent(
        ProbeUtils.probeType(property) == "ConcernTemperatureProbe",
        ProbeUtils.jsonDouble(property, "payload.value") > 40.0
    )
then
    System.out.printf("ALERT node=%s temp=%s%n",
        ProbeUtils.probeNode($e.getProperty()),
        ProbeUtils.jsonField($e.getProperty(), "payload.value"));
end

// Correlate two probe types within a time window
rule "fire-risk"
when
    $t : ConcernBaseEvent(
        $node : senderID,
        ProbeUtils.probeType(property) == "ConcernTemperatureProbe",
        ProbeUtils.jsonDouble(property, "payload.value") > 40.0
    )
    $h : ConcernBaseEvent(
        senderID == $node,
        ProbeUtils.probeType(property) == "ConcernHumidityProbe",
        ProbeUtils.jsonDouble(property, "payload.value") < 20.0,
        this after[0s, 30s] $t
    )
then
    System.out.println("FIRE RISK on node " + $node);
end
```

**`ProbeUtils` method reference:**

| Method | Returns | Example path |
|---|---|---|
| `jsonField(property, path)` | `String` | `"payload.value"` |
| `jsonDouble(property, path)` | `Double` | `"payload.readings[0].value"` |
| `jsonLong(property, path)` | `Long` | `"payload.count"` |
| `jsonInt(property, path)` | `Integer` | |
| `jsonBoolean(property, path)` | `Boolean` | |
| `probeType(property)` | `String` | shortcut for `"probeType"` |
| `probeNode(property)` | `String` | shortcut for `"probeNode"` |
| `payload(property)` | `JSONObject` | whole payload subtree |

All methods are null-safe — return `null` on missing paths, never throw.

---

## Adding a custom source adapter

1. Implement `SourceAdapter` and `SourceRunner` in any package:

```java
public class MyMqttSource implements SourceAdapter {
    @Override public String type() { return "mqtt"; }

    @Override
    public SourceRunner create(ConcernConfigurableProbe probe, Map<String, Object> params) {
        String broker = (String) params.get("brokerUrl");
        String topic  = (String) params.get("topic");
        return new MqttRunner(probe, broker, topic);
    }
}
```

2. Register in `src/main/resources/META-INF/services/it.cnr.isti.labsedc.concern.probes.source.SourceAdapter`:

```
com.example.MyMqttSource
```

3. Rebuild. The adapter appears in `GET /api/sources` and can be referenced in probe YAMLs as `type: mqtt`.

---

## Smoke test

After `docker compose up`:

```bash
chmod +x scripts/smoke-test.sh
./scripts/smoke-test.sh http://localhost:8080 change-me-admin
```

The script creates a probe, pushes 3 events via `/ingest`, verifies the counters advanced, and deletes the probe.

---

## Wire-format compatibility note

The container includes byte-identical copies of three classes from the monitor:

| Class | serialVersionUID |
|---|---|
| `it.cnr.isti.labsedc.concern.event.ConcernAbstractEvent` | `7077313246352116557L` |
| `it.cnr.isti.labsedc.concern.event.ConcernBaseEvent` | `1L` |
| `it.cnr.isti.labsedc.concern.cep.CepType` | *(enum)* |

The monitor deserialises `ObjectMessage` payloads using Java serialization. For this to work, both sides must declare the same FQN and `serialVersionUID`. If the monitor ever changes the field layout of these classes, update the copies in `src/main/java/it/cnr/isti/labsedc/concern/event/` accordingly and rebuild.

> **Note**: the field in `ConcernAbstractEvent` that stores the sender is named `sender` (not `senderID`) in the bytecode — Java serialization uses raw field names, not getter names. This is reflected in the bundled copy.

---

## Project structure

```
concern-probes-runtime/
├── config/
│   ├── application.yml                  # global runtime config
│   └── probes/
│       ├── temperature-http.yaml        # example: HTTP-ingest probe
│       ├── synthetic-demo.yaml          # example: synthetic source
│       └── gnb-mac-replay.yaml          # example: CSV replay
├── edge/                                # mini-runtime per nodi edge
│   ├── pom.xml                          # dipendenze minime (no Spring, no Javalin)
│   └── src/main/java/it/cnr/isti/labsedc/concern/
│       ├── cep/CepType.java             # wire-compatible copy
│       ├── event/
│       │   ├── Event.java               # wire-compatible copy
│       │   ├── ConcernAbstractEvent.java
│       │   └── ConcernBaseEvent.java
│       └── probe/
│           ├── EdgeProbeMain.java       # entry point (main)
│           ├── jms/EdgePublisher.java   # JMS + retry backoff esponenziale
│           ├── model/EdgeProbeConfig.java
│           ├── source/                  # 4 adapter (synthetic, csv, tail, http-poll)
│           └── util/                    # EdgeEventBuilder, EdgePlaceholderResolver
├── monitor-addon/
│   ├── README.md
│   ├── rules-examples.drl               # Drools rule templates
│   └── src/.../concern/utils/
│       └── ProbeUtils.java              # copy into monitor source tree
├── scripts/
│   └── smoke-test.sh
├── src/main/java/it/cnr/isti/labsedc/concern/
│   ├── cep/CepType.java                 # wire-compatible copy
│   ├── event/
│   │   ├── Event.java                   # wire-compatible copy
│   │   ├── ConcernAbstractEvent.java    # wire-compatible copy
│   │   └── ConcernBaseEvent.java        # wire-compatible copy
│   └── probes/
│       ├── ProbesApplication.java       # main entry point
│       ├── api/
│       │   ├── AuthFilter.java
│       │   ├── HealthController.java
│       │   ├── IngestController.java
│       │   ├── ProbesController.java
│       │   └── ProbeExportController.java  # NEW: GET /api/probes/{id}/export
│       ├── buffer/                      # OfflineBuffer, SqliteBuffer, NoopBuffer
│       ├── core/                        # ProbeDefinition, ProbeManager, EventBuilder…
│       ├── jms/                         # ActiveMqPublisher (lazy connect, no statics)
│       ├── persist/                     # ProbeConfigStore (YAML on disk)
│       ├── source/                      # SourceAdapter SPI + 4 built-in adapters
│       └── util/                        # PlaceholderResolver
├── Dockerfile
├── docker-compose.yml
└── pom.xml
```

### Build completa

```bash
# 1. Mini-runtime edge (una tantum, prima di usare l'export)
cd edge && mvn -DskipTests package
cp target/concern-probe-edge.jar ../concern-probe-edge.jar
cd ..

# 2. Runtime completo
mvn -DskipTests package
docker compose up --build
```

---

## License

Same as the upstream [CONCERN Monitoring Infrastructure](https://github.com/acalabro/Concern_Monitoring_Infrastructure) project.
