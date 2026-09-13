-- =============================================================================
-- V33: order line items gain a purchase-time thumbnail.
--
-- Nullable, no backfill: pre-V33 rows and any line whose product had no image at
-- purchase stay NULL, and the frontend placeholders them. Same snapshot discipline
-- as product_snapshot_name / listing_id_snapshot on this table (see V28).
--
-- V0.0.1..V32 are left untouched (editing an applied migration breaks its Flyway
-- checksum and the app refuses to start).
-- =============================================================================

ALTER TABLE order_items
    ADD COLUMN IF NOT EXISTS variant_snapshot_image_key varchar(512);
