-- order_shipments.shipped_at is the timestamp a brand's parcel actually left. Both write paths in
-- OrderService (confirmShipment and the admin's bulkMarkAllBrandsShipped override) set it in the
-- same breath as status = SHIPPED, so "SHIPPED implies shipped_at is not null" already holds for
-- every row the application writes. It was never enforced anywhere, though, which matters here more
-- than usual: V27 deliberately wrote no rows for historical orders and left a
-- shipment_backfill_review table for humans to work through by hand, writing order_shipments rows
-- directly in SQL. A hand-written SHIPPED row missing shipped_at would silently produce a shipment
-- that claims to have gone out at no particular time — and shipped_at is what the brand-facing
-- views and any later dispatch-time reporting read.
--
-- Defensive backfill first: the constraint is added as a separate statement, and a single
-- pre-existing violation would fail the migration and stop the application from starting. Nothing
-- in the codebase can have produced such a row, so this is expected to update zero rows; it exists
-- so that if one somehow does exist, it is repaired to its best available approximation rather
-- than blocking startup. updated_at is that approximation — on a SHIPPED row it is the moment of
-- the transition that set the status.
update order_shipments
set shipped_at = updated_at
where status = 'SHIPPED' and shipped_at is null;

alter table order_shipments
    add constraint ck_order_shipment_shipped_at
    check (status <> 'SHIPPED' or shipped_at is not null);
