-- =============================================================================
-- V32: colourway-specific product images.
--
-- product_images belonged to a product only, so a product's WHITE and BLACK
-- colourways could not show different photos. Add an optional link to
-- product_colors: NULL = shared (shown for every colourway), set = specific to
-- that colourway. No backfill — existing rows stay NULL, i.e. shared, which is
-- exactly today's behaviour.
--
-- Idempotent and safe on a database that already has the schema (a prior
-- ddl-auto run, or a re-run): ADD COLUMN uses IF NOT EXISTS, the FK is wrapped
-- in a DO block that swallows duplicate_object, and every index is IF NOT
-- EXISTS — matching V0.0.1 / V3.
--
-- V0.0.1..V31 are left untouched (editing an applied migration breaks its
-- Flyway checksum and the app refuses to start).
-- =============================================================================

ALTER TABLE product_images
    ADD COLUMN IF NOT EXISTS product_color_id BIGINT;

-- ON DELETE SET NULL, not CASCADE: removing a colourway demotes its photos to
-- shared (a recoverable state) rather than destroying real photography.
DO $$ BEGIN
    ALTER TABLE product_images
        ADD CONSTRAINT fk_product_images_product_color
        FOREIGN KEY (product_color_id) REFERENCES product_colors (id) ON DELETE SET NULL;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

CREATE INDEX IF NOT EXISTS idx_product_images_colour
    ON product_images (product_color_id);

-- "One primary per colour-group" as two partial unique indexes — the house idiom
-- (cf. V6 uq_ledger_order_payment, V15 uq_returns_active_per_brand). Two indexes,
-- not one over COALESCE(product_color_id, 0): a sentinel would silently couple
-- correctness to "no product_colors.id is ever 0".

-- (a) at most one primary among a colourway's own images
CREATE UNIQUE INDEX IF NOT EXISTS uq_product_images_primary_per_colour
    ON product_images (product_id, product_color_id)
    WHERE is_primary AND product_color_id IS NOT NULL;

-- (b) at most one primary among a product's shared (untagged) images
CREATE UNIQUE INDEX IF NOT EXISTS uq_product_images_primary_shared
    ON product_images (product_id)
    WHERE is_primary AND product_color_id IS NULL;
