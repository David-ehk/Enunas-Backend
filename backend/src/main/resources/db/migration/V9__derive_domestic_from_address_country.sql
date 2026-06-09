-- =============================================================================
-- V9: Make `domestic` derived from address_country (reverse-charge input fix).
--
-- `domestic` was an independent boolean (default true) and the new §22f onboarding
-- never set it, so a brand's reverse-charge treatment could disagree with its
-- actual country. Going forward `domestic` is derived in applyMasterData
-- (DE ⇒ true). This one-off corrects existing rows that have an address_country.
-- Rows without an address_country are left as-is (no country signal to derive from).
-- Additive; V0.0.1..V8 untouched (Flyway checksum). The app was never live, so this
-- only touches test/seed brands — but corrected cleanly regardless.
-- =============================================================================

UPDATE brand_partners
   SET domestic = (upper(address_country) = 'DE')
 WHERE address_country IS NOT NULL;
