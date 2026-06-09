-- =============================================================================
-- V8: §22f UStG recording fields — supplier legal name + postal address.
--
-- Covers Pflichtangabe 1 (Name + Anschrift des Lieferers) and, by assumption that
-- a brand ships from its business address, Pflichtangabe 4 (Versandursprung).
-- Additive only; all columns NULLABLE (mandatory enforced application-side via
-- @NotBlank in the DTO, never a DB constraint). vat_id / tax_number already exist (V6).
-- V0.0.1..V7 are left untouched (Flyway checksum).
-- =============================================================================

ALTER TABLE brand_partners ADD COLUMN IF NOT EXISTS legal_name          varchar(255);
ALTER TABLE brand_partners ADD COLUMN IF NOT EXISTS address_street      varchar(255);
ALTER TABLE brand_partners ADD COLUMN IF NOT EXISTS address_postal_code varchar(16);
ALTER TABLE brand_partners ADD COLUMN IF NOT EXISTS address_city        varchar(128);
ALTER TABLE brand_partners ADD COLUMN IF NOT EXISTS address_country     varchar(2);
