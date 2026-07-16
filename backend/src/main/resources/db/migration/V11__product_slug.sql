-- Adds a stable URL slug to products. The storefront resolves product detail pages by this
-- slug (GET /products/slug/{slug}), and builds links from the slug the backend returns, so it
-- must be unique and stable. The base slug mirrors the frontend generateSlug() in
-- lib/product.ts (lowercase, umlaut expansion, non-alphanumerics -> hyphen); collisions get a
-- numeric suffix so two products with the same name still resolve unambiguously.

ALTER TABLE products ADD COLUMN slug VARCHAR(255);

-- Backfill base slugs from name.
UPDATE products
SET slug = regexp_replace(
             regexp_replace(
               regexp_replace(
                 replace(replace(replace(replace(lower(name),
                   'ä','ae'),'ö','oe'),'ü','ue'),'ß','ss'),
                 '[^a-z0-9_\s-]', '', 'g'),   -- strip non-alphanumerics (keep underscore/space/hyphen)
               '\s+', '-', 'g'),              -- whitespace runs -> single hyphen
             '-+', '-', 'g');                 -- collapse repeated hyphens

-- Fall back to a stable id-based slug for names that reduce to nothing (e.g. only symbols).
UPDATE products SET slug = 'produkt-' || id WHERE slug IS NULL OR slug = '' OR slug = '-';

-- De-duplicate: keep the lowest id on the base slug, suffix the rest with -2, -3, ...
WITH ranked AS (
  SELECT id, slug, ROW_NUMBER() OVER (PARTITION BY slug ORDER BY id) AS rn
  FROM products
)
UPDATE products p
SET slug = p.slug || '-' || r.rn
FROM ranked r
WHERE p.id = r.id AND r.rn > 1;

ALTER TABLE products ALTER COLUMN slug SET NOT NULL;
ALTER TABLE products ADD CONSTRAINT uq_products_slug UNIQUE (slug);
CREATE INDEX idx_products_slug ON products (slug);
