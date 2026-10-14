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
