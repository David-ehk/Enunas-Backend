-- =============================================================================
-- V4: Add color_family column to product_colors
--
-- Hybrid approach: color (free-text display name) is unchanged.
-- color_family is the enum bucket used for filtering.
--
-- This is the SINGLE source of the color_family column. The V0.0.1 baseline is
-- intentionally left untouched (editing an applied migration breaks its Flyway
-- checksum and the app fails to start).
--
-- Fresh databases: V0.0.1 creates product_colors WITHOUT color_family; this
--   migration then adds the column + index. Hibernate `validate` runs after all
--   migrations, so the final schema matches the entity.
-- Existing databases: same guard adds the column and back-fills to OTHER, then
--   drops the DEFAULT so Hibernate owns the not-null constraint from here on.
-- =============================================================================

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'product_colors'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name   = 'product_colors'
          AND column_name  = 'color_family'
    ) THEN
        ALTER TABLE product_colors
            ADD COLUMN color_family VARCHAR(50) NOT NULL DEFAULT 'OTHER';
        ALTER TABLE product_colors ALTER COLUMN color_family DROP DEFAULT;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_product_colors_family
    ON product_colors(color_family);
