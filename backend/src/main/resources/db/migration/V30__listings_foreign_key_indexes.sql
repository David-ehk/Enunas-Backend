-- listings carries no index other than its primary key. The baseline schema declared both foreign
-- keys (product_id -> products, variant_id -> product_variants) but PostgreSQL, unlike MySQL, does
-- not index the *referencing* side of a foreign key on its own — declaring the constraint indexes
-- nothing. Two consequences, and the second is the one that bites first:
--
--   1. Every read of this table filters on one of these two columns. The storefront path alone runs
--      findLowestActivePriceByProductId and existsCurrentlyActiveListingByProductId (the PDP gate in
--      ProductService.assertBrowsable) per product, and ProductRepository joins listings on
--      product_id for catalogue queries. All of them are sequential scans today.
--
--   2. Deleting or updating a products / product_variants row makes PostgreSQL scan the whole of
--      listings to enforce the constraint. That cost lands on writes to a *different* table, which
--      is why it tends to show up later and in a confusing place.
--
-- Plain CREATE INDEX, not CONCURRENTLY: Flyway runs each migration in a transaction and
-- CONCURRENTLY cannot run inside one. The table is small enough for the brief write lock to be a
-- non-event; revisit only if this ever runs against a large live catalogue.
--
-- Not expressible as @Index on the entity: ddl-auto is validate, so Hibernate never emits DDL and
-- an @Index annotation would be documentation that creates no index at all.

CREATE INDEX IF NOT EXISTS idx_listings_product_id ON listings (product_id);
CREATE INDEX IF NOT EXISTS idx_listings_variant_id ON listings (variant_id);
