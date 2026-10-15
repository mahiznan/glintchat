-- Demo data for the happy-path slice. Run after chat-service has started once (Flyway creates the tables).
SET @conv = UNHEX(REPLACE('018f6b2a-0000-7000-8000-00000000c002', '-', ''));

INSERT IGNORE INTO conversation (id, customer_id, last_seq, created_at)
VALUES (@conv, 'customer-2', 0, NOW(3));

INSERT IGNORE INTO conversation_participant (conversation_id, user_id, role, joined_at)
VALUES (@conv, 'customer-2', 'CUSTOMER', NOW(3)),
       (@conv, 'rm-2', 'RM', NOW(3));
