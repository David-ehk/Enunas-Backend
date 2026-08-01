-- =============================================================================
-- V16: Replace the free-text `return_label` column (always the literal 'PENDING',
-- never read back anywhere) with a real label state.
--
-- Pre-launch, no legacy data (per the same reasoning as V15) — the old column is
-- dropped outright rather than kept alongside the new one.
--
-- label_status is the state machine (see ReturnLabelStatus): PENDING is the default
-- from return-approval onward; UPLOADED_BY_BRAND is the current MVP path (brand
-- supplies a label/tracking number it obtained itself); GENERATED/FAILED are
-- reserved for a future carrier-API integration that does not exist yet.
-- V0.0.1..V15 are left untouched (Flyway checksum).
-- =============================================================================

ALTER TABLE returns DROP COLUMN IF EXISTS return_label;

ALTER TABLE returns ADD COLUMN IF NOT EXISTS label_status varchar(32) NOT NULL DEFAULT 'PENDING';
ALTER TABLE returns ADD COLUMN IF NOT EXISTS label_carrier varchar(64);
ALTER TABLE returns ADD COLUMN IF NOT EXISTS label_tracking_number varchar(128);
ALTER TABLE returns ADD COLUMN IF NOT EXISTS label_url varchar(500);
ALTER TABLE returns ADD COLUMN IF NOT EXISTS label_failure_reason varchar(500);
ALTER TABLE returns ADD COLUMN IF NOT EXISTS label_updated_at timestamp(6);

DO $$ BEGIN
    ALTER TABLE returns ADD CONSTRAINT chk_returns_label_status
        CHECK (label_status IN ('PENDING', 'UPLOADED_BY_BRAND', 'GENERATED', 'FAILED'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
