-- =============================================================================
-- V18: restructure the orders table's embedded shipping address to the German
-- checkout field shape: firstName/lastName replace fullName, a new house_number
-- column is added (German addresses separate street from house number), and
-- `state` is retired (Germany doesn't use states for shipping).
--
-- full_name and state are NOT dropped -- left in place, unused by new code,
-- rather than risk a lossy destructive migration on free-text data. A later
-- cleanup migration can drop them once confirmed safe.
--
-- Best-effort backfill: existing rows' full_name is split on the first space
-- into first_name/last_name. house_number is left NULL for historical rows --
-- it cannot be safely auto-extracted from the free-text street column.
--
-- Column widths match the DTO's @Size caps exactly (varchar(100) for
-- first_name/last_name, varchar(16) for house_number) so an over-limit value
-- can never pass bean validation and then fail at INSERT.
--
-- Additive only where it matters; full_name/state stay in place, unused.
-- V0.0.1..V17 are left untouched (Flyway checksum).
-- =============================================================================

ALTER TABLE orders ADD COLUMN IF NOT EXISTS first_name varchar(100);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS last_name varchar(100);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS house_number varchar(16);

UPDATE orders
SET first_name = split_part(full_name, ' ', 1),
    last_name = trim(substring(full_name from position(' ' in full_name) + 1))
WHERE full_name IS NOT NULL AND position(' ' in full_name) > 0 AND first_name IS NULL;

UPDATE orders
SET first_name = full_name
WHERE full_name IS NOT NULL AND position(' ' in full_name) = 0 AND first_name IS NULL;
