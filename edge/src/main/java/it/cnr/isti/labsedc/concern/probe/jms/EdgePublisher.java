package it.cnr.isti.labsedc.concern.probe.jms;

import it.cnr.isti.labsedc.concern.event.ConcernBaseEvent;
import it.cnr.isti.labsedc.concern.probe.model.EdgeProbeConfig;
import jakarta.jms.*;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.ActiveMQSslConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publisher JMS per l'edge runtime.
 *
 * Differenze rispetto ad ActiveMqPublisher del runtime completo:
 * - aggiunge retry con backoff esponenziale (2s → 4s → ... max 60s)
 *   perché sull'edge non c'è buffer SQLite: si blocca finché il broker torna
 * - supporta SSL identico al runtime completo
 * - trustedPackages identici
 */
public class EdgePublisher {

    private static final Logger log = LoggerFactory.getLogger(EdgePublisher.class);

    private static final long INITIAL_BACKOFF_MS = 2_000L;
    private static final long MAX_BACKOFF_MS     = 60_000L;

    private final EdgeProbeConfig.BrokerConfig cfg;
    private final String                       probeName;
    private final AtomicLong                   msgCounter = new AtomicLong();

    private Connection      connection;
    private Session         session;
    private MessageProducer producer;

    public EdgePublisher(EdgeProbeConfig.BrokerConfig cfg, String probeName) {
        this.cfg       = cfg;
        this.probeName = probeName;
    }

    /**
     * Invia l'evento. Se il broker non è raggiungibile, ritenta con backoff
     * esponenziale finché non riesce o il thread viene interrotto.
     * Nessun buffer su disco: l'edge blocca e riprova.
     */
    public void send(ConcernBaseEvent<?> event) {
        long backoff = INITIAL_BACKOFF_MS;

        while (!Thread.currentThread().isInterrupted()) {
            try {
                ensureConnected();
                doSend(event);
                return; // successo
            } catch (JMSException e) {
                log.warn("[{}] broker unreachable ({}), retry in {}ms",
                         probeName, e.getMessage(), backoff);
                closeQuietly();
                sleep(backoff);
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MS);
            }
        }
    }

    private void ensureConnected() throws JMSException {
        if (session != null && producer != null) return;
        ConnectionFactory factory = cfg.ssl ? buildSslFactory() : buildPlainFactory();
        connection = ((ActiveMQConnectionFactory) factory)
                         .createConnection(cfg.username, cfg.password);
        session  = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Topic topic = session.createTopic(cfg.topic);
        producer = session.createProducer(topic);
        connection.start();
        log.info("[{}] connected to broker {}", probeName, cfg.url);
    }

    private void doSend(ConcernBaseEvent<?> event) throws JMSException {
        ObjectMessage msg = session.createObjectMessage();
        msg.setJMSMessageID(String.valueOf(msgCounter.incrementAndGet()));
        msg.setObject(event);
        producer.send(msg);
        log.info("[{}] event sent → topic={}", probeName, cfg.topic);
    }

    private ActiveMQConnectionFactory buildPlainFactory() {
        ActiveMQConnectionFactory f = new ActiveMQConnectionFactory(cfg.url);
        f.setTrustAllPackages(false);
        f.setTrustedPackages(new ArrayList<>(Arrays.asList(resolvedPackages().split(","))));
        return f;
    }

    private ActiveMQConnectionFactory buildSslFactory() throws JMSException {
        try {
            ActiveMQSslConnectionFactory f = new ActiveMQSslConnectionFactory(cfg.url);
            if (cfg.keyStore          != null) System.setProperty("javax.net.ssl.keyStore",         cfg.keyStore);
            if (cfg.keyStorePassword  != null) System.setProperty("javax.net.ssl.keyStorePassword", cfg.keyStorePassword);
            if (cfg.trustStore        != null) f.setTrustStore(cfg.trustStore);
            if (cfg.trustStorePassword != null) f.setTrustStorePassword(cfg.trustStorePassword);
            f.setTrustAllPackages(false);
            f.setTrustedPackages(new ArrayList<>(Arrays.asList(resolvedPackages().split(","))));
            return f;
        } catch (Exception e) {
            throw new JMSException("SSL factory init failed: " + e.getMessage());
        }
    }

    private String resolvedPackages() {
        return cfg.trustedPackages != null ? cfg.trustedPackages
                : "it.cnr.isti.labsedc.concern,java.lang,java.util,javax.security";
    }

    public void close() { closeQuietly(); }

    private void closeQuietly() {
        try { if (producer   != null) producer.close();   } catch (Exception ignored) {}
        try { if (session    != null) session.close();    } catch (Exception ignored) {}
        try { if (connection != null) connection.close(); } catch (Exception ignored) {}
        producer = null; session = null; connection = null;
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
