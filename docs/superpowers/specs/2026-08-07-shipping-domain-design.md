# Shipping Cost Domain — Design

## Context

Today, shipping is free: `OrderService.createOrder` hardcodes `Order.shippingTotal = BigDecimal.ZERO`, with an explicit comment that "the platform never charges, collects, or splits shipping — the brand bears its own carrier cost off-platform." `Order.shippingTotal` and `OrderResponseDto.shippingTotal` already exist as columns/fields but are dead weight.

There is also a **dormant, fully-built extension point already in the codebase**: `BrandShippingProfile` (`com.enunas.backend.brandpartner.brandshippingprofile`), table `brand_shipping_profiles`, created by Hibernate's old `ddl-auto` export and present in the `V0.0.1` baseline migration. It has `originCountry`, `handlesOwnShipping`, `avgShippingDays`, and `shippingCost` fields, a repository, and **zero code anywhere that reads or writes it**. This design activates that entity rather than inventing a parallel one.

This design makes shipping a real, priced, marketplace-accounted line: the customer pays it, Enunas collects it via Mollie alongside the product total, and it flows to the brand through the existing ledger/payout pipeline — untouched by commission.

## Goals

1. A dedicated `ShippingCostService` that computes a shipping amount per brand, given a brand, destination address, and cart items — even though V1's calculation only uses the brand.
2. Shipping is snapshotted immutably per (order, brand) at order-creation time, so later rate changes never alter historical orders.
3. Shipping revenue is its own ledger entry type, structurally incapable of feeding commission math (commission is computed only from `OrderItem`/product figures, as today).
4. Brand payout, admin order detail, brand dashboard, settlement, and the customer-facing checkout preview all show product/shipping/commission/payout as separate, clearly labeled figures.
5. Extensible without new tables or interface changes for: per-brand rates (already in scope), per-country rates, weight-based rates, express shipping, free-shipping campaigns.

## Non-goals

- Country/region/weight/express shipping calculation logic — the interface accepts the data needed for these, V1's implementation ignores everything but the brand.
- Refunding shipping on a partial per-brand item return — architecture stays traceable for this (separate ledger entries, clean reversal seam), but the actual policy isn't built.
- A generalized multi-currency system — the platform is EUR-only throughout; currency columns are added for auditability, not to enable non-EUR orders now.
- Any change to product commission math, VAT treatment, or the discount system.

## Architecture

### `ShippingCostService` — calculation layer

New package `com.enunas.backend.shipping`:

```java
public interface ShippingCostService {
    ShippingCostResult calculate(BrandPartner brand, ShippingAddress destination, List<OrderItem> brandItems);
}
```

```java
public record ShippingCostResult(
    BigDecimal amount,
    String currency,
    ShippingCalculationMethod method,   // GLOBAL_DEFAULT | BRAND_FLAT_RATE | BRAND_FREE_SHIPPING
    String ruleVersion,                 // e.g. "flat-v1" — which calculation logic version produced this
    Long brandShippingProfileId         // nullable — which BrandShippingProfile row this came from, if any
) {}
```

V1 implementation, `FlatRateShippingCostService`, ignores `destination` and `brandItems` entirely (parameters exist now so the signature never has to change when a future implementation starts using them) and resolves the amount purely from the brand's `BrandShippingProfile`:

1. No `BrandShippingProfile` row for this brand → `GLOBAL_DEFAULT`, amount = `enunas.shipping.default-rate` config (new key, mirrors `enunas.platform.commission-rate`), `brandShippingProfileId = null`.
2. Row exists, `shippingCost == null` → same as (1) but `brandShippingProfileId` set to the row's id (brand has a profile, just hasn't set a rate).
3. Row exists, `shippingCost.compareTo(ZERO) == 0` → `BRAND_FREE_SHIPPING`, amount = 0.
4. Row exists, `shippingCost > 0` → `BRAND_FLAT_RATE`, amount = that value.

This removes the ambiguity in the entity's current javadoc ("Null/zero = free"): **null and explicit zero are different, named, persisted outcomes** — an admin or support agent reading a snapshot's `calculation_method` never has to infer intent from a number. That javadoc gets corrected as part of this change.

`BrandShippingProfileRepository` gains `findByBrandPartner_Id(Long)` (the existing `findByBrandPartner(BrandPartner)` requires loading the entity first; every other per-brand repository in this codebase looks up by id).

### Order creation integration

`OrderService.createOrder` already groups `OrderItem`s by brand (for `brandSubtotals`). After that grouping, for each distinct brand: call `ShippingCostService.calculate(...)`, accumulate into `Order.shippingTotal` (stop hardcoding zero), and prepare one `OrderShippingSnapshot` per brand to persist alongside the order.

`Order.total = subtotal − discountAmount + shippingTotal` — this is what gets charged via Mollie and verified by the webhook, same mechanism as today, just no longer force-zeroing the shipping term.

**Shared pricing computation.** Steps 1–5 of today's `createOrder` (resolve/validate listings, build `OrderItem` money snapshots, apply discount, calculate shipping per brand) are extracted into a private `buildPricingDraft(CreateOrderDto, User)` returning an in-memory `OrderPricingDraft` (unsaved `OrderItem`s, per-brand shipping results, subtotal, discount, shipping total, grand total, resolved address, currency). `createOrder` persists a draft into `Order`/`OrderItem`/`OrderShippingSnapshot`/`Payment` exactly as it does today; the new checkout preview endpoint (below) maps the same draft straight to a response DTO with no persistence at all. This guarantees preview and actual charge can never drift, by construction — they run the identical code path.

### Checkout preview (new)

`POST /orders/preview`, same request shape as `CreateOrderDto` (items, address-or-saved-address-id, optional discount code), `@PreAuthorize("hasRole('CUSTOMER')")`, no `@Transactional` writes. Calls `buildPricingDraft` and returns:

```java
OrderPreviewResponseDto {
    items: [{ listingId, productName, quantity, unitPrice, lineTotal }]
    subtotal, discountCode, discountAmount,
    shippingBreakdown: [{ brandId, brandName, amount, calculationMethod }],
    shippingTotal, total, currency
}
```

Nothing is written to the database — a preview can be called repeatedly (e.g. on every cart change) with no side effects and no stock impact. This is what the frontend calls before showing the "pay" button, so the customer sees Products / Shipping / Total broken out before committing, per the marketplace UX requirement.

### Ledger integration

`LedgerEntryType` gains `SHIPPING_REVENUE`.

`LedgerService.recordOrderPayment` gains a second, explicit step (not folded into the existing per-item loop) that creates one `SHIPPING_REVENUE` entry per `OrderShippingSnapshot`: `brandPayout = amount`, `platformFee = 0`, `commissionNet = 0`, `commissionRate = 0`. Because these entries are typed and constructed from the snapshot — never from `OrderItem` — there is no code path by which shipping money can be folded into a commission calculation; commission remains sourced exclusively from `OrderItem.commissionNet`/`baseCommissionNet`, as today. These entries feed `BrandEconomics.pendingBalance`/`lifetimeRevenue` through the exact same hold-then-release pipeline (`releasePendingBalances`) product entries already use — shipping money is held and released on the same schedule as product money.

**Refund reversal — split, not merged.** `LedgerService` currently has one method (`recordRefund`, brand-scoped when `brandId != null`, order-wide when `null`) that reverses `ORDER_PAYMENT` entries. It's split into two focused private methods:

```java
private void reverseProductEntries(Order order, Long brandId, BigDecimal fraction, String externalRefundId)
private void reverseShippingEntries(Order order, Long brandId, BigDecimal fraction, String externalRefundId)
```

- `recordRefund(Order order, BigDecimal refundAmount, String externalRefundId)` — the **order-wide** path, used only by the pre-shipment admin cancel (`PENDING`/`PAID` → `CANCELLED`). Computes one `fraction` against `order.getTotal()` (now product + shipping) and calls **both** methods for every brand on the order — a full-order cancel undoes everything, product and shipping alike.
- `recordRefund(Order order, Long brandId, BigDecimal refundAmount, String externalRefundId)` — the **per-brand return** path (`RefundPersistenceHelper` → `processRefund`). Calls **only** `reverseProductEntries`. Shipping is deliberately untouched, with a comment marking the extension seam: a future `RefundPolicyService` is where "customer returned every item from Brand A — should Brand A's shipping also be refunded?" gets decided; this design doesn't answer that question, it just makes sure the answer, whenever it comes, has clean, separately-reversible product and shipping ledger trails to work from.

Each reversal writes its own `REFUND_REVERSAL` row. To keep the two calls independently idempotent under the existing `existsByExternalReferenceIdAndEntryType(id, REFUND_REVERSAL)` check — which has **no DB uniqueness constraint behind it, only an application-level guard, confirmed by inspection of `LedgerEntry`/the migrations** — `reverseShippingEntries` uses `externalRefundId + ":SHIPPING"` as its own reference id. Distinct strings, so the two guards never collide and neither can suppress the other on first call.

### Data model — `OrderShippingSnapshot`

New entity, immutable after creation (same philosophy as `OrderItem`'s purchase snapshot — never updated once written):

```java
@Entity @Table(name = "order_shipping_snapshots")
class OrderShippingSnapshot {
    Long id;
    Long orderId;
    Long brandPartnerId;
    BigDecimal amount;
    String currency;
    ShippingCalculationMethod calculationMethod;   // GLOBAL_DEFAULT | BRAND_FLAT_RATE | BRAND_FREE_SHIPPING
    String ruleVersion;                            // "flat-v1"
    Long brandShippingProfileId;                   // nullable — traceability only, not used in any calculation
    LocalDateTime createdAt;
}
```

`brandShippingProfileId` is deliberately **not** a JPA `@ManyToOne` — it's a plain nullable id column, debugging/support traceability only ("order #123 charged €4.99 — generated from BrandShippingProfile #55, rule flat-v1" — even if that profile's rate has since changed or the row itself was later deleted). No downstream logic ever reads it back to recompute anything.

## Database migration (`V22__shipping_domain.sql`)

Additive only:

```sql
CREATE TABLE IF NOT EXISTS order_shipping_snapshots (
    id                        BIGSERIAL PRIMARY KEY,
    order_id                  BIGINT NOT NULL REFERENCES orders(id) ON DELETE RESTRICT,
    brand_partner_id          BIGINT NOT NULL REFERENCES brand_partners(id) ON DELETE RESTRICT,
    amount                    NUMERIC(10,2) NOT NULL,
    currency                  VARCHAR(3) NOT NULL DEFAULT 'EUR',
    calculation_method        VARCHAR(30) NOT NULL,
    rule_version              VARCHAR(20) NOT NULL,
    brand_shipping_profile_id BIGINT REFERENCES brand_shipping_profiles(id) ON DELETE SET NULL,
    created_at                TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_shipping_snapshot_order_brand UNIQUE (order_id, brand_partner_id)
);
CREATE INDEX IF NOT EXISTS idx_shipping_snapshot_order ON order_shipping_snapshots(order_id);
CREATE INDEX IF NOT EXISTS idx_shipping_snapshot_brand ON order_shipping_snapshots(brand_partner_id);

-- order_id / brand_partner_id use ON DELETE RESTRICT: these are financial records, an order or
-- brand must never be able to disappear while money snapshots referencing it still exist.
-- brand_shipping_profile_id uses ON DELETE SET NULL: it's debugging traceability only, not a
-- financial dependency — deleting a stale profile must never be blocked by old snapshots.

-- ledger_entries: new entry type is a Java enum value (SHIPPING_REVENUE), stored in the existing
-- entry_type varchar column — no DDL change needed.

-- brand_shipping_profiles: add currency to the existing (dormant, currently unwritten) money
-- column. Explicit three-step migration rather than relying on DEFAULT alone, since this table
-- predates this change and its row population at migration time is not something this script
-- should assume:
ALTER TABLE brand_shipping_profiles ADD COLUMN IF NOT EXISTS currency VARCHAR(3);
UPDATE brand_shipping_profiles SET currency = 'EUR' WHERE currency IS NULL;
ALTER TABLE brand_shipping_profiles ALTER COLUMN currency SET NOT NULL;
ALTER TABLE brand_shipping_profiles ALTER COLUMN currency SET DEFAULT 'EUR';
```

No existing table's existing rows are altered destructively; `orders.shipping_total` and `ledger_entries` already have every column this design needs.

## Admin configuration

`PATCH /admin/brands/{brandId}/shipping-profile`, mirroring the existing `PATCH /admin/brands/{brandId}/payout-profile` (`AdminController`/`AdminService`, `BrandPayoutProfileRepository.findByBrandPartner_Id(...).ifPresentOrElse(update, create)`). Body: `{ shippingCost, originCountry, avgShippingDays }` (all optional/nullable — an admin can set `shippingCost: 0.00` to explicitly flag a brand as free-shipping, or leave it unset to fall back to the global default).

## API surface

- `OrderResponseDto` gains `shippingSnapshots: List<ShippingSnapshotDto>` (`brandId`, `brandName`, `amount`, `currency`, `calculationMethod`) — same pattern as the existing `returns` list. Legacy orders with no snapshot rows simply get an empty list; `shippingTotal` already defaults to 0 for them, so existing orders remain fully readable, admin and customer views alike.
- Brand dashboard (`BrandPartnerOrderController` → `OrderResponseDto`) rides on the same `shippingSnapshots` field — a brand reads its own row out of the list.
- `SettlementRowDto`/`SettlementService` aggregation gains `shippingRevenue` (sum of `SHIPPING_REVENUE.brandPayout` for the period), kept visually and structurally separate from `commissionNet`/`commissionVat`/`payoutAmount`.
- New `POST /orders/preview` (customer checkout summary, see above).

## Testing

1. `FlatRateShippingCostService`: all four resolution cases (no profile, profile with null cost, profile with zero cost, profile with positive cost) produce the correct amount and `calculationMethod`.
2. Commission is unaffected by shipping — an order with a non-zero shipping snapshot has `OrderItem.commissionNet` computed identically to the same order with shipping stripped out (i.e., commission is a pure function of `lineNet`, verified by construction, not just by output).
3. Multi-brand order → multiple `OrderShippingSnapshot` rows, correct per-brand amounts (the spec's "Brand A: 4.99€, Brand B: 6.99€" case).
4. Legacy orders (no `OrderShippingSnapshot` rows) still deserialize and render via `OrderResponseDto` with an empty `shippingSnapshots` list and unaffected `shippingTotal`.
5. Settlement aggregation: `shippingRevenue` sums correctly and independently of `commissionNet`/`payoutAmount` for a period.
6. Ledger refund split: order-wide cancel reverses both `ORDER_PAYMENT` and `SHIPPING_REVENUE` entries for every brand; per-brand return reverses only `ORDER_PAYMENT` entries, `SHIPPING_REVENUE` entries for that brand are untouched. Both reversal external-reference-id suffixes are independently idempotent (calling either refund path twice with the same id produces no duplicate entries).
7. Checkout preview: `POST /orders/preview` returns the same `shippingTotal`/`total` that `createOrder` would charge for an identical request, and persists nothing (no `Order`, no `Payment`, no stock decrement).
