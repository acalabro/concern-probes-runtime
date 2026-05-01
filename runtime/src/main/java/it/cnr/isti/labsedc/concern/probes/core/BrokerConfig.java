package it.cnr.isti.labsedc.concern.probes.core;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class BrokerConfig {
    /** e.g. tcp://host:61616 or ssl://host:61617 */
    public String url;
    /** Topic name WITHOUT jms. prefix — e.g. DROOLS-InstanceOne */
    public String topic;
    public String username;
    public String password;
    public boolean ssl;
    public String keyStore;
    public String keyStorePassword;
    public String trustStore;
    public String trustStorePassword;
    /** Comma-separated packages trusted for Java object deserialisation. */
    public String trustedPackages = "it.cnr.isti.labsedc.concern,java.lang,java.util,javax.security";
}
