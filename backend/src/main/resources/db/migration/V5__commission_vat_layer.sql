-- =============================================================================
-- V5: Net-based commission + VAT layer
--
-- Net becomes the single source of truth for commission; VAT is a pass-through
-- split out only on the platform commission (reverse charge ⇒ 0 for foreign brands).
-- All columns are ADDITIVE and nullable/defaulted so existing rows are unaffected.
--
-- Flyway hygiene: this is a NEW versioned script. The V0.0.1 baseline and every
-- other applied migration are left untouched — editing an applied script changes
-- its checksum and the app refuses to start.
-- =============================================================================

-- 3) Listings: store the net counterpart + the chosen input mode (price/discountPrice stay GROSS).
ALTER TABLE listings ADD COLUMN IF NOT EXISTS price_net          numeric(10,2);
ALTER TABLE listings ADD COLUMN IF NOT EXISTS discount_price_net numeric(10,2);
ALTER TABLE listings ADD COLUMN IF NOT EXISTS price_input_mode   varchar(10);

-- 2) Brand: explicit domestic flag drives VAT treatment of the commission.
ALTER TABLE brand_partners ADD COLUMN IF NOT EXISTS domestic boolean NOT NULL DEFAULT true;

-- MUST-FIX (money/compliance): `default true` would silently mark existing FOREIGN brands as
-- domestic and bill them German VAT they cannot reclaim. Flip clearly non-German brands here.
-- NOTE: brands with a NULL country stay domestic=true and MUST be reviewed before they sell;
-- confirm the exact foreign-brand set with the user / tax advisor before running in production.
UPDATE brand_partners
   SET domestic = false
 WHERE country IS NOT NULL
   AND upper(country) NOT IN ('DE', 'DEU', 'GERMANY', 'DEUTSCHLAND');

-- 4) Order items: the frozen net/VAT money snapshot (§4).
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS vat_rate_product              numeric(5,4);
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS vat_rate_service              numeric(5,4);
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS line_net                      numeric(10,2);
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS line_vat                      numeric(10,2);
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS line_gross                    numeric(10,2);
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS base_commission_net          numeric(10,2);
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS commission_net               numeric(10,2);
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS commission_vat               numeric(10,2);
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS commission_gross             numeric(10,2);
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS customer_gross_after_discount numeric(10,2);
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS brand_payout                 numeric(10,2);
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS brand_net_revenue            numeric(10,2);
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS brand_is_domestic            boolean NOT NULL DEFAULT false;
ALTER TABLE order_items ADD COLUMN IF NOT EXISTS reverse_charge               boolean NOT NULL DEFAULT false;

-- 7) Ledger entries: persist the net/VAT split for reporting (commission_net is the canonical
--    platform-revenue figure going forward; pre-V5 platform_fee rows remain gross-basis).
ALTER TABLE ledger_entries ADD COLUMN IF NOT EXISTS commission_net    numeric(10,2);
ALTER TABLE ledger_entries ADD COLUMN IF NOT EXISTS commission_vat    numeric(10,2);
ALTER TABLE ledger_entries ADD COLUMN IF NOT EXISTS brand_net_revenue numeric(10,2);
