package it.cnr.isti.labsedc.concern.event;

import java.io.Serializable;
import it.cnr.isti.labsedc.concern.cep.CepType;

/**
 * Wire-compatible copy of the monitor-side ConcernAbstractEvent.
 *
 * CRITICAL — must stay in sync with the monitor's copy:
 *  - serialVersionUID = 7077313246352116557L (explicit, must match)
 *  - field name is "sender" (not "senderID") — serialisation uses field names
 *  - field declaration order must be preserved
 *
 * If the monitor ever changes field layout, update this class and bump the
 * serialVersionUID in ConcernBaseEvent accordingly.
 */
public abstract class ConcernAbstractEvent<T> implements Event<T>, Serializable {

    @java.io.Serial
    private static final long serialVersionUID = 7077313246352116557L;

    // NOTE: field is "sender", not "senderID". getSenderID()/setSenderID() are the
    // public API, but Java serialisation writes the raw field name "sender".
    private long    timestamp;
    private String  sender;
    private String  destinationID;
    private String  sessionID;
    private String  checksum;
    private String  name;
    private T       data;
    private CepType cepType;
    private boolean consumed;

    protected ConcernAbstractEvent(
            long    timestamp,
            String  senderID,
            String  destinationID,
            String  sessionID,
            String  checksum,
            String  name,
            T       data,
            CepType type,
            boolean consumed) {
        setTimestamp(timestamp);
        setSenderID(senderID);
        setDestinationID(destinationID);
        setSessionID(sessionID);
        setChecksum(checksum);
        setName(name);
        setData(data);
        setCepType(type);
        setConsumed(consumed);
    }

    @Override public long    getTimestamp()              { return timestamp; }
    @Override public void    setTimestamp(long ts)       { this.timestamp = ts; }
    @Override public String  getSenderID()               { return sender; }
    @Override public void    setSenderID(String sender)  { this.sender = sender; }
    @Override public String  getDestinationID()          { return destinationID; }
    @Override public void    setDestinationID(String d)  { this.destinationID = d; }
              public String  getSessionID()               { return sessionID; }
              public void    setSessionID(String s)       { this.sessionID = s; }
              public String  getChecksum()                { return checksum; }
              public void    setChecksum(String c)        { this.checksum = c; }
    @Override public String  getName()                   { return name; }
    @Override public void    setName(String name)        { this.name = name; }
    @Override public T       getData()                   { return data; }
    @Override public void    setData(T data)             { this.data = data; }
              public CepType getCepType()                { return cepType; }
              public void    setCepType(CepType t)       { this.cepType = t; }
    @Override public boolean getConsumed()               { return consumed; }
    @Override public void    setConsumed(boolean c)      { this.consumed = c; }
}
