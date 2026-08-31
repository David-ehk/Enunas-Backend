-- Follow-on to V30, same reasoning applied to the remaining hot foreign keys: PostgreSQL indexes
-- the *referenced* side of a foreign key (the primary key it points at) and nothing on the
-- referencing side, so every one of these columns is a sequential scan today, and every write to
-- the parent table scans the child to enforce the constraint.
--
-- Each index below is here because a query in the codebase filters on that column. Indexes are not
-- free — they cost on every insert and update of the table — so "it is a foreign key" alone was not
-- taken as sufficient reason.
--
--   products.creator_id      ProductRepository.findByCreator backs the brand dashboard's
--                            "my products" list (GET /products/my), which reads nothing else.
--   products.brand_id        ProductRepository.search joins Product -> brand to match
--                            brandName, and the storefront search box is public.
--   product_variants.product_id
--                            Product.variants is a @OneToMany loaded by product_id. Every product
--                            detail page and every order line that touches a variant walks it.
--                            product_color_id is already covered by uq_product_variants_color_size.
--   order_items.order_id     Order.items is a @OneToMany loaded by order_id — the single most
--                            travelled association in the codebase. Every order DTO, the customer
--                            order list, the admin order list, the shipment rollup and the §22f
--                            export all load it.
--   order_items.variant_id   ProductService.deleteProduct asks "has any variant of this product
--                            ever been ordered" through it, and deleting a variant makes
--                            PostgreSQL scan this table to check the constraint.
--
-- Deliberately NOT added, having checked what already exists and what the queries actually do:
--
--   products.slug            V11 already creates idx_products_slug, on top of the uq_products_slug
--                            UNIQUE constraint, which is itself an index. Adding a third would be
--                            pure write cost. (findBySlug and existsBySlug are already covered.)
--   product_colors.color_family
--                            V4 already creates idx_product_colors_family.
--   product_colors.product_id
--                            Covered by uq_product_colors_product_color (product_id, color) — a
--                            composite index serves lookups on its leading column.
--   products.status          Low selectivity: nearly every row is ACTIVE, so the planner would
--                            choose a sequential scan over it anyway. The gated browse queries are
--                            selective through the EXISTS on listings, which V30 indexed.
--   products (status, created_at)
--                            No query orders by created_at; the browse endpoints take whatever sort
--                            the Pageable carries, which today is unsorted. Speculative until a
--                            default sort actually exists.
--
-- Plain CREATE INDEX, not CONCURRENTLY, for the reason V30 gives: Flyway runs each migration inside
-- a transaction and CREATE INDEX CONCURRENTLY cannot run inside one — it fails outright, which on
-- startup means the application does not come up at all.

CREATE INDEX IF NOT EXISTS idx_products_creator_id ON products (creator_id);
CREATE INDEX IF NOT EXISTS idx_products_brand_id ON products (brand_id);
CREATE INDEX IF NOT EXISTS idx_product_variants_product_id ON product_variants (product_id);
CREATE INDEX IF NOT EXISTS idx_order_items_order_id ON order_items (order_id);
CREATE INDEX IF NOT EXISTS idx_order_items_variant_id ON order_items (variant_id);
