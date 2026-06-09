-- =============================================================================
-- V7: Monthly settlement marker
--
-- Additive only. A settlement_run freezes the aggregated commission/payout figures
-- for one brand + calendar month at the moment the operator settles, and its UNIQUE
-- (brand_id, period) prevents a brand/month being settled (and paid out) twice.
--
-- NOTE: the brief named this V6, but V6 (pre_prod_hardening) already exists — using
-- V7 to avoid a duplicate Flyway version. V0.0.1..V6 are left untouched (checksum).
-- =============================================================================

CREATE TABLE IF NOT EXISTS settlement_runs (
    id                BIGSERIAL PRIMARY KEY,
    brand_id          BIGINT       NOT NULL,
    period            VARCHAR(7)   NOT NULL,            -- 'YYYY-MM'
    settled_at        TIMESTAMP    NOT NULL,
    invoice_reference VARCHAR(100),                     -- optional, operator-supplied
    -- Frozen amounts at settlement time (EUR):
    commission_net    NUMERIC(10,2) NOT NULL,
    commission_vat    NUMERIC(10,2) NOT NULL,
    payout_amount     NUMERIC(10,2) NOT NULL,
    created_at        TIMESTAMP    DEFAULT NOW(),
    CONSTRAINT uq_settlement_brand_period UNIQUE (brand_id, period)
);
