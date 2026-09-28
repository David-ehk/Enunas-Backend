-- Pre-shipment item cancellation (spec 2026-09-28, D1/D11/D15). A claim writes every column except
-- refund_transaction_id; finalize writes refund_transaction_id. All nullable; null on every
-- existing row. Widths and the check constraint match orders.cancellation_* (V0.0.1) and
-- orders.refund_transaction_id (V34).
ALTER TABLE order_items ADD COLUMN cancelled_at TIMESTAMP(6);
ALTER TABLE order_items ADD COLUMN cancellation_reason VARCHAR(255)
    CHECK (cancellation_reason IN ('FRAUD_SUSPICION','OUT_OF_STOCK','CUSTOMER_REQUEST','TECHNICAL_ERROR','OTHER'));
ALTER TABLE order_items ADD COLUMN cancellation_note VARCHAR(500);
ALTER TABLE order_items ADD COLUMN cancelled_by_admin_email VARCHAR(255);
ALTER TABLE order_items ADD COLUMN cancellation_includes_shipping BOOLEAN;
ALTER TABLE order_items ADD COLUMN cancellation_claim_key VARCHAR(255);
ALTER TABLE order_items ADD COLUMN refund_transaction_id VARCHAR(64);
