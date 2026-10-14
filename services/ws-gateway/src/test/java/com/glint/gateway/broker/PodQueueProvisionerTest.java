package com.glint.gateway.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.MessageConsumer;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.activemq.ArtemisContainer;

class PodQueueProvisionerTest {

    static final ArtemisContainer ARTEMIS = new ArtemisContainer("apache/activemq-artemis:2.43.0");

    @BeforeAll static void start() { ARTEMIS.start(); }
    @AfterAll static void stop() { ARTEMIS.stop(); }

    private ActiveMQConnectionFactory factory() {
        return new ActiveMQConnectionFactory(ARTEMIS.getBrokerUrl(), ARTEMIS.getUser(), ARTEMIS.getPassword());
    }

    private void provision(String podId) {
        new PodQueueProvisioner(ARTEMIS.getBrokerUrl(), ARTEMIS.getUser(), ARTEMIS.getPassword(), podId).provision();
    }

    private void publish(String text) throws JMSException {
        try (Connection c = factory().createConnection()) {
            Session s = c.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageProducer p = s.createProducer(s.createTopic("chat.events"));
            p.send(s.createTextMessage(text));
        }
    }

    @Test
    void aPodQueueReceivesWhatIsPublishedToTheTopic() throws Exception {
        provision("pod-a");
        try (Connection c = factory().createConnection()) {
            c.start();
            Session s = c.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageConsumer consumer = s.createConsumer(s.createQueue("chat.events::pod-a"));
            publish("hello");
            TextMessage m = (TextMessage) consumer.receive(5000);
            assertThat(m).isNotNull();
            assertThat(m.getText()).isEqualTo("hello");
        }
    }

    @Test
    void everyPodQueueGetsItsOwnCopy() throws Exception {
        provision("pod-1");
        provision("pod-2");
        try (Connection c1 = factory().createConnection(); Connection c2 = factory().createConnection()) {
            c1.start(); c2.start();
            Session s1 = c1.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Session s2 = c2.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageConsumer a = s1.createConsumer(s1.createQueue("chat.events::pod-1"));
            MessageConsumer b = s2.createConsumer(s2.createQueue("chat.events::pod-2"));
            publish("broadcast");
            assertThat(((TextMessage) a.receive(5000)).getText()).isEqualTo("broadcast");
            assertThat(((TextMessage) b.receive(5000)).getText()).isEqualTo("broadcast");
        }
    }

    @Test
    void aSecondConsumerOnTheSamePodQueueIsRejected() throws Exception {
        provision("pod-z");
        try (Connection c1 = factory().createConnection(); Connection c2 = factory().createConnection()) {
            c1.start(); c2.start();
            Session s1 = c1.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Session s2 = c2.createSession(false, Session.AUTO_ACKNOWLEDGE);
            s1.createConsumer(s1.createQueue("chat.events::pod-z"));
            assertThatThrownBy(() -> s2.createConsumer(s2.createQueue("chat.events::pod-z")))
                    .isInstanceOf(JMSException.class);
        }
    }

    @Test
    void provisioningTwiceDoesNotFail() {
        provision("pod-again");
        provision("pod-again");
    }
}
