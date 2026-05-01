package it.cnr.isti.labsedc.concern.probe.source;

import java.util.Map;

/**
 * Interfaccia comune per i source adapter del mini-runtime edge.
 * Semplificata rispetto a SourceAdapter del runtime completo:
 * non passa ConcernConfigurableProbe ma restituisce il payload direttamente.
 */
public interface EdgeSourceAdapter {
    String type();
    void init(Map<String, Object> params);

    /**
     * Blocca finché un dato è disponibile e lo restituisce come payload Map.
     * Restituisce null se la sorgente è esaurita (es. CSV senza loop).
     */
    Map<String, Object> next() throws InterruptedException;

    default void close() {}
}
