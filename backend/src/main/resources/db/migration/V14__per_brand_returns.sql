-- =============================================================================
-- V14: Returns become per-(order, brand) and carry a frozen ship-to snapshot.
--
-- WHY: a `returns` row was keyed to an order, but every downstream fact about a
-- return is per-brand — where the parcel goes, whose stock is restored, whose
-- ledger is reversed. With nowhere to put a second brand, the application picked
-- the FIRST order item's brand and told the customer to ship a whole multi-brand
-- order there. This migration gives each brand its own return row.
--
-- The ship_to_* columns are a SNAPSHOT, not a lookup. Same reasoning as the money
-- model (order_items' frozen economics, reversed by LedgerService against the
-- immutable snapshot): a return already in flight must keep the address the
-- customer was actually told, even if the brand moves warehouses the next day.
--
-- Additive only; all columns NULLABLE. Note `returns.order_id` never carried a
-- UNIQUE constraint (V0.0.1 declares it `bigint not null` only) — the one-return-
-- per-order rule was application-side, so nothing needs dropping here.
-- V0.0.1..V13 are left untouched (Flyway checksum).
-- =============================================================================

ALTER TABLE returns ADD COLUMN IF NOT EXISTS brand_partner_id    bigint;
ALTER TABLE returns ADD COLUMN IF NOT EXISTS ship_to_name        varchar(255);
ALTER TABLE returns ADD COLUMN IF NOT EXISTS ship_to_street      varchar(255);
ALTER TABLE returns ADD COLUMN IF NOT EXISTS ship_to_postal_code varchar(16);
ALTER TABLE returns ADD COLUMN IF NOT EXISTS ship_to_city        varchar(128);
ALTER TABLE returns ADD COLUMN IF NOT EXISTS ship_to_country     varchar(2);
ALTER TABLE returns ADD COLUMN IF NOT EXISTS ship_to_instructions text;
ALTER TABLE returns ADD COLUMN IF NOT EXISTS ship_to_snapshot_at timestamp(6);

-- Backfill the brand from the FIRST return item — deliberately reproducing the old
-- buildReturnAddress() behaviour, so historical rows keep exactly the address the
-- customer was given. New returns are split per brand at request time instead.
UPDATE returns r
SET brand_partner_id = sub.brand_id
FROM (
    SELECT ri.return_order_id AS return_order_id,
           p.brand_id         AS brand_id,
           ROW_NUMBER() OVER (PARTITION BY ri.return_order_id
                              ORDER BY ri.order_item_id) AS rn
    FROM return_items ri
    JOIN order_items      oi ON oi.id = ri.order_item_id
    JOIN product_variants pv ON pv.id = oi.variant_id
    JOIN products         p  ON p.id  = pv.product_id
) sub
WHERE sub.return_order_id = r.id
  AND sub.rn = 1
  AND r.brand_partner_id IS NULL;

-- Backfill the snapshot from that brand's §22f address. V13's return_* columns are
-- still NULL for every brand at this point, so the fallback rule resolves to the
-- §22f address — i.e. exactly what these returns already displayed.
UPDATE returns r
SET ship_to_name        = bp.legal_name,
    ship_to_street      = bp.address_street,
    ship_to_postal_code = bp.address_postal_code,
    ship_to_city        = bp.address_city,
    ship_to_country     = bp.address_country,
    ship_to_snapshot_at = COALESCE(r.requested_at, now())
FROM brand_partners bp
WHERE bp.id = r.brand_partner_id
  AND r.ship_to_street IS NULL;

DO $$ BEGIN
    ALTER TABLE returns ADD CONSTRAINT fk_returns_brand_partner
        FOREIGN KEY (brand_partner_id) REFERENCES brand_partners;
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

create index if not exists idx_returns_order_brand on returns (order_id, brand_partner_id);
