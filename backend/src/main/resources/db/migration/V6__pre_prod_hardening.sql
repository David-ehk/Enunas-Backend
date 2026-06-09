-- =============================================================================
-- V6: Pre-production hardening (additive / non-destructive only)
--
-- New versioned script — V0.0.1..V5 are left untouched (editing an applied
-- migration breaks its Flyway checksum and the app refuses to start).
-- =============================================================================

-- Task 1: the snapshot booleans must be NULLABLE so pre-V5 rows (NULL) load into
-- Boolean wrappers without error. V5 created them NOT NULL DEFAULT false; relax that.
-- (DROP NOT NULL / DROP DEFAULT are no-ops if already relaxed — safe to re-run.)
ALTER TABLE order_items ALTER COLUMN brand_is_domestic DROP NOT NULL;
ALTER TABLE order_items ALTER COLUMN brand_is_domestic DROP DEFAULT;
ALTER TABLE order_items ALTER COLUMN reverse_charge     DROP NOT NULL;
ALTER TABLE order_items ALTER COLUMN reverse_charge     DROP DEFAULT;

-- Task 2: backstop against concurrent duplicate ORDER_PAYMENT bookings. Scoped to
-- ORDER_PAYMENT only — REFUND_REVERSAL legitimately creates several rows per item
-- (multiple partial refunds), so a blanket unique would wrongly block them.
-- OPS: if prod already holds duplicate ORDER_PAYMENT rows from past double-webhooks,
-- this index will fail to build — clean the duplicates first.
CREATE UNIQUE INDEX IF NOT EXISTS uq_ledger_order_payment
    ON ledger_entries (order_id, order_item_id)
    WHERE entry_type = 'ORDER_PAYMENT';

-- Task 5: capture the brand's VAT identifiers (storage only; feeds the future
-- commission Gutschrift and the §22f/§25e marketplace recording duty).
ALTER TABLE brand_partners ADD COLUMN IF NOT EXISTS vat_id     varchar(32);
ALTER TABLE brand_partners ADD COLUMN IF NOT EXISTS tax_number varchar(32);
