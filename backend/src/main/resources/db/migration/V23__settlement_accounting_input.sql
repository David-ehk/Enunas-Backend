-- =============================================================================
-- V23: Settlement Accounting Report — admin-entered per-period facts
--
-- Additive only. Holds the two facts the system has no authoritative source for
-- (Mollie fees, confirmed payout reference) so SettlementAccountingReportService
-- can merge them into the live-computed ledger figures without fabricating data.
-- One row per calendar period ('YYYY-MM'); everything else in the report is
-- derived fresh from ledger_entries/payouts on every request.
-- =============================================================================

CREATE TABLE IF NOT EXISTS settlement_accounting_inputs (
    id                                     BIGSERIAL PRIMARY KEY,
    period                                 VARCHAR(7)    NOT NULL,   -- 'YYYY-MM'
    mollie_fees                            NUMERIC(10,2),
    mollie_fees_included_in_actual_payout  BOOLEAN,
    payout_reference                       VARCHAR(100),
    mollie_settlement_date                 DATE,
    notes                                  VARCHAR(1000),
    entered_by_admin_email                 VARCHAR(255)  NOT NULL,
    entered_at                             TIMESTAMP     NOT NULL,
    updated_at                             TIMESTAMP     NOT NULL,
    CONSTRAINT uq_settlement_accounting_input_period UNIQUE (period)
);
