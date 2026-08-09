-- =============================================================================
-- V22: Shipping cost domain
--
-- Activates the previously-dormant brand_shipping_profiles table (created by the
-- old Hibernate ddl-auto export, present in V0.0.1, never read or written by any
-- code) and adds the immutable per-order-per-brand shipping snapshot. Additive only.
-- =============================================================================

-- 1) order_shipping_snapshots: one immutable row per (order, brand). Financial record —
--    order_id/brand_partner_id use ON DELETE RESTRICT so neither can disappear while a
--    money snapshot referencing it exists. brand_shipping_profile_id is debugging
--    traceability only (which profile produced this amount), not a financial dependency,
--    so it uses ON DELETE SET NULL.
CREATE TABLE IF NOT EXISTS order_shipping_snapshots (
    id                        BIGSERIAL PRIMARY KEY,
    order_id                  BIGINT NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
    brand_partner_id          BIGINT NOT NULL REFERENCES brand_partners(id) ON DELETE RESTRICT,
    amount                    NUMERIC(10,2) NOT NULL,
    currency                  VARCHAR(3) NOT NULL DEFAULT 'EUR',
    calculation_method        VARCHAR(30) NOT NULL,
    rule_version              VARCHAR(20) NOT NULL,
    brand_shipping_profile_id BIGINT REFERENCES brand_shipping_profiles(id) ON DELETE SET NULL,
    created_at                TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_shipping_snapshot_order_brand UNIQUE (order_id, brand_partner_id)
);
CREATE INDEX IF NOT EXISTS idx_shipping_snapshot_order ON order_shipping_snapshots(order_id);
CREATE INDEX IF NOT EXISTS idx_shipping_snapshot_brand ON order_shipping_snapshots(brand_partner_id);

-- 2) brand_shipping_profiles: add currency to the existing (dormant, currently unwritten)
--    shipping_cost money column. Explicit three-step migration rather than relying on
--    DEFAULT alone, since this table predates this change.
ALTER TABLE brand_shipping_profiles ADD COLUMN IF NOT EXISTS currency VARCHAR(3);
UPDATE brand_shipping_profiles SET currency = 'EUR' WHERE currency IS NULL;
ALTER TABLE brand_shipping_profiles ALTER COLUMN currency SET NOT NULL;
ALTER TABLE brand_shipping_profiles ALTER COLUMN currency SET DEFAULT 'EUR';

-- 3) ledger_entries.entry_type gains SHIPPING_REVENUE as a value (Java enum
--    com.enunas.backend.ledger.LedgerEntryType) — stored in the existing varchar
--    column, no DDL change needed.

-- 4) The baseline schema's inline CHECK constraint on ledger_entries.entry_type predates
--    SHIPPING_REVENUE and would reject any row using it. Find and replace it by content
--    rather than assuming its auto-generated name.
DO $$
DECLARE
    con_name text;
BEGIN
    SELECT con.conname INTO con_name
    FROM pg_constraint con
    JOIN pg_class rel ON rel.oid = con.conrelid
    WHERE rel.relname = 'ledger_entries'
      AND con.contype = 'c'
      AND pg_get_constraintdef(con.oid) LIKE '%entry_type%';
    IF con_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE ledger_entries DROP CONSTRAINT %I', con_name);
    END IF;
END $$;

ALTER TABLE ledger_entries ADD CONSTRAINT ledger_entries_entry_type_check
    CHECK (entry_type IN ('ORDER_PAYMENT','PAYOUT_TRANSFER','REFUND_REVERSAL','SHIPPING_REVENUE'));
