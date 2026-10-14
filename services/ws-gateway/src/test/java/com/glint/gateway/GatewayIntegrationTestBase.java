package com.glint.gateway;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.activemq.ArtemisContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "gateway.pod-id=test-pod")
public abstract class GatewayIntegrationTestBase {
    protected static final ArtemisContainer ARTEMIS = new ArtemisContainer("apache/activemq-artemis:2.43.0");

    static { ARTEMIS.start(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.artemis.broker-url", ARTEMIS::getBrokerUrl);
        r.add("spring.artemis.user", ARTEMIS::getUser);
        r.add("spring.artemis.password", ARTEMIS::getPassword);
    }
}
