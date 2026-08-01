-- =============================================================================
-- V13: Brand-nominated returns destination, distinct from the §22f legal address.
--
-- The V8 address (legal_name + address_*) is TAX master data — Pflichtangabe 1,
-- doubling as the assumed Versandursprung. It is NOT where a brand wants parcels
-- sent back: a brand fulfilling through a 3PL or a separate warehouse had no way
-- to say so, and returned goods went to its registered office.
--
-- Additive only; all columns NULLABLE. A brand that nominates nothing falls back
-- to its §22f address (single fallback rule in BrandReturnAddress), so existing
-- brands are unaffected. NEVER feeds the `domestic` flag — this is logistics data,
-- not tax data; `domestic` stays derived from address_country alone (see V9).
-- V0.0.1..V12 are left untouched (Flyway checksum).
-- =============================================================================

ALTER TABLE brand_partners ADD COLUMN IF NOT EXISTS return_recipient    varchar(255);
ALTER TABLE brand_partners ADD COLUMN IF NOT EXISTS return_street       varchar(255);
ALTER TABLE brand_partners ADD COLUMN IF NOT EXISTS return_postal_code  varchar(16);
ALTER TABLE brand_partners ADD COLUMN IF NOT EXISTS return_city         varchar(128);
ALTER TABLE brand_partners ADD COLUMN IF NOT EXISTS return_country      varchar(2);
ALTER TABLE brand_partners ADD COLUMN IF NOT EXISTS return_instructions text;
