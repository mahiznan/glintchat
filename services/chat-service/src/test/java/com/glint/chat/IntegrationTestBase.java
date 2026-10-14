package com.glint.chat;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.activemq.ArtemisContainer;
import org.testcontainers.mariadb.MariaDBContainer;

@SpringBootTest(properties = "chat.relay.enabled=false")
public abstract class IntegrationTestBase {

    // Singleton containers: started once per JVM and shared by every test class.
    protected static final MariaDBContainer MARIADB = new MariaDBContainer("mariadb:11.4");
    protected static final ArtemisContainer ARTEMIS = new ArtemisContainer("apache/activemq-artemis:2.43.0");

    static {
        MARIADB.start();
        ARTEMIS.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MARIADB::getJdbcUrl);
        r.add("spring.datasource.username", MARIADB::getUsername);
        r.add("spring.datasource.password", MARIADB::getPassword);
        r.add("spring.artemis.broker-url", () -> ARTEMIS.getBrokerUrl() + "?blockOnNonDurableSend=true");
        r.add("spring.artemis.user", ARTEMIS::getUser);
        r.add("spring.artemis.password", ARTEMIS::getPassword);
    }
}
