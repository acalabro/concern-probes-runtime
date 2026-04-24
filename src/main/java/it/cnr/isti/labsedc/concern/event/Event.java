package it.cnr.isti.labsedc.concern.event;

/**
 * Wire-compatible copy of the monitor-side Event interface.
 * Interfaces are not serialised, so the exact method set just needs to compile;
 * it does not affect the serialised byte stream.
 */
public interface Event<T> {
    long   getTimestamp();
    void   setTimestamp(long timestamp);
    String getSenderID();
    void   setSenderID(String sender);
    String getDestinationID();
    void   setDestinationID(String destinationID);
    String getName();
    void   setName(String name);
    T      getData();
    void   setData(T data);
    boolean getConsumed();
    void    setConsumed(boolean consumed);
}
