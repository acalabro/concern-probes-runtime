package it.cnr.isti.labsedc.concern.probes.buffer;

import it.cnr.isti.labsedc.concern.event.ConcernBaseEvent;

public interface OfflineBuffer extends AutoCloseable {
    void enqueue(String probeId, ConcernBaseEvent<?> event) throws Exception;
    ConcernBaseEvent<?> peek(String probeId);
    void ack(String probeId, ConcernBaseEvent<?> event);
    long size(String probeId);
    @Override void close();
}
