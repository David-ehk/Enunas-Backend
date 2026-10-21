-- V37: refund claim + tracking state on returns (spec 2026-10-07-partial-returns-exact-refunds-design).
ALTER TABLE returns
    ADD COLUMN refund_claim_key        varchar(100),
    ADD COLUMN refund_state            varchar(20),
    ADD COLUMN refund_items_amount     numeric(10,2),
    ADD COLUMN refund_shipping_amount  numeric(10,2),
    ADD COLUMN refund_claimed_at       timestamp,
    ADD COLUMN refund_released_by      varchar(255),
    ADD COLUMN refund_override_reason  varchar(500),
    ADD COLUMN refund_transaction_id   varchar(100),
    ADD COLUMN refund_resend_count     integer NOT NULL DEFAULT 0,
    ADD COLUMN received_by             varchar(10);

ALTER TABLE returns ADD CONSTRAINT uq_returns_refund_claim_key UNIQUE (refund_claim_key);

UPDATE returns SET refund_state = 'REFUNDED' WHERE status = 'REFUNDED';
