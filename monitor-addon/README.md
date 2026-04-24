# monitor-addon

Files to add to the Concern monitor side. This is the only change needed server-side.

## Installation

```bash
cp src/main/java/it/cnr/isti/labsedc/concern/utils/ProbeUtils.java \
   <Concern_Monitoring_Infrastructure>/src/main/java/it/cnr/isti/labsedc/concern/utils/
```

`ProbeUtils` depends only on `org.json`, already in the monitor's `pom.xml`. No
other change required. Rebuild the monitor as usual.

## ProbeUtils path syntax

| Expression | Example |
|---|---|
| Top-level key | `"probeType"` |
| Nested key | `"payload.value"` |
| Array index | `"payload.readings[0].value"` |
| Multiple indices | `"payload.matrix[1][2]"` |

All methods are null-safe — return `null` on missing paths, never throw.

## Example Drools rules

See `rules-examples.drl` in this folder.
