package com.glint.gateway.broker;

import jakarta.annotation.PostConstruct;
import org.apache.activemq.artemis.api.core.ActiveMQQueueExistsException;
import org.apache.activemq.artemis.api.core.QueueConfiguration;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.client.ClientSession;
import org.apache.activemq.artemis.api.core.client.ClientSessionFactory;
import org.apache.activemq.artemis.api.core.client.ServerLocator;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Creates this pod's queue before the listener starts: chat.events::&lt;podId&gt;, multicast, non-durable,
 * one consumer at most, nothing kept while no consumer is attached, deleted by the broker after the consumer leaves.
 * The queue has a stable name so the relay can target the pod later (Phase 2).
 */
@Component
public class PodQueueProvisioner {
    public static final String ADDRESS = "chat.events";
    private static final Logger log = LoggerFactory.getLogger(PodQueueProvisioner.class);

    private final String brokerUrl, user, password, podId;

    public PodQueueProvisioner(@Value("${spring.artemis.broker-url}") String brokerUrl,
                               @Value("${spring.artemis.user}") String user,
                               @Value("${spring.artemis.password}") String password,
                               @Value("${gateway.pod-id}") String podId) {
        this.brokerUrl = brokerUrl; this.user = user; this.password = password; this.podId = podId;
    }

    /**
     * JMS cannot set queue attributes, so this uses the Artemis Core API (the JMS factory only supplies the
     * server locator, which parses the same broker URL).
     */
    @PostConstruct
    public void provision() {
        ServerLocator locator = new ActiveMQConnectionFactory(brokerUrl, user, password).getServerLocator();
        try (ClientSessionFactory sessions = locator.createSessionFactory();
             ClientSession session = sessions.createSession(user, password, false, true, true, false, 0)) {
            session.createQueue(QueueConfiguration.of(podId)
                    .setAddress(ADDRESS)
                    .setRoutingType(RoutingType.MULTICAST)
                    .setDurable(false)
                    .setMaxConsumers(1)
                    .setPurgeOnNoConsumers(true)
                    .setAutoDelete(true)
                    .setAutoDeleteDelay(5_000L));
            log.info("created queue {}::{}", ADDRESS, podId);
        } catch (ActiveMQQueueExistsException e) {
            log.info("queue {}::{} already exists, reusing it", ADDRESS, podId);
        } catch (Exception e) {
            throw new IllegalStateException("cannot create queue " + ADDRESS + "::" + podId, e);
        } finally {
            locator.close();
        }
    }
}
