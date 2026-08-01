-- =============================================================================
-- V12: Brand contact person — first + last name captured at onboarding.
--
-- Additive only; both columns NULLABLE (brands onboarded before this migration
-- have no contact person, and mandatory is enforced application-side via
-- @NotBlank in RegisterBrandPartnerDto, never as a DB constraint).
-- V0.0.1..V11 are left untouched (Flyway checksum).
-- =============================================================================

ALTER TABLE brand_partners ADD COLUMN IF NOT EXISTS first_name varchar(100);
ALTER TABLE brand_partners ADD COLUMN IF NOT EXISTS last_name  varchar(100);
