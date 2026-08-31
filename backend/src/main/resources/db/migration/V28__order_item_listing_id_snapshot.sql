-- items[].listingId in POST /orders/preview and every persisted order response was silently the
-- variant id — order_items never stored the listing id the buyer actually ordered against, only
-- variant_id (deliberately: listings are deletable/deactivatable without touching order history).
-- OrderService now freezes the real listing id into this column at order-creation time.
--
-- Nullable, no FK: existing rows stay NULL (there is no way to reconstruct which listing was live
-- for a variant at some point in the past — a variant can have had several listings over time), and
-- a listing being later deleted must never cascade into or block reading historical order data.
alter table order_items add column listing_id_snapshot bigint;
