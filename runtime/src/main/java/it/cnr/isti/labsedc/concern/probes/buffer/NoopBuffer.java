package it.cnr.isti.labsedc.concern.probes.buffer;

import it.cnr.isti.labsedc.concern.event.ConcernBaseEvent;

public class NoopBuffer implements OfflineBuffer {
    @Override public void enqueue(String p, ConcernBaseEvent<?> e) {}
    @Override public ConcernBaseEvent<?> peek(String p) { return null; }
    @Override public void ack(String p, ConcernBaseEvent<?> e) {}
    @Override public long size(String p) { return 0; }
    @Override public void close() {}
}
