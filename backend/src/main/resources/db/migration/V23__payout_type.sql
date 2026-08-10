-- =============================================================================
-- V23: Split payouts by revenue source (product vs shipping)
--
-- generatePayouts() now creates up to two Payout rows per brand per cycle instead
-- of one, so the brand receives two separate bank transfers it can reconcile on its
-- own books: REVENUE (product/commission-side net) and SHIPPING (shipping money
-- collected on the brand's behalf, per the shipping domain added in V22). Existing
-- rows all predate the shipping domain, so they backfill as REVENUE.
-- =============================================================================

ALTER TABLE payouts ADD COLUMN IF NOT EXISTS type VARCHAR(20);
UPDATE payouts SET type = 'REVENUE' WHERE type IS NULL;
ALTER TABLE payouts ALTER COLUMN type SET NOT NULL;
ALTER TABLE payouts ALTER COLUMN type SET DEFAULT 'REVENUE';

ALTER TABLE payouts ADD CONSTRAINT payouts_type_check
    CHECK (type IN ('REVENUE','SHIPPING'));
