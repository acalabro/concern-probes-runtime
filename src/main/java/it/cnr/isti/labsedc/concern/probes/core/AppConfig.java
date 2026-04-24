package it.cnr.isti.labsedc.concern.probes.core;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class AppConfig {
    public String nodeId;
    public int    httpPort              = 8080;
    public String probesDir            = "config/probes";
    public String bufferDbPath         = "data/buffer.sqlite";
    public int    retryIntervalSeconds = 15;
    public String adminToken;
    public boolean uiEnabled           = true;

    /**
     * MySQL connection coordinates. The probe runtime itself does not use MySQL
     * directly, but these values are read from env vars and exposed here so that
     * future source adapters (e.g. a jdbc-query source) can access them without
     * hard-coding anything. Populated from MYSQL_* env vars by ProbesApplication.
     */
    public String mysqlHost     = "localhost";
    public int    mysqlPort     = 3306;
    public String mysqlDatabase = "concern";
    public String mysqlUser;
    public String mysqlPassword;
}
