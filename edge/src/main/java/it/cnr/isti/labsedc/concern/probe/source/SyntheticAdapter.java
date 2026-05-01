package it.cnr.isti.labsedc.concern.probe.source;

import java.util.Map;
import java.util.Random;

/**
 * Emette valori casuali a intervallo fisso.
 * Params: intervalMs (default 1000), valueMin (default 0), valueMax (default 100).
 * Identico a SyntheticSource del runtime completo.
 */
public class SyntheticAdapter implements EdgeSourceAdapter {

    private long   intervalMs = 1000L;
    private double valueMin   = 0.0;
    private double valueMax   = 100.0;
    private final Random rng  = new Random();

    @Override public String type() { return "synthetic"; }

    @Override
    public void init(Map<String, Object> params) {
        if (params == null) return;
        intervalMs = num(params.get("intervalMs"), 1000L);
        valueMin   = dbl(params.get("valueMin"),   0.0);
        valueMax   = dbl(params.get("valueMax"),   100.0);
    }

    @Override
    public Map<String, Object> next() throws InterruptedException {
        Thread.sleep(intervalMs);
        double v = valueMin + rng.nextDouble() * (valueMax - valueMin);
        return Map.of("value", v, "ts", System.currentTimeMillis());
    }

    private long   num(Object o, long   d) { return o instanceof Number n ? n.longValue()   : d; }
    private double dbl(Object o, double d) { return o instanceof Number n ? n.doubleValue() : d; }
}
