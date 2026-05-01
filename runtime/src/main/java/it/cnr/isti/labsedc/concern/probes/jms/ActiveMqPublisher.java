package it.cnr.isti.labsedc.concern.probes.jms;

import it.cnr.isti.labsedc.concern.event.ConcernBaseEvent;
import it.cnr.isti.labsedc.concern.probes.core.BrokerConfig;
import jakarta.jms.*;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.ActiveMQSslConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-probe JMS publisher. Fully instance-scoped — no static state.
 *
 * Connection is established lazily on the first send attempt. If the broker is
 * unreachable at send time, the caller receives a JMSException and can buffer
 * the event locally. A subsequent send will try to reconnect automatically.
 * This keeps probe creation non-blocking (no retry loop in the constructor).
 */
public class ActiveMqPublisher {

    private static final Logger log = LoggerFactory.getLogger(ActiveMqPublisher.class);

    private final BrokerConfig cfg;
    private final String probeName;
    private final AtomicLong msgCounter = new AtomicLong();

    private Connection     connection;
    private Session        session;
    private MessageProducer producer;

    public ActiveMqPublisher(BrokerConfig cfg, String probeName) {
        this.cfg = cfg;
        this.probeName = probeName;
    }

    /**
     * Send one event. Connects lazily on first call; reconnects on stale session.
     * Throws JMSException if the broker is unreachable so the caller can buffer.
     */
    public synchronized void send(ConcernBaseEvent<?> event) throws JMSException {
        if (!isConnected()) {
            doConnect();   // throws JMSException if broker is down — let caller buffer
        }
        try {
            doSend(event);
        } catch (JMSException e) {
            // Session may have gone stale; close and try once more
            log.warn("[{}] send failed ({}), reconnecting once", probeName, e.getMessage());
            closeQuietly();
            doConnect();   // second attempt; throws if still down
            doSend(event);
        }
    }

    private boolean isConnected() {
        return session != null && producer != null;
    }

    private void doConnect() throws JMSException {
        ConnectionFactory factory = cfg.ssl ? buildSslFactory() : buildPlainFactory();
        connection = ((ActiveMQConnectionFactory) factory).createConnection(cfg.username, cfg.password);
        session    = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Topic topic = session.createTopic(cfg.topic);
        producer   = session.createProducer(topic);
        connection.start();
        log.info("[{}] connected to broker {}", probeName, cfg.url);
    }

    private void doSend(ConcernBaseEvent<?> event) throws JMSException {
        ObjectMessage msg = session.createObjectMessage();
        msg.setJMSMessageID(String.valueOf(msgCounter.incrementAndGet()));
        msg.setObject(event);
        producer.send(msg);
    }

    private ActiveMQConnectionFactory buildPlainFactory() {
        var f = new ActiveMQConnectionFactory(cfg.url);
        f.setTrustAllPackages(false);
        f.setTrustedPackages(new ArrayList<>(Arrays.asList(resolvedPackages().split(","))));
        return f;
    }

    private ActiveMQConnectionFactory buildSslFactory() throws JMSException {
        try {
            var f = new ActiveMQSslConnectionFactory(cfg.url);
            if (cfg.keyStore != null)           System.setProperty("javax.net.ssl.keyStore",           cfg.keyStore);
            if (cfg.keyStorePassword != null)   System.setProperty("javax.net.ssl.keyStorePassword",   cfg.keyStorePassword);
            if (cfg.trustStore != null)         f.setTrustStore(cfg.trustStore);
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

    public synchronized void close() { closeQuietly(); }

    private void closeQuietly() {
        try { if (producer   != null) producer.close(); }   catch (Exception ignored) {}
        try { if (session    != null) session.close(); }    catch (Exception ignored) {}
        try { if (connection != null) connection.close(); } catch (Exception ignored) {}
        producer = null; session = null; connection = null;
    }
}

