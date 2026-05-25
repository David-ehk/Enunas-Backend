-- V2: Back-fill missing columns on existing databases
--
-- Both blocks are guarded: on a fresh database the tables do not exist at
-- Flyway time (Hibernate creates them afterwards), so we skip and let
-- Hibernate build the full schema from the current entity definitions.
-- On an existing database that is missing these columns we add them safely.

DO $$
BEGIN
    -- Add complete_the_look_enabled only when products exists but the column is missing.
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'products'
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name  = 'products'
          AND column_name = 'complete_the_look_enabled'
    ) THEN
        ALTER TABLE products
            ADD COLUMN complete_the_look_enabled BOOLEAN NOT NULL DEFAULT false;
    END IF;
END $$;

-- Create the CTL join table only when products already exists.
-- On fresh databases Hibernate creates it; on existing ones this is idempotent.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'products'
    ) THEN
        CREATE TABLE IF NOT EXISTS product_complete_the_look (
            product_id         BIGINT NOT NULL REFERENCES products(id),
            related_product_id BIGINT NOT NULL REFERENCES products(id),
            CONSTRAINT pk_product_complete_the_look PRIMARY KEY (product_id, related_product_id)
        );
    END IF;
END $$;
