# Happy-Path Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (inline, one task at a time, pause after every task as the user requires). Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A customer tab and an RM tab chat live through ws-gateway, chat-service, MariaDB and Artemis, with the message stored before it is published.

**Architecture:** chat-service stores the message and an outbox row in one transaction; a polling relay publishes the envelope to the topic `chat.events`; each ws-gateway pod consumes its own non-durable queue on that address and writes to the local sockets of the participants; the gateway never publishes. A plain HTML page is the client. Spec: `docs/superpowers/specs/2026-10-12-happy-path-slice-design.md`. Schema, address and envelope: `docs/chat-design.md`.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Maven, `JdbcTemplate`, Flyway, Spring JMS over Artemis Core, MariaDB 11.4, Artemis 2.43.0, JUnit 5, Testcontainers, `uuid-creator` 6.1.1.

## Global Constraints

- Java 21 only. `mvn` defaults to Java 27 on this machine (`JAVA_HOME` unset): every Maven command in this plan is run with `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` in the same shell, and the build fails on any other Java (Maven Enforcer, Task 0).
- Spring Boot 4.1.1 is set in one place in each `pom.xml` (the parent version). Starters: `spring-boot-starter-webmvc`, `-jdbc`, `-flyway` (+ `org.flywaydb:flyway-mysql`), `-artemis`, `-websocket`; MariaDB driver `org.mariadb.jdbc:mariadb-java-client`.
- Plain SQL with `JdbcTemplate`; no JPA. UUIDs are stored as `BINARY(16)`; UUID v7 for `messageId`, `eventId`, `conversationId`; UUID v4 for `clientMsgId` and `sessionId`.
- The insert transaction order is fixed: lock `conversation` row, check membership, idempotency check, assign `seq`, insert message, insert outbox row, commit.
- The gateway never publishes chat events. Relay publishes non-persistent to the topic `chat.events` with `blockOnNonDurableSend=true` in the broker URL.
- Test classes are named `*Test` so `mvn test` runs them (they use Testcontainers; Docker must be running). Tests never touch the lab containers.
- Secrets come from the environment (`set -a; source infra/.env; set +a`), never from committed files. Login in the gateway is a development stub and must be labeled so.
- Never commit until the user says "commit". Explain every file, flag and command to the user as it is created. Claude runs builds and tests to verify and says so plainly; the user runs the final two-tab check.

## File Structure

```
services/chat-service/
  pom.xml
  src/main/java/com/glint/chat/
    ChatServiceApplication.java
    ids/IdGenerator.java, ids/UuidV7Generator.java, ids/UuidBytes.java
    domain/SendCommand.java, SendResult.java, StoredMessage.java,
           ConversationNotFoundException.java, NotParticipantException.java
    repo/ConversationRepository.java, MessageRepository.java, OutboxRepository.java, OutboxRow.java
    service/MessageService.java, HistoryService.java
    event/MessageCreatedEvent.java, EventMessage.java
    relay/EventPublisher.java, OutboxRelay.java, RelayScheduler.java
    web/MessageController.java, ApiExceptionHandler.java, CorsConfig.java
  src/main/resources/application.yml, db/migration/V1__init.sql
  src/test/java/com/glint/chat/ ... (one test class per task)
services/ws-gateway/
  pom.xml
  src/main/java/com/glint/gateway/
    GatewayApplication.java
    socket/ClientSocket.java, SocketRegistry.java, WebSocketClientSocket.java
    routing/EventRouter.java
    broker/PodQueueProvisioner.java, PodEventListener.java
    chat/ChatServiceClient.java, SendResponse.java
    ws/ChatWebSocketHandler.java, WebSocketConfig.java
  src/main/resources/application.yml, static/index.html
  src/test/java/com/glint/gateway/ ...
infra/seed-dev.sql, infra/artemis-setup.sh
```

---

### Task 0: Environment and chat-service skeleton

**Files:**
- Create: `services/chat-service/pom.xml`, `ChatServiceApplication.java`, `src/main/resources/application.yml`

**Interfaces:**
- Produces: a compiling Spring Boot 4.1.1 project that fails fast on a Java other than 21.

- [ ] **Step 1: Confirm the Java 21 path**

Run: `/usr/libexec/java_home -v 21`
Expected: `/opt/homebrew/Cellar/openjdk@21/21.0.12.1/libexec/openjdk.jdk/Contents/Home` (the number may differ).

- [ ] **Step 2: Write `services/chat-service/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.1</version>
    <relativePath/>
  </parent>

  <groupId>com.glint</groupId>
  <artifactId>chat-service</artifactId>
  <version>0.0.1-SNAPSHOT</version>
  <name>chat-service</name>

  <properties>
    <java.version>21</java.version>
    <uuid-creator.version>6.1.1</uuid-creator.version>
  </properties>

  <dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-webmvc</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-jdbc</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-flyway</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-artemis</artifactId></dependency>
    <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-mysql</artifactId></dependency>
    <dependency><groupId>org.mariadb.jdbc</groupId><artifactId>mariadb-java-client</artifactId><scope>runtime</scope></dependency>
    <dependency><groupId>com.github.f4b6a3</groupId><artifactId>uuid-creator</artifactId><version>${uuid-creator.version}</version></dependency>

    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-webmvc-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-jdbc-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-flyway-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-artemis-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-testcontainers</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>testcontainers-junit-jupiter</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>testcontainers-mariadb</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>testcontainers-activemq</artifactId><scope>test</scope></dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-enforcer-plugin</artifactId>
        <version>3.5.0</version>
        <executions>
          <execution>
            <id>enforce-java-21</id>
            <goals><goal>enforce</goal></goals>
            <configuration>
              <rules>
                <requireJavaVersion><version>[21,22)</version></requireJavaVersion>
              </rules>
            </configuration>
          </execution>
        </executions>
      </plugin>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 3: Write the application class and `application.yml`**

`src/main/java/com/glint/chat/ChatServiceApplication.java`:
```java
package com.glint.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ChatServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ChatServiceApplication.class, args);
    }
}
```

`src/main/resources/application.yml`:
```yaml
spring:
  application:
    name: chat-service
  datasource:
    url: jdbc:mariadb://localhost:3306/${MARIADB_DATABASE}
    username: ${MARIADB_USER}
    password: ${MARIADB_PASSWORD}
  artemis:
    mode: native
    broker-url: tcp://localhost:61616?blockOnNonDurableSend=true
    user: ${ARTEMIS_USER}
    password: ${ARTEMIS_PASSWORD}
server:
  port: 8080
chat:
  relay:
    enabled: true
    poll-delay-ms: 200
    batch-size: 100
  cors:
    allowed-origin: http://localhost:8081
```

- [ ] **Step 4: Build under Java 21 and prove the Java rule works**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -f services/chat-service/pom.xml -q -DskipTests package`
Expected: BUILD SUCCESS (the first run downloads dependencies; the dependency names are the ones start.spring.io gave for 4.1.1).

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home mvn -f services/chat-service/pom.xml -q -DskipTests validate`
Expected: FAIL with an Enforcer message "Detected JDK ... is not in the allowed range [21,22)". If the path does not exist, skip this step and say so.

- [ ] **Step 5: Pause.** Show the user the file tree and explain each file, the parent version, the Enforcer rule and why it exists. Wait for questions. Do not commit.

---

### Task 1: Database schema with Flyway, and the integration-test base

**Files:**
- Create: `src/main/resources/db/migration/V1__init.sql`
- Create: `src/test/java/com/glint/chat/IntegrationTestBase.java`, `SchemaMigrationTest.java`

**Interfaces:**
- Produces: the four tables from `docs/chat-design.md` and `IntegrationTestBase` (shared MariaDB and Artemis containers, relay scheduler disabled) that later tests extend.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/glint/chat/IntegrationTestBase.java`:
```java
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
```

`src/test/java/com/glint/chat/SchemaMigrationTest.java`:
```java
package com.glint.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class SchemaMigrationTest extends IntegrationTestBase {

    @Autowired JdbcTemplate jdbc;

    @Test
    void flywayCreatesTheFourTables() {
        List<String> tables = jdbc.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()", String.class);
        assertThat(tables).contains("conversation", "conversation_participant", "message", "outbox");
    }

    @Test
    void messageHasTheTwoUniqueKeys() {
        List<String> keys = jdbc.queryForList(
            "SELECT DISTINCT index_name FROM information_schema.statistics "
          + "WHERE table_schema = DATABASE() AND table_name = 'message' AND non_unique = 0", String.class);
        assertThat(keys).contains("uq_conversation_seq", "uq_idempotency");
    }
}
```

- [ ] **Step 2: Run the test to see it fail**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -f services/chat-service/pom.xml -q test -Dtest=SchemaMigrationTest`
Expected: FAIL (tables missing). If the failure is a compile error on a Testcontainers import (Boot 4 / Testcontainers 2 package names), fix the import from the compiler message and note it for the user.

- [ ] **Step 3: Write the migration** `src/main/resources/db/migration/V1__init.sql`:

```sql
CREATE TABLE conversation (
  id           BINARY(16)   NOT NULL,
  customer_id  VARCHAR(64)  NOT NULL,
  last_seq     BIGINT       NOT NULL DEFAULT 0,
  created_at   DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_customer (customer_id)
) ENGINE=InnoDB;

CREATE TABLE conversation_participant (
  conversation_id      BINARY(16)   NOT NULL,
  user_id              VARCHAR(64)  NOT NULL,
  role                 ENUM('CUSTOMER','RM') NOT NULL,
  added_by             VARCHAR(64)  NULL,
  history_mode         ENUM('FULL','FROM_JOIN') NOT NULL DEFAULT 'FROM_JOIN',
  visible_from_seq     BIGINT       NOT NULL DEFAULT 1,
  last_read_seq        BIGINT       NOT NULL DEFAULT 0,
  joined_at            DATETIME(3)  NOT NULL,
  left_at              DATETIME(3)  NULL,
  can_read_after_leave TINYINT(1)   NOT NULL DEFAULT 0,
  PRIMARY KEY (conversation_id, user_id),
  KEY idx_user (user_id, conversation_id)
) ENGINE=InnoDB;

CREATE TABLE message (
  id              BINARY(16)   NOT NULL,
  conversation_id BINARY(16)   NOT NULL,
  seq             BIGINT       NOT NULL,
  sender_id       VARCHAR(64)  NOT NULL,
  client_msg_id   BINARY(16)   NOT NULL,
  type            VARCHAR(32)  NOT NULL,
  body            MEDIUMTEXT   NOT NULL,
  created_at      DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_conversation_seq (conversation_id, seq),
  UNIQUE KEY uq_idempotency (conversation_id, sender_id, client_msg_id)
) ENGINE=InnoDB;

CREATE TABLE outbox (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  event_id        BINARY(16)   NOT NULL,
  event_type      VARCHAR(40)  NOT NULL,
  conversation_id BINARY(16)   NOT NULL,
  payload         MEDIUMTEXT   NOT NULL,
  trace_id        VARCHAR(64)  NULL,
  created_at      DATETIME(3)  NOT NULL,
  published_at    DATETIME(3)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_event (event_id),
  KEY idx_unpublished (published_at, id)
) ENGINE=InnoDB;
```

- [ ] **Step 4: Run the test to see it pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -f services/chat-service/pom.xml -q test -Dtest=SchemaMigrationTest`
Expected: PASS (the first run pulls the MariaDB and Artemis images through Testcontainers and takes longer).

- [ ] **Step 5: Pause.** Explain each table (link back to `docs/chat-design.md`), why `BINARY(16)`, what Testcontainers did, and why tests do not use the lab containers. Wait for questions. Do not commit.

---

### Task 2: Identifiers (UUID v7 and the byte conversion)

**Files:**
- Create: `ids/IdGenerator.java`, `ids/UuidV7Generator.java`, `ids/UuidBytes.java`
- Test: `src/test/java/com/glint/chat/ids/UuidV7GeneratorTest.java`, `UuidBytesTest.java`

**Interfaces:**
- Produces: `interface IdGenerator { UUID newId(); }` (UUID v7), `UuidBytes.toBytes(UUID) -> byte[16]`, `UuidBytes.fromBytes(byte[]) -> UUID`.

- [ ] **Step 1: Write the failing tests**

`UuidV7GeneratorTest.java`:
```java
package com.glint.chat.ids;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidV7GeneratorTest {

    private final IdGenerator ids = new UuidV7Generator();

    @Test
    void generatesVersion7() {
        assertThat(ids.newId().version()).isEqualTo(7);
    }

    @Test
    void generatesUniqueIds() {
        Set<UUID> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) seen.add(ids.newId());
        assertThat(seen).hasSize(10_000);
    }

    @Test
    void laterIdsSortAfterEarlierOnes() throws Exception {
        UUID first = ids.newId();
        Thread.sleep(3);
        UUID second = ids.newId();
        assertThat(java.util.Arrays.compareUnsigned(UuidBytes.toBytes(first), UuidBytes.toBytes(second))).isNegative();
    }
}
```
`UuidBytesTest.java`:
```java
package com.glint.chat.ids;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidBytesTest {
    @Test
    void roundTrips() {
        UUID id = UUID.randomUUID();
        byte[] bytes = UuidBytes.toBytes(id);
        assertThat(bytes).hasSize(16);
        assertThat(UuidBytes.fromBytes(bytes)).isEqualTo(id);
    }
}
```

- [ ] **Step 2: Run to see failure.** `mvn -f services/chat-service/pom.xml -q test -Dtest='UuidV7GeneratorTest,UuidBytesTest'` (with `JAVA_HOME` as before). Expected: compile failure, classes missing.

- [ ] **Step 3: Implement**

`IdGenerator.java`:
```java
package com.glint.chat.ids;

import java.util.UUID;

/** The only place ids are created, so the strategy (UUID v7) can change in one file. */
public interface IdGenerator {
    UUID newId();
}
```
`UuidV7Generator.java`:
```java
package com.glint.chat.ids;

import com.github.f4b6a3.uuid.UuidCreator;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class UuidV7Generator implements IdGenerator {
    @Override
    public UUID newId() {
        return UuidCreator.getTimeOrderedEpoch(); // UUID v7 (RFC 9562)
    }
}
```
`UuidBytes.java`:
```java
package com.glint.chat.ids;

import java.nio.ByteBuffer;
import java.util.UUID;

/** BINARY(16) in MariaDB, UUID in Java. Big-endian, so the byte order equals the UUID order. */
public final class UuidBytes {
    private UuidBytes() {}

    public static byte[] toBytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }

    public static UUID fromBytes(byte[] bytes) {
        ByteBuffer b = ByteBuffer.wrap(bytes);
        return new UUID(b.getLong(), b.getLong());
    }
}
```

- [ ] **Step 4: Run to see pass.** Same command. Expected: 4 tests PASS. If `UuidCreator.getTimeOrderedEpoch()` is not found in 6.1.1, list the methods with `javap -cp <jar> com.github.f4b6a3.uuid.UuidCreator | grep -i epoch` and use the UUID v7 method it shows.

- [ ] **Step 5: Pause.** Explain why ids come from one interface, what UUID v7 gives (insert locality), and why byte order matters. Wait. Do not commit.

---

### Task 3: Repositories and the insert transaction

**Files:**
- Create: `domain/*.java`, `repo/ConversationRepository.java`, `repo/MessageRepository.java`, `repo/OutboxRepository.java`, `repo/OutboxRow.java`, `event/MessageCreatedEvent.java`, `event/EventMessage.java`, `service/MessageService.java`, a `Clock` bean in `ChatServiceApplication`
- Test: `src/test/java/com/glint/chat/TestData.java`, `service/MessageServiceTest.java`

**Interfaces:**
- Consumes: `IdGenerator`, `UuidBytes` (Task 2), schema (Task 1).
- Produces: `MessageService.send(SendCommand) -> SendResult`; `OutboxRepository.insert(...)`; `ConversationRepository.visibleFromSeq(UUID, String) -> Optional<Long>`; `MessageRepository.findAfterSeq(UUID, long, long, int) -> List<StoredMessage>`.

- [ ] **Step 1: Domain and event types**

`domain/SendCommand.java`:
```java
package com.glint.chat.domain;

import java.util.UUID;

public record SendCommand(UUID conversationId, String userId, String originSessionId,
                          UUID clientMsgId, String type, String body) {}
```
`domain/SendResult.java`:
```java
package com.glint.chat.domain;

import java.util.UUID;

public record SendResult(UUID messageId, long seq, UUID clientMsgId, boolean duplicate) {}
```
`domain/StoredMessage.java`:
```java
package com.glint.chat.domain;

import java.time.Instant;
import java.util.UUID;

public record StoredMessage(UUID messageId, UUID conversationId, long seq, String senderId,
                            UUID clientMsgId, String type, String body, Instant createdAt) {}
```
`domain/ConversationNotFoundException.java`:
```java
package com.glint.chat.domain;

public class ConversationNotFoundException extends RuntimeException {
    public ConversationNotFoundException(String message) { super(message); }
}
```
`domain/NotParticipantException.java`:
```java
package com.glint.chat.domain;

public class NotParticipantException extends RuntimeException {
    public NotParticipantException(String message) { super(message); }
}
```
`event/EventMessage.java` and `event/MessageCreatedEvent.java` (times are ISO strings so the JSON does not depend on Jackson date settings):
```java
package com.glint.chat.event;

public record EventMessage(String messageId, long seq, String senderId, String clientMsgId,
                           String type, String body, String createdAt) {}
```
```java
package com.glint.chat.event;

import java.util.List;

public record MessageCreatedEvent(int schemaVersion, String eventId, String eventType, String occurredAt,
                                  String traceId, String conversationId, List<String> participants,
                                  String originSessionId, EventMessage message) {}
```

- [ ] **Step 2: Write the failing tests**

`TestData.java` (test helper):
```java
package com.glint.chat;

import com.glint.chat.ids.IdGenerator;
import com.glint.chat.ids.UuidBytes;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class TestData {
    private TestData() {}

    /** One conversation with a customer and the given RMs. */
    public static UUID conversation(JdbcTemplate jdbc, IdGenerator ids, String customer, String... rms) {
        UUID id = ids.newId();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO conversation (id, customer_id, last_seq, created_at) VALUES (?,?,0,?)",
                UuidBytes.toBytes(id), customer, now);
        jdbc.update("INSERT INTO conversation_participant (conversation_id, user_id, role, joined_at) VALUES (?,?,'CUSTOMER',?)",
                UuidBytes.toBytes(id), customer, now);
        for (String rm : rms) {
            jdbc.update("INSERT INTO conversation_participant (conversation_id, user_id, role, joined_at) VALUES (?,?,'RM',?)",
                    UuidBytes.toBytes(id), rm, now);
        }
        return id;
    }
}
```
`service/MessageServiceTest.java`:
```java
package com.glint.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.glint.chat.IntegrationTestBase;
import com.glint.chat.TestData;
import com.glint.chat.domain.NotParticipantException;
import com.glint.chat.domain.SendCommand;
import com.glint.chat.domain.SendResult;
import com.glint.chat.ids.IdGenerator;
import com.glint.chat.ids.UuidBytes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class MessageServiceTest extends IntegrationTestBase {

    @Autowired MessageService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired IdGenerator ids;
    @Autowired TransactionTemplate tx;

    private SendCommand cmd(UUID conv, String user, UUID clientMsgId, String body) {
        return new SendCommand(conv, user, UUID.randomUUID().toString(), clientMsgId, "text", body);
    }

    private int count(String table, UUID conv) {
        String col = table.equals("conversation") ? "id" : "conversation_id";
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + col + " = ?",
                Integer.class, UuidBytes.toBytes(conv));
    }

    @Test
    void sendAssignsSeqOneAndWritesAnOutboxRow() {
        UUID conv = TestData.conversation(jdbc, ids, "c1", "r1");
        SendResult r = service.send(cmd(conv, "c1", UUID.randomUUID(), "hello"));
        assertThat(r.seq()).isEqualTo(1);
        assertThat(r.duplicate()).isFalse();
        assertThat(count("message", conv)).isEqualTo(1);
        String payload = jdbc.queryForObject(
                "SELECT payload FROM outbox WHERE conversation_id = ?", String.class, UuidBytes.toBytes(conv));
        assertThat(payload).contains(r.messageId().toString()).contains("\"participants\"").contains("hello");
    }

    @Test
    void retryWithTheSameClientMsgIdReturnsTheOriginalResult() {
        UUID conv = TestData.conversation(jdbc, ids, "c1", "r1");
        UUID clientMsgId = UUID.randomUUID();
        SendResult first = service.send(cmd(conv, "c1", clientMsgId, "hello"));
        SendResult retry = service.send(cmd(conv, "c1", clientMsgId, "hello"));
        assertThat(retry.duplicate()).isTrue();
        assertThat(retry.messageId()).isEqualTo(first.messageId());
        assertThat(retry.seq()).isEqualTo(first.seq());
        assertThat(count("message", conv)).isEqualTo(1);
        assertThat(count("outbox", conv)).isEqualTo(1);
    }

    @Test
    void concurrentDuplicatesCreateExactlyOneMessage() throws Exception {
        UUID conv = TestData.conversation(jdbc, ids, "c1", "r1");
        UUID clientMsgId = UUID.randomUUID();
        List<SendResult> results = runConcurrently(10, i -> service.send(cmd(conv, "c1", clientMsgId, "same")));
        Set<UUID> messageIds = new HashSet<>();
        results.forEach(r -> messageIds.add(r.messageId()));
        assertThat(messageIds).hasSize(1);
        assertThat(count("message", conv)).isEqualTo(1);
        assertThat(results.stream().filter(r -> !r.duplicate()).count()).isEqualTo(1);
    }

    @Test
    void concurrentSendsGetGapFreeSequenceNumbers() throws Exception {
        UUID conv = TestData.conversation(jdbc, ids, "c1", "r1");
        List<SendResult> results = runConcurrently(20, i -> service.send(cmd(conv, "c1", UUID.randomUUID(), "m" + i)));
        Set<Long> seqs = new HashSet<>();
        results.forEach(r -> seqs.add(r.seq()));
        Set<Long> expected = new HashSet<>();
        for (long s = 1; s <= 20; s++) expected.add(s);
        assertThat(seqs).isEqualTo(expected);
    }

    @Test
    void aNonParticipantIsRejectedAndNothingIsWritten() {
        UUID conv = TestData.conversation(jdbc, ids, "c1", "r1");
        assertThatThrownBy(() -> service.send(cmd(conv, "stranger", UUID.randomUUID(), "hi")))
                .isInstanceOf(NotParticipantException.class);
        assertThat(count("message", conv)).isZero();
        assertThat(count("outbox", conv)).isZero();
    }

    @Test
    void aRollbackLeavesNoMessageAndNoOutboxRow() {
        UUID conv = TestData.conversation(jdbc, ids, "c1", "r1");
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            service.send(cmd(conv, "c1", UUID.randomUUID(), "doomed"));
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count("message", conv)).isZero();
        assertThat(count("outbox", conv)).isZero();
        assertThat(jdbc.queryForObject("SELECT last_seq FROM conversation WHERE id = ?",
                Long.class, UuidBytes.toBytes(conv))).isZero();
    }

    private <T> List<T> runConcurrently(int n, java.util.function.IntFunction<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Callable<T>> tasks = new ArrayList<>();
            for (int i = 0; i < n; i++) { final int idx = i; tasks.add(() -> work.apply(idx)); }
            List<T> out = new ArrayList<>();
            for (Future<T> f : pool.invokeAll(tasks)) out.add(f.get());
            return out;
        } finally {
            pool.shutdown();
        }
    }
}
```

- [ ] **Step 3: Run to see failure.** `mvn -f services/chat-service/pom.xml -q test -Dtest=MessageServiceTest` (with `JAVA_HOME`). Expected: compile failure (`MessageService` missing).

- [ ] **Step 4: Repositories**

`repo/ConversationRepository.java`:
```java
package com.glint.chat.repo;

import com.glint.chat.ids.UuidBytes;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ConversationRepository {
    private final JdbcTemplate jdbc;

    public ConversationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Step 1 of the insert transaction: lock the row and read last_seq. Concurrent senders wait here. */
    public Optional<Long> lockAndGetLastSeq(UUID id) {
        return jdbc.query("SELECT last_seq FROM conversation WHERE id = ? FOR UPDATE",
                (rs, i) -> rs.getLong(1), UuidBytes.toBytes(id)).stream().findFirst();
    }

    public void updateLastSeq(UUID id, long seq) {
        jdbc.update("UPDATE conversation SET last_seq = ? WHERE id = ?", seq, UuidBytes.toBytes(id));
    }

    public boolean isActiveParticipant(UUID conversationId, String userId) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM conversation_participant WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL",
            Integer.class, UuidBytes.toBytes(conversationId), userId);
        return n != null && n > 0;
    }

    public List<String> activeParticipantIds(UUID conversationId) {
        return jdbc.queryForList(
            "SELECT user_id FROM conversation_participant WHERE conversation_id = ? AND left_at IS NULL ORDER BY role, user_id",
            String.class, UuidBytes.toBytes(conversationId));
    }

    public Optional<Long> visibleFromSeq(UUID conversationId, String userId) {
        return jdbc.query(
            "SELECT visible_from_seq FROM conversation_participant WHERE conversation_id = ? AND user_id = ? AND left_at IS NULL",
            (rs, i) -> rs.getLong(1), UuidBytes.toBytes(conversationId), userId).stream().findFirst();
    }
}
```
`repo/MessageRepository.java`:
```java
package com.glint.chat.repo;

import com.glint.chat.domain.StoredMessage;
import com.glint.chat.ids.UuidBytes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class MessageRepository {
    private static final RowMapper<StoredMessage> MAPPER = MessageRepository::map;
    private final JdbcTemplate jdbc;

    public MessageRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(StoredMessage m) {
        jdbc.update("INSERT INTO message (id, conversation_id, seq, sender_id, client_msg_id, type, body, created_at) "
                  + "VALUES (?,?,?,?,?,?,?,?)",
                UuidBytes.toBytes(m.messageId()), UuidBytes.toBytes(m.conversationId()), m.seq(), m.senderId(),
                UuidBytes.toBytes(m.clientMsgId()), m.type(), m.body(), Timestamp.from(m.createdAt()));
    }

    public Optional<StoredMessage> findByIdempotencyKey(UUID conversationId, String senderId, UUID clientMsgId) {
        return jdbc.query("SELECT * FROM message WHERE conversation_id = ? AND sender_id = ? AND client_msg_id = ?",
                MAPPER, UuidBytes.toBytes(conversationId), senderId, UuidBytes.toBytes(clientMsgId)).stream().findFirst();
    }

    /** Messages after {@code afterSeq} that the participant may see ({@code seq >= visibleFromSeq}), ascending. */
    public List<StoredMessage> findAfterSeq(UUID conversationId, long afterSeq, long visibleFromSeq, int limit) {
        return jdbc.query("SELECT * FROM message WHERE conversation_id = ? AND seq > ? AND seq >= ? ORDER BY seq LIMIT ?",
                MAPPER, UuidBytes.toBytes(conversationId), afterSeq, visibleFromSeq, limit);
    }

    private static StoredMessage map(ResultSet rs, int i) throws SQLException {
        return new StoredMessage(UuidBytes.fromBytes(rs.getBytes("id")), UuidBytes.fromBytes(rs.getBytes("conversation_id")),
                rs.getLong("seq"), rs.getString("sender_id"), UuidBytes.fromBytes(rs.getBytes("client_msg_id")),
                rs.getString("type"), rs.getString("body"), rs.getTimestamp("created_at").toInstant());
    }
}
```
`repo/OutboxRow.java`:
```java
package com.glint.chat.repo;

import java.util.UUID;

public record OutboxRow(long id, UUID eventId, String eventType, UUID conversationId, String payload) {}
```
`repo/OutboxRepository.java`:
```java
package com.glint.chat.repo;

import com.glint.chat.ids.UuidBytes;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {
    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void insert(UUID eventId, String eventType, UUID conversationId, String payload, String traceId, Instant now) {
        jdbc.update("INSERT INTO outbox (event_id, event_type, conversation_id, payload, trace_id, created_at) VALUES (?,?,?,?,?,?)",
                UuidBytes.toBytes(eventId), eventType, UuidBytes.toBytes(conversationId), payload, traceId, Timestamp.from(now));
    }

    /** Oldest unpublished rows, locked for this transaction; rows locked by another relay are skipped. */
    public List<OutboxRow> lockUnpublishedBatch(int limit) {
        return jdbc.query("SELECT id, event_id, event_type, conversation_id, payload FROM outbox "
                        + "WHERE published_at IS NULL ORDER BY id LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs, i) -> new OutboxRow(rs.getLong("id"), UuidBytes.fromBytes(rs.getBytes("event_id")),
                        rs.getString("event_type"), UuidBytes.fromBytes(rs.getBytes("conversation_id")),
                        rs.getString("payload")), limit);
    }

    public void markPublished(long id, Instant now) {
        jdbc.update("UPDATE outbox SET published_at = ? WHERE id = ?", Timestamp.from(now), id);
    }
}
```

- [ ] **Step 5: The service** `service/MessageService.java`

```java
package com.glint.chat.service;

import com.glint.chat.domain.ConversationNotFoundException;
import com.glint.chat.domain.NotParticipantException;
import com.glint.chat.domain.SendCommand;
import com.glint.chat.domain.SendResult;
import com.glint.chat.domain.StoredMessage;
import com.glint.chat.event.EventMessage;
import com.glint.chat.event.MessageCreatedEvent;
import com.glint.chat.ids.IdGenerator;
import com.glint.chat.repo.ConversationRepository;
import com.glint.chat.repo.MessageRepository;
import com.glint.chat.repo.OutboxRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class MessageService {
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final OutboxRepository outbox;
    private final IdGenerator ids;
    private final Clock clock;
    private final ObjectMapper json;

    public MessageService(ConversationRepository conversations, MessageRepository messages, OutboxRepository outbox,
                          IdGenerator ids, Clock clock, ObjectMapper json) {
        this.conversations = conversations; this.messages = messages; this.outbox = outbox;
        this.ids = ids; this.clock = clock; this.json = json;
    }

    /** The persist-before-publish transaction. The order of the steps is the design (docs/chat-design.md section 2). */
    @Transactional
    public SendResult send(SendCommand c) {
        // 1. Lock the conversation row. Concurrent sends to the same conversation queue up here.
        long lastSeq = conversations.lockAndGetLastSeq(c.conversationId())
                .orElseThrow(() -> new ConversationNotFoundException("conversation " + c.conversationId()));
        // 2. Membership.
        if (!conversations.isActiveParticipant(c.conversationId(), c.userId())) {
            throw new NotParticipantException(c.userId() + " is not a participant of " + c.conversationId());
        }
        // 3. Idempotency: a retry returns the original result.
        Optional<StoredMessage> existing = messages.findByIdempotencyKey(c.conversationId(), c.userId(), c.clientMsgId());
        if (existing.isPresent()) {
            StoredMessage m = existing.get();
            return new SendResult(m.messageId(), m.seq(), m.clientMsgId(), true);
        }
        // 4. Assign seq and store the message.
        Instant now = clock.instant();
        long seq = lastSeq + 1;
        UUID messageId = ids.newId();
        conversations.updateLastSeq(c.conversationId(), seq);
        messages.insert(new StoredMessage(messageId, c.conversationId(), seq, c.userId(), c.clientMsgId(),
                c.type(), c.body(), now));
        // 5. The outbox row, in the same transaction, with the participants as they are right now.
        UUID eventId = ids.newId();
        List<String> participants = conversations.activeParticipantIds(c.conversationId());
        MessageCreatedEvent event = new MessageCreatedEvent(1, eventId.toString(), "message.created", now.toString(),
                UUID.randomUUID().toString(), c.conversationId().toString(), participants, c.originSessionId(),
                new EventMessage(messageId.toString(), seq, c.userId(), c.clientMsgId().toString(), c.type(),
                        c.body(), now.toString()));
        outbox.insert(eventId, "message.created", c.conversationId(), json.writeValueAsString(event),
                event.traceId(), now);
        return new SendResult(messageId, seq, c.clientMsgId(), false);
    }
}
```
Add a `Clock` bean to `ChatServiceApplication`:
```java
@org.springframework.context.annotation.Bean
Clock clock() { return Clock.systemUTC(); }
```
(with `import java.time.Clock;`).

- [ ] **Step 6: Run to see pass.** `mvn -f services/chat-service/pom.xml -q test -Dtest=MessageServiceTest` (with `JAVA_HOME`). Expected: 6 tests PASS. If `tools.jackson.databind.ObjectMapper` does not resolve (Boot 4 uses Jackson 3), read the compiler error and fix the import; if the bean is `JsonMapper`, inject that type. Tell the user what changed.

- [ ] **Step 7: Pause.** Walk the user through the six steps of `send`, in order, and what each test proves (S17 duplicates, gap-free `seq`, rollback). Ask the cross-questions: what breaks if step 1 and step 3 are swapped; why `seq` is not `AUTO_INCREMENT`. Wait. Do not commit.

---

### Task 4: The outbox relay and the publisher

**Files:**
- Create: `relay/EventPublisher.java`, `relay/OutboxRelay.java`, `relay/RelayScheduler.java`
- Test: `relay/OutboxRelayTest.java`

**Interfaces:**
- Consumes: `OutboxRepository.lockUnpublishedBatch(int)`, `OutboxRepository.markPublished(long, Instant)`, `OutboxRow` (Task 3).
- Produces: `OutboxRelay.relayOnce() -> int` (number of rows published); `EventPublisher.publish(OutboxRow)`.

- [ ] **Step 1: Write the failing test** `relay/OutboxRelayTest.java`

```java
package com.glint.chat.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.glint.chat.IntegrationTestBase;
import com.glint.chat.TestData;
import com.glint.chat.domain.SendCommand;
import com.glint.chat.domain.SendResult;
import com.glint.chat.ids.IdGenerator;
import com.glint.chat.ids.UuidBytes;
import com.glint.chat.repo.OutboxRow;
import com.glint.chat.service.MessageService;
import jakarta.jms.Connection;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import java.util.UUID;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class OutboxRelayTest extends IntegrationTestBase {

    @Autowired OutboxRelay relay;
    @Autowired MessageService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired IdGenerator ids;
    @MockitoSpyBean EventPublisher publisher;

    private SendResult send(UUID conv) {
        return service.send(new SendCommand(conv, "c1", "session-1", UUID.randomUUID(), "text", "hello"));
    }

    private int unpublished(UUID conv) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE conversation_id = ? AND published_at IS NULL",
                Integer.class, UuidBytes.toBytes(conv));
    }

    @Test
    void publishesToTheTopicAndMarksTheRowPublished() throws Exception {
        UUID conv = TestData.conversation(jdbc, ids, "c1", "r1");
        // Drain anything left by other tests, then subscribe BEFORE publishing (a topic only reaches existing subscribers).
        relay.relayOnce();
        ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory(ARTEMIS.getBrokerUrl(), ARTEMIS.getUser(), ARTEMIS.getPassword());
        try (Connection conn = cf.createConnection()) {
            conn.start();
            Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageConsumer consumer = session.createConsumer(session.createTopic("chat.events"));

            SendResult sent = send(conv);
            assertThat(unpublished(conv)).isEqualTo(1);
            assertThat(relay.relayOnce()).isGreaterThanOrEqualTo(1);

            TextMessage received = (TextMessage) consumer.receive(5000);
            assertThat(received).isNotNull();
            assertThat(received.getStringProperty("eventType")).isEqualTo("message.created");
            assertThat(received.getStringProperty("conversationId")).isEqualTo(conv.toString());
            assertThat(received.getText()).contains(sent.messageId().toString());
            assertThat(unpublished(conv)).isZero();
            assertThat(relay.relayOnce()).isZero();
        }
    }

    @Test
    void aFailedPublishLeavesTheRowForTheNextAttempt() {
        UUID conv = TestData.conversation(jdbc, ids, "c1", "r1");
        relay.relayOnce();
        send(conv);
        doThrow(new IllegalStateException("broker down")).when(publisher).publish(any(OutboxRow.class));
        assertThatThrownBy(() -> relay.relayOnce()).isInstanceOf(IllegalStateException.class);
        assertThat(unpublished(conv)).isEqualTo(1);

        Mockito.reset(publisher); // the broker is back
        assertThat(relay.relayOnce()).isGreaterThanOrEqualTo(1);
        assertThat(unpublished(conv)).isZero();
    }
}
```

- [ ] **Step 2: Run to see failure.** `mvn -f services/chat-service/pom.xml -q test -Dtest=OutboxRelayTest`. Expected: compile failure.

- [ ] **Step 3: Implement**

`relay/EventPublisher.java`:
```java
package com.glint.chat.relay;

import com.glint.chat.repo.OutboxRow;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.DeliveryMode;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

/** Publishes one outbox row to the multicast address chat.events. Non-persistent: per-pod queues are non-durable. */
@Component
public class EventPublisher {
    public static final String ADDRESS = "chat.events";
    private final JmsTemplate jms;

    public EventPublisher(ConnectionFactory connectionFactory) {
        this.jms = new JmsTemplate(connectionFactory);
        this.jms.setPubSubDomain(true);                       // topic = multicast
        this.jms.setExplicitQosEnabled(true);
        this.jms.setDeliveryMode(DeliveryMode.NON_PERSISTENT);
    }

    public void publish(OutboxRow row) {
        jms.send(ADDRESS, session -> {
            var message = session.createTextMessage(row.payload());
            message.setStringProperty("eventType", row.eventType());
            message.setStringProperty("conversationId", row.conversationId().toString());
            message.setStringProperty("eventId", row.eventId().toString());
            return message;
        });
    }
}
```
`relay/OutboxRelay.java`:
```java
package com.glint.chat.relay;

import com.glint.chat.repo.OutboxRepository;
import com.glint.chat.repo.OutboxRow;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxRelay {
    private final OutboxRepository outbox;
    private final EventPublisher publisher;
    private final Clock clock;
    private final int batchSize;

    public OutboxRelay(OutboxRepository outbox, EventPublisher publisher, Clock clock,
                       @Value("${chat.relay.batch-size:100}") int batchSize) {
        this.outbox = outbox; this.publisher = publisher; this.clock = clock; this.batchSize = batchSize;
    }

    /**
     * One polling round. Rows are locked (SKIP LOCKED) for the whole transaction. If a send fails the exception
     * rolls the transaction back, so the rows stay unpublished and the next round retries them (at least once).
     */
    @Transactional
    public int relayOnce() {
        List<OutboxRow> rows = outbox.lockUnpublishedBatch(batchSize);
        for (OutboxRow row : rows) {
            publisher.publish(row);
            outbox.markPublished(row.id(), clock.instant());
        }
        return rows.size();
    }
}
```
`relay/RelayScheduler.java`:
```java
package com.glint.chat.relay;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "chat.relay.enabled", havingValue = "true", matchIfMissing = true)
public class RelayScheduler {
    private final OutboxRelay relay;

    public RelayScheduler(OutboxRelay relay) { this.relay = relay; }

    @Scheduled(fixedDelayString = "${chat.relay.poll-delay-ms:200}")
    public void poll() { relay.relayOnce(); }
}
```

- [ ] **Step 4: Run to see pass.** Same command. Expected: 2 tests PASS. If the message arrives only after the 5 s timeout or `receive` returns null, check that the subscription was created before the send and that `blockOnNonDurableSend=true` is in the broker URL (the test base adds it).

- [ ] **Step 5: Pause.** Explain: why `fixedDelay`, what `SKIP LOCKED` does with two relays, why the rollback test proves at-least-once, and the open item that the relay holds row locks while sending (Spec, risk 4). Ask: what happens if the process crashes after `publish` but before `markPublished`? Wait. Do not commit.

---

### Task 5: REST API (send and history)

**Files:**
- Create: `service/HistoryService.java`, `web/MessageController.java`, `web/ApiExceptionHandler.java`, `web/CorsConfig.java`
- Test: `web/MessageApiTest.java`

**Interfaces:**
- Consumes: `MessageService.send`, `ConversationRepository.visibleFromSeq`, `MessageRepository.findAfterSeq`.
- Produces: `POST /internal/conversations/{id}/messages` and `GET /conversations/{id}/messages` as specified in the spec.

- [ ] **Step 1: Write the failing test** `web/MessageApiTest.java`

```java
package com.glint.chat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.glint.chat.IntegrationTestBase;
import com.glint.chat.TestData;
import com.glint.chat.ids.IdGenerator;
import com.glint.chat.ids.UuidBytes;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "chat.relay.enabled=false")
class MessageApiTest extends IntegrationTestBase {

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired IdGenerator ids;

    private RestClient http() { return RestClient.create("http://localhost:" + port); }

    private Map<?, ?> send(UUID conv, String user, UUID clientMsgId, String body) {
        return http().post().uri("/internal/conversations/{id}/messages", conv)
                .body(Map.of("userId", user, "originSessionId", "s1", "clientMsgId", clientMsgId.toString(),
                        "type", "text", "body", body))
                .retrieve().body(Map.class);
    }

    private Map<?, ?> history(UUID conv, String user, long afterSeq) {
        return http().get().uri("/conversations/{id}/messages?afterSeq={a}", conv, afterSeq)
                .header("X-User-Id", user).retrieve().body(Map.class);
    }

    @Test
    void sendThenReadHistory() {
        UUID conv = TestData.conversation(jdbc, ids, "c1", "r1");
        Map<?, ?> ack = send(conv, "c1", UUID.randomUUID(), "hello");
        assertThat(ack.get("seq")).isEqualTo(1);
        assertThat(ack.get("duplicate")).isEqualTo(false);

        List<?> messages = (List<?>) history(conv, "r1", 0).get("messages");
        assertThat(messages).hasSize(1);
        assertThat(((Map<?, ?>) messages.get(0)).get("body")).isEqualTo("hello");
    }

    @Test
    void afterSeqSkipsEarlierMessages() {
        UUID conv = TestData.conversation(jdbc, ids, "c1", "r1");
        send(conv, "c1", UUID.randomUUID(), "one");
        send(conv, "r1", UUID.randomUUID(), "two");
        List<?> messages = (List<?>) history(conv, "c1", 1).get("messages");
        assertThat(messages).hasSize(1);
        assertThat(((Map<?, ?>) messages.get(0)).get("seq")).isEqualTo(2);
    }

    @Test
    void aStrangerGets403OnSendAndOnHistory() {
        UUID conv = TestData.conversation(jdbc, ids, "c1", "r1");
        assertThatThrownBy(() -> send(conv, "stranger", UUID.randomUUID(), "x"))
                .isInstanceOfSatisfying(RestClientResponseException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
        assertThatThrownBy(() -> history(conv, "stranger", 0))
                .isInstanceOfSatisfying(RestClientResponseException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(403));
    }

    @Test
    void anUnknownConversationGets404() {
        assertThatThrownBy(() -> send(UUID.randomUUID(), "c1", UUID.randomUUID(), "x"))
                .isInstanceOfSatisfying(RestClientResponseException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(404));
    }

    @Test
    void historyHidesMessagesBeforeVisibleFromSeq() {
        UUID conv = TestData.conversation(jdbc, ids, "c1", "r1");
        send(conv, "c1", UUID.randomUUID(), "before-join");
        send(conv, "c1", UUID.randomUUID(), "after-join");
        jdbc.update("UPDATE conversation_participant SET visible_from_seq = 2 WHERE conversation_id = ? AND user_id = 'r1'",
                UuidBytes.toBytes(conv));
        List<?> messages = (List<?>) history(conv, "r1", 0).get("messages");
        assertThat(messages).hasSize(1);
        assertThat(((Map<?, ?>) messages.get(0)).get("body")).isEqualTo("after-join");
    }

    @Test
    void aBlankBodyGets400() {
        UUID conv = TestData.conversation(jdbc, ids, "c1", "r1");
        assertThatThrownBy(() -> send(conv, "c1", UUID.randomUUID(), " "))
                .isInstanceOfSatisfying(RestClientResponseException.class, e -> assertThat(e.getStatusCode().value()).isEqualTo(400));
    }
}
```

- [ ] **Step 2: Run to see failure.** `mvn -f services/chat-service/pom.xml -q test -Dtest=MessageApiTest`. Expected: failures (404 on the endpoints).

- [ ] **Step 3: Implement**

`service/HistoryService.java`:
```java
package com.glint.chat.service;

import com.glint.chat.domain.NotParticipantException;
import com.glint.chat.domain.StoredMessage;
import com.glint.chat.repo.ConversationRepository;
import com.glint.chat.repo.MessageRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class HistoryService {
    private final ConversationRepository conversations;
    private final MessageRepository messages;

    public HistoryService(ConversationRepository conversations, MessageRepository messages) {
        this.conversations = conversations; this.messages = messages;
    }

    /** The server, not the client, applies the participant's visible_from_seq. */
    public List<StoredMessage> history(UUID conversationId, String userId, long afterSeq, int limit) {
        long visibleFrom = conversations.visibleFromSeq(conversationId, userId)
                .orElseThrow(() -> new NotParticipantException(userId + " is not a participant of " + conversationId));
        return messages.findAfterSeq(conversationId, afterSeq, visibleFrom, Math.max(1, Math.min(limit, 200)));
    }
}
```
`web/MessageController.java`:
```java
package com.glint.chat.web;

import com.glint.chat.domain.SendCommand;
import com.glint.chat.domain.SendResult;
import com.glint.chat.domain.StoredMessage;
import com.glint.chat.service.HistoryService;
import com.glint.chat.service.MessageService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MessageController {
    public record SendRequest(String userId, String originSessionId, UUID clientMsgId, String type, String body) {}
    public record SendResponse(UUID messageId, long seq, UUID clientMsgId, boolean duplicate) {}
    public record MessageDto(UUID messageId, long seq, String senderId, UUID clientMsgId, String type, String body, String createdAt) {}
    public record HistoryResponse(List<MessageDto> messages) {}

    private final MessageService messageService;
    private final HistoryService historyService;

    public MessageController(MessageService messageService, HistoryService historyService) {
        this.messageService = messageService; this.historyService = historyService;
    }

    /** Internal: called by the gateway. DEVELOPMENT: userId comes from the body; real auth replaces this. */
    @PostMapping("/internal/conversations/{conversationId}/messages")
    public SendResponse send(@PathVariable UUID conversationId, @RequestBody SendRequest r) {
        if (r.userId() == null || r.userId().isBlank() || r.clientMsgId() == null
                || r.body() == null || r.body().isBlank()) {
            throw new IllegalArgumentException("userId, clientMsgId and a non-blank body are required");
        }
        String type = (r.type() == null || r.type().isBlank()) ? "text" : r.type();
        SendResult res = messageService.send(
                new SendCommand(conversationId, r.userId(), r.originSessionId(), r.clientMsgId(), type, r.body()));
        return new SendResponse(res.messageId(), res.seq(), res.clientMsgId(), res.duplicate());
    }

    /** DEVELOPMENT: the caller is identified by the X-User-Id header. */
    @GetMapping("/conversations/{conversationId}/messages")
    public HistoryResponse history(@PathVariable UUID conversationId,
                                   @RequestHeader("X-User-Id") String userId,
                                   @RequestParam(defaultValue = "0") long afterSeq,
                                   @RequestParam(defaultValue = "50") int limit) {
        List<StoredMessage> list = historyService.history(conversationId, userId, afterSeq, limit);
        return new HistoryResponse(list.stream().map(m -> new MessageDto(m.messageId(), m.seq(), m.senderId(),
                m.clientMsgId(), m.type(), m.body(), m.createdAt().toString())).toList());
    }
}
```
`web/ApiExceptionHandler.java`:
```java
package com.glint.chat.web;

import com.glint.chat.domain.ConversationNotFoundException;
import com.glint.chat.domain.NotParticipantException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ConversationNotFoundException.class)
    ResponseEntity<Map<String, String>> notFound(ConversationNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "conversation_not_found"));
    }

    @ExceptionHandler(NotParticipantException.class)
    ResponseEntity<Map<String, String>> forbidden(NotParticipantException e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "not_a_participant"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "invalid_request"));
    }
}
```
`web/CorsConfig.java`:
```java
package com.glint.chat.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** DEVELOPMENT: the page is served by the gateway (8081) and reads history from here (8080). */
@Configuration
public class CorsConfig implements WebMvcConfigurer {
    private final String allowedOrigin;

    public CorsConfig(@Value("${chat.cors.allowed-origin}") String allowedOrigin) { this.allowedOrigin = allowedOrigin; }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/conversations/**").allowedOrigins(allowedOrigin).allowedMethods("GET").allowedHeaders("*");
    }
}
```

- [ ] **Step 4: Run to see pass, then the whole chat-service suite.**
`mvn -f services/chat-service/pom.xml -q test -Dtest=MessageApiTest` then `mvn -f services/chat-service/pom.xml test`
Expected: all tests PASS. If `@SpringBootTest(webEnvironment=...)` subclass conflicts with the base class annotation, drop the base class's `@SpringBootTest` into a meta-annotation or set the web environment in the base class for all tests; tell the user what you chose.

- [ ] **Step 5: Pause.** Demonstrate the idempotent retry through the API with `curl` against a chat-service run later (Task 9). Explain the visibility rule and why `visible_from_seq` is applied in the query and not in the client. Wait. Do not commit.

---

### Task 6: ws-gateway skeleton, socket registry and event routing

**Files:**
- Create: `services/ws-gateway/pom.xml`, `GatewayApplication.java`, `src/main/resources/application.yml`
- Create: `socket/ClientSocket.java`, `socket/SocketRegistry.java`, `routing/GatewayEvent.java`, `routing/EventRouter.java`
- Test: `src/test/java/com/glint/gateway/routing/EventRouterTest.java`

**Interfaces:**
- Produces: `ClientSocket { String sessionId(); String userId(); void send(String json); }`; `SocketRegistry.register/unregister/socketsOf(String userId)`; `EventRouter.route(String payload) -> int` (sockets written to).

- [ ] **Step 1: `services/ws-gateway/pom.xml`** (same parent, Java rule and plugins as chat-service)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.1</version>
    <relativePath/>
  </parent>

  <groupId>com.glint</groupId>
  <artifactId>ws-gateway</artifactId>
  <version>0.0.1-SNAPSHOT</version>
  <name>ws-gateway</name>

  <properties>
    <java.version>21</java.version>
  </properties>

  <dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-webmvc</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-websocket</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-artemis</artifactId></dependency>

    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-webmvc-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-websocket-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-artemis-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-testcontainers</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>testcontainers-junit-jupiter</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.testcontainers</groupId><artifactId>testcontainers-activemq</artifactId><scope>test</scope></dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-enforcer-plugin</artifactId>
        <version>3.5.0</version>
        <executions>
          <execution>
            <id>enforce-java-21</id>
            <goals><goal>enforce</goal></goals>
            <configuration>
              <rules><requireJavaVersion><version>[21,22)</version></requireJavaVersion></rules>
            </configuration>
          </execution>
        </executions>
      </plugin>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```
`GatewayApplication.java`:
```java
package com.glint.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
```
`src/main/resources/application.yml`:
```yaml
spring:
  application:
    name: ws-gateway
  artemis:
    mode: native
    broker-url: tcp://localhost:61616
    user: ${ARTEMIS_USER}
    password: ${ARTEMIS_PASSWORD}
server:
  port: 8081
gateway:
  # Names this pod's queue chat.events::<pod-id>. Set POD_ID to run more than one gateway locally.
  pod-id: ${POD_ID:${HOSTNAME:local}}
chat-service:
  base-url: http://localhost:8080
```

- [ ] **Step 2: Write the failing test** `routing/EventRouterTest.java`

```java
package com.glint.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.glint.gateway.socket.ClientSocket;
import com.glint.gateway.socket.SocketRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EventRouterTest {

    static class FakeSocket implements ClientSocket {
        final String sessionId, userId;
        final List<String> sent = new ArrayList<>();
        FakeSocket(String sessionId, String userId) { this.sessionId = sessionId; this.userId = userId; }
        public String sessionId() { return sessionId; }
        public String userId() { return userId; }
        public void send(String json) { sent.add(json); }
    }

    private final SocketRegistry registry = new SocketRegistry();
    private final EventRouter router = new EventRouter(registry, JsonMapper.builder().build());

    private String event(String origin, String... participants) {
        String list = String.join("\",\"", participants);
        return "{\"schemaVersion\":1,\"eventId\":\"e1\",\"eventType\":\"message.created\",\"occurredAt\":\"t\","
             + "\"traceId\":\"tr\",\"conversationId\":\"conv-1\",\"participants\":[\"" + list + "\"],"
             + "\"originSessionId\":" + (origin == null ? "null" : "\"" + origin + "\"") + ","
             + "\"message\":{\"messageId\":\"m1\",\"seq\":1,\"senderId\":\"c1\",\"clientMsgId\":\"x\","
             + "\"type\":\"text\",\"body\":\"hi\",\"createdAt\":\"t\"},\"futureField\":true}";
    }

    @Test
    void deliversToParticipantsAndSkipsTheOriginSocket() {
        FakeSocket origin = new FakeSocket("s1", "c1");
        FakeSocket otherTab = new FakeSocket("s2", "c1");
        FakeSocket rm = new FakeSocket("s3", "r1");
        registry.register(origin); registry.register(otherTab); registry.register(rm);

        int written = router.route(event("s1", "c1", "r1"));

        assertThat(written).isEqualTo(2);
        assertThat(origin.sent).isEmpty();
        assertThat(otherTab.sent).hasSize(1);
        assertThat(rm.sent).hasSize(1);
        assertThat(rm.sent.get(0)).contains("\"type\":\"message\"").contains("\"body\":\"hi\"").contains("conv-1");
    }

    @Test
    void ignoresParticipantsWithoutASocketOnThisPod() {
        FakeSocket rm = new FakeSocket("s3", "r1");
        registry.register(rm);
        assertThat(router.route(event(null, "c1", "r1"))).isEqualTo(1); // c1 is connected to another pod
    }

    @Test
    void ignoresMalformedAndUnknownEvents() {
        FakeSocket rm = new FakeSocket("s3", "r1");
        registry.register(rm);
        assertThat(router.route("not json")).isZero();
        assertThat(router.route(event(null, "r1").replace("message.created", "something.else"))).isZero();
        assertThat(rm.sent).isEmpty();
    }

    @Test
    void aFailingSocketDoesNotStopTheOthers() {
        ClientSocket broken = new FakeSocket("s1", "c1") {
            @Override public void send(String json) { throw new IllegalStateException("closed"); }
        };
        FakeSocket rm = new FakeSocket("s3", "r1");
        registry.register(broken); registry.register(rm);
        assertThat(router.route(event(null, "c1", "r1"))).isEqualTo(1);
        assertThat(rm.sent).hasSize(1);
    }

    @Test
    void unregisterRemovesTheSocket() {
        FakeSocket rm = new FakeSocket("s3", "r1");
        registry.register(rm);
        registry.unregister(rm);
        assertThat(registry.socketsOf("r1")).isEmpty();
    }
}
```

- [ ] **Step 3: Run to see failure.** `export JAVA_HOME=$(/usr/libexec/java_home -v 21) && mvn -f services/ws-gateway/pom.xml -q test -Dtest=EventRouterTest`. Expected: compile failure.

- [ ] **Step 4: Implement**

`socket/ClientSocket.java`:
```java
package com.glint.gateway.socket;

/** One open WebSocket of one user on this pod. Kept as an interface so routing can be tested without a network. */
public interface ClientSocket {
    String sessionId();
    String userId();
    void send(String json);
}
```
`socket/SocketRegistry.java`:
```java
package com.glint.gateway.socket;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** userId to the sockets this pod holds for that user (several tabs and devices are normal). */
@Component
public class SocketRegistry {
    private final ConcurrentHashMap<String, Set<ClientSocket>> byUser = new ConcurrentHashMap<>();

    public void register(ClientSocket socket) {
        byUser.computeIfAbsent(socket.userId(), k -> ConcurrentHashMap.newKeySet()).add(socket);
    }

    public void unregister(ClientSocket socket) {
        byUser.computeIfPresent(socket.userId(), (k, set) -> {
            set.remove(socket);
            return set.isEmpty() ? null : set;
        });
    }

    public Set<ClientSocket> socketsOf(String userId) {
        return byUser.getOrDefault(userId, Set.of());
    }
}
```
`routing/GatewayEvent.java`:
```java
package com.glint.gateway.routing;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** The consumer's view of the envelope on chat.events. Unknown fields are ignored (rolling deploys). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GatewayEvent(int schemaVersion, String eventId, String eventType, String conversationId,
                           List<String> participants, String originSessionId, Message message) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String messageId, long seq, String senderId, String clientMsgId,
                          String type, String body, String createdAt) {}
}
```
`routing/EventRouter.java`:
```java
package com.glint.gateway.routing;

import com.glint.gateway.socket.ClientSocket;
import com.glint.gateway.socket.SocketRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class EventRouter {
    private static final Logger log = LoggerFactory.getLogger(EventRouter.class);

    public record MessageFrame(String type, String conversationId, GatewayEvent.Message message) {}

    private final SocketRegistry registry;
    private final ObjectMapper json;

    public EventRouter(SocketRegistry registry, ObjectMapper json) {
        this.registry = registry; this.json = json;
    }

    /** Writes the event to every local socket of every participant, except the socket that sent it. */
    public int route(String payload) {
        GatewayEvent event;
        try {
            event = json.readValue(payload, GatewayEvent.class);
        } catch (RuntimeException e) {
            log.warn("dropping unreadable event: {}", e.getMessage());
            return 0;
        }
        if (!"message.created".equals(event.eventType()) || event.participants() == null) return 0;

        String frame = json.writeValueAsString(new MessageFrame("message", event.conversationId(), event.message()));
        int written = 0;
        for (String userId : event.participants()) {
            for (ClientSocket socket : registry.socketsOf(userId)) {
                if (socket.sessionId().equals(event.originSessionId())) continue; // it already has the ACK
                try {
                    socket.send(frame);
                    written++;
                } catch (RuntimeException e) {
                    log.warn("send to session {} failed: {}", socket.sessionId(), e.getMessage());
                }
            }
        }
        return written;
    }
}
```

- [ ] **Step 5: Run to see pass.** Same command. Expected: 5 tests PASS. If the Jackson 3 import or `readValue`/`writeValueAsString` signatures differ, follow the compiler and tell the user.

- [ ] **Step 6: Pause.** Explain what the pod does with an event it cannot deliver (nothing; no socket here), why the origin socket is skipped (`originSessionId`, not `deviceId`), and why unknown fields are ignored. Wait. Do not commit.

---

### Task 7: The pod queue (spike, then the listener)

**Files:**
- Create: `broker/PodQueueProvisioner.java`, `broker/PodEventListener.java`
- Test: `src/test/java/com/glint/gateway/broker/PodQueueProvisionerTest.java`

**Interfaces:**
- Consumes: `EventRouter.route(String)` (Task 6).
- Produces: `PodQueueProvisioner(String brokerUrl, String user, String password, String podId).provision()` which creates `chat.events::<podId>` (multicast, non-durable, `max-consumers 1`, purge-on-no-consumers, auto-delete); `PodEventListener.onEvent(String)`.

This task settles the "to verify" item of the design: how to create a **named** non-durable queue from Java. Treat the first implementation as a hypothesis the tests prove or reject.

- [ ] **Step 1: Write the failing test** `broker/PodQueueProvisionerTest.java`

```java
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
```

- [ ] **Step 2: Run to see failure.** `mvn -f services/ws-gateway/pom.xml -q test -Dtest=PodQueueProvisionerTest` (with `JAVA_HOME`). Expected: compile failure.

- [ ] **Step 3: Implement** `broker/PodQueueProvisioner.java`

```java
package com.glint.gateway.broker;

import jakarta.annotation.PostConstruct;
import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.Session;
import org.apache.activemq.artemis.api.core.ActiveMQQueueExistsException;
import org.apache.activemq.artemis.api.core.QueueConfiguration;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.activemq.artemis.jms.client.ActiveMQSession;
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

    @PostConstruct
    public void provision() {
        ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(brokerUrl, user, password);
        try (Connection connection = factory.createConnection()) {
            ActiveMQSession session = (ActiveMQSession) connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            session.createQueue(QueueConfiguration.of(podId)
                    .setAddress(ADDRESS)
                    .setRoutingType(RoutingType.MULTICAST)
                    .setDurable(false)
                    .setMaxConsumers(1)
                    .setPurgeOnNoConsumers(true)
                    .setAutoDelete(true)
                    .setAutoDeleteDelay(5_000L));
            log.info("created queue {}::{}", ADDRESS, podId);
        } catch (JMSException e) {
            if (e.getCause() instanceof ActiveMQQueueExistsException) {
                log.info("queue {}::{} already exists, reusing it", ADDRESS, podId);
                return;
            }
            throw new IllegalStateException("cannot create queue " + ADDRESS + "::" + podId, e);
        }
    }
}
```
`broker/PodEventListener.java`:
```java
package com.glint.gateway.broker;

import com.glint.gateway.routing.EventRouter;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

/** One listener thread per pod queue, so events reach sockets in the order the broker holds them. */
@Component
public class PodEventListener {
    private final EventRouter router;

    public PodEventListener(EventRouter router) { this.router = router; }

    @JmsListener(destination = "chat.events::${gateway.pod-id}")
    public void onEvent(String payload) {
        router.route(payload);
    }
}
```

- [ ] **Step 4: Run to see pass.** Same command. Expected: 4 tests PASS. If `createQueue(QueueConfiguration)` is not found on `ActiveMQSession`, or the second consumer is not rejected, or "exists" is not signalled by `ActiveMQQueueExistsException`, record exactly what the compiler or the test shows, adjust (for example use a Core `ClientSession` from `factory.getServerLocator()`), and write the finding to the user and to `docs/chat-design.md` section 5 under "To verify".

- [ ] **Step 5: Pause.** Explain each queue setting (`setDurable(false)`, `setMaxConsumers(1)`, `setPurgeOnNoConsumers(true)`, `setAutoDelete(true)`) and the lab it comes from (Labs A to D, lessons 5, 10 to 12). State what the tests proved and what they did not: whether the broker deletes the queue after the pod stops is observed by hand in Task 9, not asserted. Wait. Do not commit.

---

### Task 8: The WebSocket endpoint and the send flow

> **Added 2026-10-15 (from the Task 7 review):** `PodEventListener` now has `@DependsOn("podQueueProvisioner")`. The first Spring-context test with a real Artemis in this task must also prove the ordering: start the context, assert that `chat.events::<podId>` exists, and that an event published to `chat.events` reaches `EventRouter`. Known gap, not solved in the slice: the queue is created once at startup and is NOT recreated if it disappears while the pod runs (see D18).

**Files:**
- Create: `chat/ChatServiceClient.java`, `chat/SendResponse.java`, `socket/WebSocketClientSocket.java`, `ws/ChatWebSocketHandler.java`, `ws/WebSocketConfig.java`
- Test: `src/test/java/com/glint/gateway/GatewayIntegrationTestBase.java`, `ws/WebSocketFlowTest.java`

**Interfaces:**
- Consumes: `SocketRegistry`, `ClientSocket`, `EventRouter`, `PodQueueProvisioner`, `PodEventListener`.
- Produces: `ws://localhost:8081/ws?userId=<id>`; frames: client `{type:"send", conversationId, clientMsgId, body}`; server `{type:"ready", sessionId}`, `{type:"ack", clientMsgId, messageId, seq, duplicate}`, `{type:"message", ...}` (from the router), `{type:"error", clientMsgId, code}`.

- [ ] **Step 1: Write the failing tests**

`GatewayIntegrationTestBase.java`:
```java
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
```
`ws/WebSocketFlowTest.java`:
```java
package com.glint.gateway.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.glint.gateway.GatewayIntegrationTestBase;
import com.glint.gateway.chat.ChatServiceClient;
import com.glint.gateway.chat.SendResponse;
import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class WebSocketFlowTest extends GatewayIntegrationTestBase {

    @Value("${local.server.port}") int port;
    @Autowired ObjectMapper json;
    @Autowired JmsTemplate jms;
    @MockitoBean ChatServiceClient chatService;

    static class TestClient extends TextWebSocketHandler {
        final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        WebSocketSession session;
        volatile CloseStatus closed;
        @Override public void afterConnectionEstablished(WebSocketSession s) { session = s; }
        @Override protected void handleTextMessage(WebSocketSession s, TextMessage m) { frames.add(m.getPayload()); }
        @Override public void afterConnectionClosed(WebSocketSession s, CloseStatus status) { closed = status; }
    }

    private TestClient connect(String userId) throws Exception {
        TestClient client = new TestClient();
        new StandardWebSocketClient().execute((WebSocketHandler) client, "ws://localhost:" + port + "/ws?userId=" + userId)
                .get(5, TimeUnit.SECONDS);
        return client;
    }

    private JsonNode next(TestClient c) throws Exception {
        String frame = c.frames.poll(5, TimeUnit.SECONDS);
        assertThat(frame).as("a frame within 5 s").isNotNull();
        return json.readTree(frame);
    }

    @Test
    void sendAcksTheOriginAndTheEventReachesTheOtherSocketsOnly() throws Exception {
        TestClient customer = connect("c1");
        TestClient customerTab2 = connect("c1");
        TestClient rm = connect("r1");
        String customerSession = next(customer).get("sessionId").asString();
        next(customerTab2); next(rm);

        when(chatService.send(eq("conv-1"), eq("c1"), eq(customerSession), eq("cm-1"), eq("hello")))
                .thenReturn(new SendResponse("m-1", 1, "cm-1", false));

        customer.session.sendMessage(new TextMessage(
                "{\"type\":\"send\",\"conversationId\":\"conv-1\",\"clientMsgId\":\"cm-1\",\"body\":\"hello\"}"));
        JsonNode ack = next(customer);
        assertThat(ack.get("type").asString()).isEqualTo("ack");
        assertThat(ack.get("messageId").asString()).isEqualTo("m-1");
        assertThat(ack.get("seq").asLong()).isEqualTo(1);

        // Simulate the relay: publish the envelope to the topic.
        JmsTemplate topic = new JmsTemplate(jms.getConnectionFactory());
        topic.setPubSubDomain(true);
        topic.convertAndSend("chat.events",
            "{\"schemaVersion\":1,\"eventId\":\"e1\",\"eventType\":\"message.created\",\"conversationId\":\"conv-1\","
          + "\"participants\":[\"c1\",\"r1\"],\"originSessionId\":\"" + customerSession + "\","
          + "\"message\":{\"messageId\":\"m-1\",\"seq\":1,\"senderId\":\"c1\",\"clientMsgId\":\"cm-1\","
          + "\"type\":\"text\",\"body\":\"hello\",\"createdAt\":\"t\"}}");

        assertThat(next(rm).get("type").asString()).isEqualTo("message");
        assertThat(next(customerTab2).get("message").get("body").asString()).isEqualTo("hello");
        assertThat(customer.frames.poll(1, TimeUnit.SECONDS)).as("the origin socket gets no duplicate").isNull();
    }

    @Test
    void aFailingSendBecomesAnErrorFrame() throws Exception {
        TestClient customer = connect("c2");
        next(customer);
        when(chatService.send(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("chat-service down"));
        customer.session.sendMessage(new TextMessage(
                "{\"type\":\"send\",\"conversationId\":\"conv-2\",\"clientMsgId\":\"cm-9\",\"body\":\"x\"}"));
        JsonNode error = next(customer);
        assertThat(error.get("type").asString()).isEqualTo("error");
        assertThat(error.get("clientMsgId").asString()).isEqualTo("cm-9");
    }

    @Test
    void aConnectionWithoutUserIdIsClosed() throws Exception {
        TestClient client = new TestClient();
        new StandardWebSocketClient().execute((WebSocketHandler) client, "ws://localhost:" + port + "/ws")
                .get(5, TimeUnit.SECONDS);
        long deadline = System.currentTimeMillis() + 5000;
        while (client.closed == null && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertThat(client.closed).isNotNull();
    }
}
```
(`URI` import is unused and may be removed; if `asString()` does not exist on Jackson 3's `JsonNode`, use the method the compiler suggests, for example `stringValue()` or `asText()`.)

- [ ] **Step 2: Run to see failure.** `mvn -f services/ws-gateway/pom.xml -q test -Dtest=WebSocketFlowTest`. Expected: compile failure.

- [ ] **Step 3: Implement**

`chat/SendResponse.java`:
```java
package com.glint.gateway.chat;

public record SendResponse(String messageId, long seq, String clientMsgId, boolean duplicate) {}
```
`chat/ChatServiceClient.java`:
```java
package com.glint.gateway.chat;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** The gateway never writes the database and never publishes: it asks chat-service to persist (D5). */
@Component
public class ChatServiceClient {
    private final RestClient http;

    public ChatServiceClient(@Value("${chat-service.base-url}") String baseUrl) {
        this.http = RestClient.create(baseUrl);
    }

    public SendResponse send(String conversationId, String userId, String originSessionId, String clientMsgId, String body) {
        return http.post().uri("/internal/conversations/{id}/messages", conversationId)
                .body(Map.of("userId", userId, "originSessionId", originSessionId,
                        "clientMsgId", clientMsgId, "type", "text", "body", body))
                .retrieve().body(SendResponse.class);
    }
}
```
`socket/WebSocketClientSocket.java`:
```java
package com.glint.gateway.socket;

import java.io.IOException;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

public class WebSocketClientSocket implements ClientSocket {
    private final WebSocketSession session; // a ConcurrentWebSocketSessionDecorator: safe for several sending threads
    private final String sessionId, userId;

    public WebSocketClientSocket(WebSocketSession session, String sessionId, String userId) {
        this.session = session; this.sessionId = sessionId; this.userId = userId;
    }

    @Override public String sessionId() { return sessionId; }
    @Override public String userId() { return userId; }

    @Override
    public void send(String json) {
        try {
            session.sendMessage(new TextMessage(json));
        } catch (IOException e) {
            throw new IllegalStateException("send failed for session " + sessionId, e);
        }
    }
}
```
`ws/ChatWebSocketHandler.java`:
```java
package com.glint.gateway.ws;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.glint.gateway.chat.ChatServiceClient;
import com.glint.gateway.chat.SendResponse;
import com.glint.gateway.socket.ClientSocket;
import com.glint.gateway.socket.SocketRegistry;
import com.glint.gateway.socket.WebSocketClientSocket;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private static final String SOCKET = "socket";

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Inbound(String type, String conversationId, String clientMsgId, String body) {}

    private final SocketRegistry registry;
    private final ChatServiceClient chatService;
    private final ObjectMapper json;

    public ChatWebSocketHandler(SocketRegistry registry, ChatServiceClient chatService, ObjectMapper json) {
        this.registry = registry; this.chatService = chatService; this.json = json;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        // DEVELOPMENT STUB: the user id comes from the query string. Real authentication replaces this.
        String userId = UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams().getFirst("userId");
        if (userId == null || userId.isBlank()) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("userId required"));
            return;
        }
        String sessionId = UUID.randomUUID().toString();
        ClientSocket socket = new WebSocketClientSocket(
                new ConcurrentWebSocketSessionDecorator(session, 5_000, 64 * 1024), sessionId, userId);
        session.getAttributes().put(SOCKET, socket);
        registry.register(socket);
        socket.send(json.writeValueAsString(Map.of("type", "ready", "sessionId", sessionId)));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        ClientSocket socket = (ClientSocket) session.getAttributes().get(SOCKET);
        if (socket == null) return;
        Inbound in;
        try {
            in = json.readValue(message.getPayload(), Inbound.class);
        } catch (RuntimeException e) {
            socket.send(error(null, "bad_frame"));
            return;
        }
        if (!"send".equals(in.type()) || in.conversationId() == null || in.clientMsgId() == null || in.body() == null) {
            socket.send(error(in.clientMsgId(), "bad_frame"));
            return;
        }
        try {
            SendResponse r = chatService.send(in.conversationId(), socket.userId(), socket.sessionId(),
                    in.clientMsgId(), in.body());
            socket.send(json.writeValueAsString(Map.of("type", "ack", "clientMsgId", in.clientMsgId(),
                    "messageId", r.messageId(), "seq", r.seq(), "duplicate", r.duplicate())));
        } catch (RuntimeException e) {
            log.warn("send failed for {}: {}", socket.userId(), e.getMessage());
            socket.send(error(in.clientMsgId(), "send_failed"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object socket = session.getAttributes().get(SOCKET);
        if (socket instanceof ClientSocket s) registry.unregister(s);
    }

    private String error(String clientMsgId, String code) {
        Map<String, Object> frame = new java.util.HashMap<>();
        frame.put("type", "error");
        frame.put("clientMsgId", clientMsgId);
        frame.put("code", code);
        return json.writeValueAsString(frame);
    }
}
```
`ws/WebSocketConfig.java`:
```java
package com.glint.gateway.ws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final ChatWebSocketHandler handler;

    public WebSocketConfig(ChatWebSocketHandler handler) { this.handler = handler; }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // DEVELOPMENT: any origin may connect. Restrict to the real origins with real authentication.
        registry.addHandler(handler, "/ws").setAllowedOrigins("*");
    }
}
```

- [ ] **Step 4: Run to see pass, then the whole gateway suite.**
`mvn -f services/ws-gateway/pom.xml -q test -Dtest=WebSocketFlowTest` then `mvn -f services/ws-gateway/pom.xml test`
Expected: all PASS. The test context creates the real pod queue `test-pod` in the Artemis container, so it exercises Task 7's provisioner and listener too. If `Mockito` or `@MockitoBean` imports differ in Boot 4, follow the compiler.

- [ ] **Step 5: Pause.** Walk through the three frames and the flow. Explain `ConcurrentWebSocketSessionDecorator` (two threads send to one socket: the listener thread and the handler thread) and the stub login. Ask: what would a real user do to reach another user's conversation with this stub, and where would the real check go? Wait. Do not commit.

---

### Task 9: Seed data, address setup, the web page, and the end-to-end check

**Files:**
- Create: `infra/artemis-setup.sh`, `infra/seed-dev.sql`, `infra/seed-dev.sh`, `services/ws-gateway/src/main/resources/static/index.html`

**Interfaces:**
- Produces: the fixed demo conversation `018f6b2a-0000-7000-8000-00000000c001` with participants `customer-1` and `rm-1`; the page at `http://localhost:8081/?user=<id>`.

- [ ] **Step 1: `infra/artemis-setup.sh`** (creates the multicast-only address, as decided in `docs/chat-design.md` section 5; the address settings `FAIL`/TTL are deferred)

```bash
#!/usr/bin/env bash
# Creates the multicast-only address chat.events on the lab broker. Safe to run twice.
set -euo pipefail
cd "$(dirname "$0")/.."
./labs/art.sh address create --name chat.events --multicast --no-anycast --silent \
  || echo "chat.events probably exists already (check: ./labs/art.sh address show --name chat.events)"
```

- [ ] **Step 2: `infra/seed-dev.sql`**

```sql
-- Demo data for the happy-path slice. Run after chat-service has started once (Flyway creates the tables).
SET @conv = UNHEX(REPLACE('018f6b2a-0000-7000-8000-00000000c001', '-', ''));

INSERT IGNORE INTO conversation (id, customer_id, last_seq, created_at)
VALUES (@conv, 'customer-1', 0, NOW(3));

INSERT IGNORE INTO conversation_participant (conversation_id, user_id, role, joined_at)
VALUES (@conv, 'customer-1', 'CUSTOMER', NOW(3)),
       (@conv, 'rm-1', 'RM', NOW(3));
```
`infra/seed-dev.sh`:
```bash
#!/usr/bin/env bash
# Loads the demo conversation into the lab MariaDB. Lab only: the password is on the command line.
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; source infra/.env; set +a
docker compose --env-file infra/.env -f infra/docker-compose.yml exec -T mariadb \
  mariadb -u"$MARIADB_USER" -p"$MARIADB_PASSWORD" "$MARIADB_DATABASE" < infra/seed-dev.sql
echo "seeded conversation 018f6b2a-0000-7000-8000-00000000c001 (customer-1, rm-1)"
```
Make both scripts executable: `chmod +x infra/artemis-setup.sh infra/seed-dev.sh`.

- [ ] **Step 3: The page** `services/ws-gateway/src/main/resources/static/index.html`

```html
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Glint chat (development page)</title>
<style>
  body { font-family: system-ui, sans-serif; max-width: 640px; margin: 24px auto; }
  #log { border: 1px solid #ccc; border-radius: 6px; height: 360px; overflow-y: auto; padding: 8px; }
  .msg { margin: 6px 0; padding: 6px 10px; border-radius: 10px; max-width: 75%; background: #eee; }
  .mine { margin-left: auto; background: #d6ecff; }
  .pending { opacity: .5; }
  .failed { background: #ffd6d6; }
  .meta { font-size: 11px; color: #666; }
  #form { display: flex; gap: 8px; margin-top: 8px; }
  #text { flex: 1; padding: 8px; }
</style>
</head>
<body>
<h3 id="title"></h3>
<div id="status">connecting...</div>
<div id="log"></div>
<form id="form"><input id="text" autocomplete="off" placeholder="Type a message"><button>Send</button></form>
<script>
  // DEVELOPMENT PAGE: the user comes from ?user=..., there is no login.
  const params = new URLSearchParams(location.search);
  const user = params.get('user') || 'customer-1';
  const conv = params.get('conv') || '018f6b2a-0000-7000-8000-00000000c001';
  const chatServiceUrl = 'http://localhost:8080';
  document.getElementById('title').textContent = 'Glint chat as ' + user;

  const messages = new Map();   // key: messageId, or clientMsgId while the message is pending
  const pendingByClientMsgId = new Map();
  let socket;

  function render() {
    const log = document.getElementById('log');
    log.replaceChildren();
    const sorted = [...messages.values()].sort((a, b) => (a.seq ?? Infinity) - (b.seq ?? Infinity));
    for (const m of sorted) {
      const div = document.createElement('div');
      div.className = 'msg' + (m.senderId === user ? ' mine' : '') + (m.state === 'pending' ? ' pending' : '')
                    + (m.state === 'failed' ? ' failed' : '');
      const meta = document.createElement('div');
      meta.className = 'meta';
      meta.textContent = m.senderId + (m.seq ? ' #' + m.seq : '') + (m.state === 'pending' ? ' sending...' : '')
                       + (m.state === 'failed' ? ' failed' : '');
      const body = document.createElement('div');
      body.textContent = m.body;          // textContent, never innerHTML: message text is untrusted
      div.append(meta, body);
      log.append(div);
    }
    log.scrollTop = log.scrollHeight;
  }

  function addStored(m) {                 // dedupe by messageId
    if (messages.has(m.messageId)) return;
    const pending = m.clientMsgId && pendingByClientMsgId.get(m.clientMsgId);
    if (pending) { messages.delete(pending); pendingByClientMsgId.delete(m.clientMsgId); }
    messages.set(m.messageId, { ...m, state: 'sent' });
  }

  async function loadHistory() {
    const res = await fetch(chatServiceUrl + '/conversations/' + conv + '/messages?afterSeq=0&limit=200',
                            { headers: { 'X-User-Id': user } });
    if (!res.ok) throw new Error('history failed: ' + res.status);
    (await res.json()).messages.forEach(addStored);
    render();
  }

  function connect() {
    socket = new WebSocket('ws://localhost:8081/ws?userId=' + encodeURIComponent(user));
    socket.onopen = () => { document.getElementById('status').textContent = 'connected'; };
    socket.onclose = () => { document.getElementById('status').textContent = 'disconnected (reload the page to reconnect)'; };
    socket.onmessage = (e) => {
      const f = JSON.parse(e.data);
      if (f.type === 'ack') {
        const key = pendingByClientMsgId.get(f.clientMsgId);
        const m = messages.get(key);
        if (m) { messages.delete(key); pendingByClientMsgId.delete(f.clientMsgId);
                 messages.set(f.messageId, { ...m, messageId: f.messageId, seq: f.seq, state: 'sent' }); }
      } else if (f.type === 'message') {
        addStored(f.message);
      } else if (f.type === 'error') {
        const key = pendingByClientMsgId.get(f.clientMsgId);
        if (key && messages.has(key)) messages.get(key).state = 'failed';
      }
      render();
    };
  }

  document.getElementById('form').addEventListener('submit', (e) => {
    e.preventDefault();
    const input = document.getElementById('text');
    const body = input.value.trim();
    if (!body || socket.readyState !== WebSocket.OPEN) return;
    const clientMsgId = crypto.randomUUID();
    messages.set(clientMsgId, { clientMsgId, senderId: user, body, state: 'pending' });
    pendingByClientMsgId.set(clientMsgId, clientMsgId);
    socket.send(JSON.stringify({ type: 'send', conversationId: conv, clientMsgId, body }));
    input.value = '';
    render();
  });

  loadHistory().then(connect).catch((err) => { document.getElementById('status').textContent = String(err); });
</script>
</body>
</html>
```

- [ ] **Step 4: Static check of the page.** Run `node --check` is not applicable to HTML; instead start the gateway in Step 6 and load the page. For a quick syntax check of the script, extract it: `sed -n '/<script>/,/<\/script>/p' services/ws-gateway/src/main/resources/static/index.html | sed '1d;$d' > "$TMPDIR/page.js" && node --check "$TMPDIR/page.js"` (only if `node` is installed; otherwise skip and say so).

- [ ] **Step 5: Create the address.** The user runs: `./infra/artemis-setup.sh`, then `./labs/art.sh address show --name chat.events --bindings`. Expected: the address exists and has no bindings yet.

- [ ] **Step 6: Start the services.** The user runs each in its own terminal from the repo root (explain every part of the command before they run it):

Terminal 1, chat-service:
```bash
set -a; source infra/.env; set +a
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -f services/chat-service/pom.xml spring-boot:run
```
Expected: Flyway creates the four tables in the lab MariaDB (the log shows `Migrating schema ... to version "1 - init"`), and the app listens on 8080.

Then seed (new terminal): `./infra/seed-dev.sh`.

Terminal 2, ws-gateway:
```bash
set -a; source infra/.env; set +a
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
POD_ID=pod-1 mvn -f services/ws-gateway/pom.xml spring-boot:run
```
Expected: log line `created queue chat.events::pod-1`, app on 8081. Then `./labs/art.sh queue stat --queueName pod-1` shows `CONSUMER COUNT` 1.

- [ ] **Step 7: The two-tab check (the user does this).** Open `http://localhost:8081/?user=customer-1` and `http://localhost:8081/?user=rm-1`. A third tab with `?user=customer-1` is a second device of the customer.
  1. Send "hello" from the customer tab: it appears (greyed "sending..." then confirmed with `#1`) in the sender's tab, and live in the RM tab and in the third tab.
  2. Reply from the RM tab. Reload any tab: the history shows both messages in order.
  3. Look at the data: `docker compose --env-file infra/.env -f infra/docker-compose.yml exec mariadb mariadb -u"$MARIADB_USER" -p"$MARIADB_PASSWORD" "$MARIADB_DATABASE" -e "SELECT seq, sender_id, body FROM message ORDER BY seq; SELECT id, event_type, published_at FROM outbox;"` (source `infra/.env` first). Expected: two messages, `seq` 1 and 2, outbox rows with `published_at` set.
  4. Look at the broker: `./labs/art.sh queue stat --queueName pod-1`. Predict first: what do `MESSAGES ADDED` and `MESSAGES ACKED` show after two messages?

- [ ] **Step 8: Break it once, on purpose.** (a) Stop chat-service, send from a tab: the bubble turns red ("failed"). Start it again. (b) Stop the gateway with Ctrl+C and watch `./labs/art.sh queue stat`: does `pod-1` disappear, and after how long? Record the answer: it settles the auto-delete "to verify" in Task 7. (c) Send the same message twice quickly by resending a frame in the browser console with the same `clientMsgId`: only one row appears in MariaDB.

- [ ] **Step 9: Pause.** Summarize what ran, what the user saw, and which "to verify" items are now settled or still open. Wait. Do not commit.

---

### Task 10: Record the slice

**Files:**
- Modify: `README.md` (how to run the slice), `docs/chat-design.md` (settled "to verify" items), `docs/design-decisions.md` (add D25: the slice's choices and what it proved), `CLAUDE.md` (current state), `docs/superpowers/plans/2026-10-09-master-roadmap.md` (add the slice row)

- [ ] **Step 1: README.** Add a section "Run the chat slice" with the commands from Task 9 steps 5 to 7 and the Java 21 note.

- [ ] **Step 2: Settle the verified items.** In `docs/chat-design.md` section 5 and in D11/D14, replace "to verify" with what the build and the Task 7 and Task 9 results showed: how the named non-durable queue is created, whether a second consumer is rejected, whether the queue disappears after the pod stops (and after how long), and whether `blockOnNonDurableSend=true` made the relay's send a confirmed one. Anything not shown stays "to verify".

- [ ] **Step 3: D25.** One entry: the slice scope, the technology bundle (Spring Boot 4.1.1, Maven, `JdbcTemplate`, Flyway, Spring JMS over Core), the development simplifications (stub login, CORS `*`, the page calls chat-service directly), and the list of deferred hardening items from the spec ("Out of the slice").

- [ ] **Step 4: State.** Update `CLAUDE.md` section 4 (slice built, how to run it, what is next) and the roadmap (add a row "Happy-path slice", status Done).

- [ ] **Step 5: Show the user `git status` and a one-paragraph summary. Wait for "commit"; do not commit before.**

---

### Deferred task: outbox cleanup job (planned 2026-10-15, NOT part of the slice, not built)

Decision (user): published outbox rows are kept for a **configurable duration**, then deleted by a scheduled **overnight** job. See D18 "Outbox cleanup".

**Rules**
- Delete only rows with `published_at IS NOT NULL AND published_at < now - retention`. Never touch unpublished rows.
- Delete in small batches by `id` (for example `DELETE FROM outbox WHERE published_at IS NOT NULL AND published_at < ? ORDER BY id LIMIT ?`), looping with a short pause, until nothing is left or a time budget is used. A night that was missed is then caught up by the next run.
- The existing `idx_unpublished (published_at, id)` should serve the `published_at < ?` scan; confirm with `EXPLAIN` when this is built.
- One runner at a time: the same leader lock as the relay (D8, ShedLock), so two instances do not both delete.

**Configuration (names proposed, values to be chosen by the user)**
- `chat.outbox.retention` (a duration; default to be decided, 7 days was only a suggestion), `chat.outbox.cleanup.cron` (overnight, with an explicit time zone), `chat.outbox.cleanup.batch-size`, `chat.outbox.cleanup.max-runtime`.

**Tests to write first (Testcontainers, a fixed `Clock`)**
1. An old published row is deleted; a recent published row is kept (boundary around the retention).
2. An unpublished row is never deleted, however old.
3. More rows than one batch are all deleted in one run; the run stops at the time budget.
4. Changing the retention changes the result (configuration is honoured).
5. A second concurrent run does nothing harmful (leader lock or idempotent deletes).

**Operations**
- Metrics and alerts: rows deleted per run, outbox row count, age of the oldest unpublished row (a stuck relay), and "cleanup has not run for more than N days".
- To verify: that overnight is really quiet (customers may use the app at any hour; check real traffic in Module 11), and the production database version (batch `DELETE ... ORDER BY id LIMIT` and `SKIP LOCKED`), which the user will confirm.

---

## Self-review (done when this plan was written)

- **Spec coverage:** send transaction and idempotency (Task 3), history with visibility (Task 5), relay and `chat.events` publishing (Task 4), pod queue (Task 7), socket delivery with origin skipped (Tasks 6 and 8), page (Task 9), seed (Task 9), Java 21 rule (Task 0), documentation (Task 10). Out-of-scope items are listed in the spec and not planned.
- **Placeholders:** none; every code step has code. Items that depend on library behavior are explicit "if the compiler says X" instructions plus a requirement to tell the user, because Spring Boot 4, Jackson 3 and Testcontainers 2 APIs were not available to check offline.
- **Type consistency:** `SendCommand`/`SendResult` (chat-service) match the JSON of `SendRequest`/`SendResponse`; gateway `SendResponse(messageId, seq, clientMsgId, duplicate)` matches the controller's `SendResponse`; `GatewayEvent` fields match `MessageCreatedEvent` from Task 3; `ClientSocket` methods are used identically in Tasks 6 and 8; the address name `chat.events` and the pod queue name `<podId>` are the same in Tasks 4, 7 and 9.
