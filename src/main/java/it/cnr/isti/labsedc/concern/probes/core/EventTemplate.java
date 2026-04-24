package it.cnr.isti.labsedc.concern.probes.core;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.HashMap;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventTemplate {
    public String name = "ProbeEvent";
    public String destinationId = "Monitoring";
    /** Null = generate UUID per event. */
    public String sessionId;
    /** Matches CepType enum values: DROOLS or ESPER. */
    public String cepType = "DROOLS";
    /** Placeholder expression for ConcernBaseEvent.data field. E.g. ${payload.value} */
    public String dataField;
    /** Template-mode field mapping: key → expression. Applied to property.payload. */
    public Map<String, String> propertyPayload = new HashMap<>();
}
