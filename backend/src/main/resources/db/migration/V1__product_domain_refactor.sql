-- =============================================================================
-- V1: Product domain refactor
--
-- Safe on existing databases: only runs the transformation when the old schema
-- (product_variants.sku column) is detected. Fresh databases are handled by
-- Hibernate (ddl-auto=update creates the new schema directly).
--
-- Covers:
--  1. products — add product_type, outfit_slot, complete_the_look_enabled
--  2. product_colors — new table: color-level SKU ownership
--  3. product_variants — add product_color_id FK, drop sku + color columns,
--                        add unique(product_color_id, size) constraint
--  4. product_catalogue_categories — new table (ElementCollection), migrate data
--  5. product_complete_the_look — new join table for self-referential ManyToMany
-- =============================================================================

DO $$
BEGIN

-- Guard: only migrate when the old variant schema (sku on product_variants) is present.
-- Fresh databases skip this block entirely; Hibernate builds the new schema from entities.
IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_name = 'product_variants' AND column_name = 'sku'
) THEN

    -- =========================================================================
    -- 1. products — new columns
    -- =========================================================================

    ALTER TABLE products ADD COLUMN IF NOT EXISTS product_type VARCHAR(50);
    UPDATE products SET product_type = 'T_SHIRT' WHERE product_type IS NULL;
    ALTER TABLE products ALTER COLUMN product_type SET NOT NULL;

    ALTER TABLE products ADD COLUMN IF NOT EXISTS outfit_slot VARCHAR(50);
    UPDATE products SET outfit_slot = 'TOP' WHERE outfit_slot IS NULL;
    ALTER TABLE products ALTER COLUMN outfit_slot SET NOT NULL;

    ALTER TABLE products ADD COLUMN IF NOT EXISTS complete_the_look_enabled BOOLEAN;
    UPDATE products SET complete_the_look_enabled = FALSE WHERE complete_the_look_enabled IS NULL;
    ALTER TABLE products ALTER COLUMN complete_the_look_enabled SET NOT NULL;
    ALTER TABLE products ALTER COLUMN complete_the_look_enabled SET DEFAULT FALSE;

    -- =========================================================================
    -- 2. product_colors — color-level SKU ownership table
    -- =========================================================================

    CREATE TABLE IF NOT EXISTS product_colors (
        id         BIGSERIAL    PRIMARY KEY,
        sku        VARCHAR(8)   NOT NULL,
        color      VARCHAR(255) NOT NULL,
        product_id BIGINT       NOT NULL REFERENCES products(id),
        CONSTRAINT uq_product_colors_sku           UNIQUE (sku),
        CONSTRAINT uq_product_colors_product_color UNIQUE (product_id, color)
    );

    -- One row per unique (product_id, color) derived from existing variants.
    -- SKU = first 8 uppercase hex chars of MD5(product_id_color).
    INSERT INTO product_colors (sku, color, product_id)
    SELECT
        UPPER(LEFT(MD5(t.product_id::text || '_' || t.color_val), 8)) AS sku,
        t.color_val                                                     AS color,
        t.product_id
    FROM (
        SELECT DISTINCT product_id, COALESCE(color, 'DEFAULT') AS color_val
        FROM product_variants
    ) t
    ON CONFLICT (product_id, color) DO NOTHING;

    -- =========================================================================
    -- 3. product_variants — add FK, migrate, drop old columns, add constraint
    -- =========================================================================

    ALTER TABLE product_variants ADD COLUMN IF NOT EXISTS product_color_id BIGINT;

    -- Map each variant to its new ProductColor row by matching product + color.
    UPDATE product_variants pv
    SET product_color_id = pc.id
    FROM product_colors pc
    WHERE pc.product_id = pv.product_id
      AND pc.color       = COALESCE(pv.color, 'DEFAULT');

    ALTER TABLE product_variants ALTER COLUMN product_color_id SET NOT NULL;

    ALTER TABLE product_variants
        ADD CONSTRAINT fk_pv_product_color
        FOREIGN KEY (product_color_id) REFERENCES product_colors(id);

    -- Unique: one variant per (color, size) combination.
    ALTER TABLE product_variants
        ADD CONSTRAINT uq_product_variants_color_size
        UNIQUE (product_color_id, size);

    -- Remove old per-variant SKU and color columns (ownership moved to product_colors).
    ALTER TABLE product_variants DROP COLUMN IF EXISTS sku;
    ALTER TABLE product_variants DROP COLUMN IF EXISTS color;

    -- =========================================================================
    -- 4. product_catalogue_categories — ElementCollection table + data migration
    -- =========================================================================

    CREATE TABLE IF NOT EXISTS product_catalogue_categories (
        product_id BIGINT      NOT NULL REFERENCES products(id),
        category   VARCHAR(50) NOT NULL
    );

    -- Migrate single-value catalogue_category from the products row to the new table.
    INSERT INTO product_catalogue_categories (product_id, category)
    SELECT id, catalogue_category
    FROM products
    WHERE catalogue_category IS NOT NULL;

    -- =========================================================================
    -- 5. product_complete_the_look — self-referential ManyToMany join table
    -- =========================================================================

    CREATE TABLE IF NOT EXISTS product_complete_the_look (
        product_id         BIGINT NOT NULL REFERENCES products(id),
        related_product_id BIGINT NOT NULL REFERENCES products(id),
        CONSTRAINT pk_product_complete_the_look PRIMARY KEY (product_id, related_product_id)
    );

END IF;

END $$;
