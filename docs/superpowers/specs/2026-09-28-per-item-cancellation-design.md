# Per-brand, per-item pre-shipment cancellation — design

**Date:** 2026-09-28
**Status:** approved — brainstormed, then grilled four rounds (D7–D26 came out of grilling)

## Problem

An admin cancel today is all-or-nothing at the order level: `cancelOrder`/`validateCancellable`
blocks the **entire** order's cancel the instant `anyBrandHasShipped(order)` is true — even if the
brand that shipped has nothing to do with the brand the customer wants to cancel. The return flow
doesn't cover the gap either: `doRequestReturn`'s window only opens once the order reaches
`DELIVERED`.

Concretely: a customer orders from brand A and brand B on one order. Brand A ships same-day. Brand B
is backordered two weeks and the customer wants to drop just brand B's line. No endpoint reaches
this — `cancelOrder` returns 409 on `anyBrandHasShipped`, and `requestReturn`/`adminRequestReturn`
return 409 because the order isn't `DELIVERED`. A full audit of `OrderService.java` confirmed every
other piece of per-brand plumbing (shipping, returns, refunds, ledger) is already brand-scoped and
leak-free; this is the one real gap.

## Facts this design rests on

Verified in the code or in the provider's documentation during grilling — not assumed:

| # | Fact | Source |
|---|---|---|
| F1 | `paidAt` is set only when Mollie reports status `PAID` (captured); an `authorized` payment never sets it | `MolliePaymentService:105`, `MollieWebhookController:48` |
| F2 | `lineGross` is the **pre-discount** price; `customerGrossAfterDiscount` is what the customer paid | `OrderItem.applyMoneySnapshot` |
| F3 | Discounts exist since V3; `customer_gross_after_discount` since V5, added **without backfill** | `V3__discount_codes.sql`, `V5__commission_vat_layer.sql` |
| F4 | Stock is only taken at payment (`decrementStock` in the webhook and admin PENDING→PAID) | `OrderService:829`, `:1084` |
| F5 | `confirmShipment` and `reportShippingProblem` load the order with plain `findById` — **no row lock** | `OrderService:664`, `:701` |
| F6 | Mollie keeps idempotency keys **1 hour**; the same key with different parameters returns **400**, not a replay | [docs.mollie.com/reference/api-idempotency](https://docs.mollie.com/reference/api-idempotency) |
| F7 | `MolliePaymentService.refundPayment` wraps every error in `PaymentException` with the cause preserved | `MolliePaymentService:88-91` |
| F8 | `REFUND_RECORDING_FAILED` is a log line only; no endpoint or view surfaces it | `OrderService:960` |
| F9 | The §22f export is a live query per request, no stored snapshot | `Vat22fExportService` |
| F10 | Shipping is stored per brand: one `OrderShippingSnapshot` per `(orderId, brandPartnerId)` with its `amount` | `OrderShippingSnapshot` |
| F11 | Shipment is per brand: `confirmShipment` ships all of a brand's items at once | `OrderShipment`, `confirmShipment` |

## Goal

An admin can cancel one or more of a single brand's still-unshipped line items on a **paid**,
multi-brand order — refunding exactly what the customer paid for those items (plus that brand's
shipping if nothing of the brand remains), reversing exactly those items' ledger entries, restoring
exactly those items' stock — while the order and its other brands' items continue unaffected, no
other flow (shipping, returns, VAT export, later return reversals) ever treats a cancelled item as
still sold, and no failure mode or retry can ever refund money twice.

## Non-goals

- Partial quantity within one line item. Whole line items only.
- Cross-brand cancellation in one call (D3).
- Customer self-service. Admin-only, mirroring `cancelOrder`.
- Unpaid (`PENDING`) orders (D14) and pre-V5 legacy rows (D12) — the whole-order cancel covers both.
- Recomputing order totals (D16).
- Net-of-refund customer spend stats (D21).
- Fixing the return flow's own money issues — filed as one follow-up issue (D13, Follow-ups).

## Decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | New columns live on `OrderItem`, not on `OrderShipment` or a new entity | Cancellation is a per-item outcome with whole-line-item granularity. A header+lines entity would duplicate `OrderShipment`'s `(order, brand)` uniqueness and split "what happened to this brand's items" across two tables. |
| D2 | New `LedgerService.recordItemCancellationReversal(Order, List<OrderItem>, String refundId)` | The per-brand `recordRefund` applies a fraction to **every** item of the brand. The new method sums the cancelled items' own `commissionNet`/`commissionVat`/`brandPayoutAmount` (post-discount): exact, no fraction. Distinct name — different math, not a different basis. |
| D3 | One call targets exactly one brand's items; mixed-brand requests → 400 | One Mollie refund, one idempotency key, one single-brand ledger reversal. |
| D4 | Idempotency key = claim key = `item-cancel-<orderNumber>-<random UUID>`, **fresh per claim** | Revised during planning. Retries are governed by the claim (D15/D25/D26), never by re-sending a key, so a deterministic key buys nothing — and a fresh one removes two provider unknowns (maximum key length, and a cached *failed* response blocking a retry of the same item set for an hour, F6). Persisted on the claimed items as the claim's identity; the admin copies it from the order view or the log marker to reconcile. |
| D5 | A **partial** item-cancel (brand keeps other active items) never refunds shipping; the exception is D11 | Shipping is charged once per shipment, not per item — same policy as the per-brand return reversal. |
| D6 | When every item on the order `isCancellationSettled()`, roll `Order.status` to `CANCELLED` and run `releaseDiscountUsageOnce` | Otherwise the order sits at `PAID`/`PARTIALLY_SHIPPED` with nothing active. Keyed on **settled**, not claimed (D24): a stuck claim must not cancel the order before its money is recorded. The last call to finalize does the roll. |
| D7 | Status gate = `CANCELLABLE ∪ {PARTIALLY_SHIPPED}`; per-item gate = the target brand's `OrderShipment` isn't `SHIPPED` | `PARTIALLY_SHIPPED` is the order's status once the first brand ships — the motivating scenario. Because shipment is per brand (F11), "brand not SHIPPED" also means "none of its items is in a parcel": ship-then-cancel is rejected here and belongs to the return flow. |
| D8 | Customer notification: `OrderItemsCancelledEvent` + listener, `AFTER_COMMIT`, best-effort, published at **finalize** | Mirror of `RefundCompletedEvent`/`RefundCompletedEmailListener`. At finalize, not claim: a released claim must never have told anyone the item was cancelled. |
| D9 | `brandsOnOrder(order)` skips items where `isCancelled()` | Its callers are `syncShipmentStatus` (whose `allShipped` would otherwise wait forever on an emptied brand) and `bulkMarkAllBrandsShipped` (which would otherwise force-ship one). Keyed on **claimed**, not settled (D24): a claimed item is withdrawn from fulfilment immediately. |
| D10 | Finalize sets `Payment.status = REFUNDED`, mirroring `RefundPersistenceHelper.persist` | Same consequence as returns today: once any item-cancel has finalized, the whole-order `cancelOrder` 409s on that order (existing `REFUNDED` guard). Item-cancel's own guards never read `Payment.status`. |
| D11 | Emptying a brand refunds that brand's `OrderShippingSnapshot.amount` **in the same Mollie refund** and reverses its shipping in the ledger — always both | The emptied brand's parcel never ships. The decision is made once, in the claim transaction under the order lock, and **persisted** as `cancellation_includes_shipping` on the claimed items; the refund amount and the shipping reversal both derive from that stored flag, so they cannot disagree — including on the reconcile path after a crash. |
| D12 | Refund per item = `customerGrossAfterDiscount`; if it is null → 409 "legacy order — use the whole-order cancel" | F2: `lineGross` is pre-discount (a €119 item paid €107.10 with a 10% code must refund €107.10). F3: pre-V5 discounted rows have it null, so any fallback would reintroduce the over-refund. Refusing costs nothing real — a pre-V5 order is long shipped — and removes the fallback branch entirely. |
| D13 | The return flow's money issues are **not** fixed here; drafted as one follow-up issue (Follow-ups) | They change live return behaviour and deserve their own tests and review. |
| D14 | Item-cancel requires a captured payment (`paidAt != null`, F1), else 409 | F4: on `PENDING` stock was never taken, so "restoring" it inflates stock; and `Payment.amount` is fixed at checkout, so the customer could still pay for a cancelled item. Every call is therefore paid — the unpaid branch doesn't exist. |
| D15 | **Claim before refund.** Phase 1 (short write transaction under the Order lock) persists the complete cancellation decision on the target items — everything except the refund id. See Design → Claim lifecycle | Without a claim, overlapping calls ({A,B} and {B,C}) carry different keys and both reach Mollie: B is refunded twice. With it, the second call serialises on the Order lock, sees B claimed, and 409s before money moves. The same move serialises concurrent brand-emptying (exactly one call includes shipping) and, with D23, concurrent shipping. |
| D16 | `Order.subtotal`/`total` and `Payment.amount` stay frozen | Same as returns; `order.getTotal()` is the basis of the ledger's whole-order reversal math. Views show cancelled items and the refunded amount. |
| D17 | Brand notification from the same event, best-effort, at finalize | **Advisory only.** Enforcement is D18 + D23. A best-effort email must never become the only line of defence. |
| D18 | `confirmShipment`/`reportShippingProblem` reject a brand with no items where `!isCancelled()` (409); the dispatch email lists non-cancelled items only | Without it an emptied brand could mark itself shipped and the customer would be told cancelled items are on their way (`OrderService:748`). |
| D19 | `doRequestReturn` excludes items where `isCancelled()` (naming one → 409). `allItemsReturned` (`OrderService:1558`) and `allItemsCovered` (`RefundPersistenceHelper:114`) — character-for-character copies — become one shared check: `allMatch(i -> i.isCancellationSettled() \|\| returned.contains(i.getId()))` | A customer must never return (and be refunded twice for) a cancelled item; an order with some items cancelled and the rest returned must still reach `REFUNDED`. Eligibility keys on **claimed**, the "all covered" gate on **settled** (D24). |
| D20 | §22f export (`findVat22fLineItems`) adds `i.cancelledAt IS NULL` | A cancelled item was never delivered. F9: the export is live, so every export after the cancellation is correct; a file already downloaded before it is an external copy, re-export if needed. Whether **returned** items belong in §22f goes to the tax advisor **together with** this exclusion, not decided unilaterally. |
| D21 | Customer spend stats unchanged | They measure gross purchase volume — what the customer bought — not net cash retained; refunds are tracked in the ledger and payments. Changing this for cancellations alone would make the same stat mean two things. Net spend is a separate follow-up for returns and cancellations together. |
| D22 | Cancelled-and-settled items are excluded from **both** `brandProductGross` and `reverseProductEntries`' fee/vat/payout sums, via one shared filter | Grilled and reversed. A cancelled item is reversed exactly (D2); a later return on the same brand is reversed pro rata. If the cancelled item stays in the pro-rata basis and sum, the brand's total reversal is only correct when every item has the same margin — one discounted item leaves the brand's balance permanently off. Excluding it from both sides makes the later return pro rata over only the not-yet-reversed items: exact totals regardless of margins. No-op on orders without cancellations, so existing behaviour is unchanged. |
| D23 | `confirmShipment`, `reportShippingProblem` and `updateOrderStatus` load the order via `findByIdForUpdate` | F5: they read item state without the Order lock, so a concurrent shipment could read items before a claim commits and ship an item being refunded. With the lock every such writer serialises with claims. Every writer locks the `orders` row first, so lock order is uniform — no deadlock. |
| D24 | Two predicates on `OrderItem`: `isCancelled()` = `cancelledAt != null` (withdrawn from fulfilment — claimed or settled) and `isCancellationSettled()` = `refundTransactionId != null` (money recorded) | Fulfilment gates (D9, D18, D19 eligibility, D20) key on **claimed**: an item being refunded must not ship or be returned. Money gates (D6, D19 "all covered", D22) key on **settled**: a stuck claim must not look refunded. One predicate for both would either reopen the ship-while-refunding race or mark orders refunded before money is recorded. |
| D25 | Mollie failures are classified in `MolliePaymentService`: **definitive** only when Mollie answered with a 4xx; timeouts, I/O errors and 5xx are **ambiguous** | A timeout may have created the refund (and F6: after an hour the same key would create a second one). Only a definitive failure releases the claim; an ambiguous one keeps it. The classification lives in the payment adapter so `OrderService` never inspects SDK exception types. |
| D27 | Finalize re-runs the shipment rollup (`syncShipmentStatus`) after settling | Found during planning. If brand A already shipped and an item-cancel then empties brand B, nothing else triggers the rollup: the order would stay `PARTIALLY_SHIPPED` although every remaining active brand has shipped. `syncShipmentStatus` only ever moves forward within PAID → PARTIALLY_SHIPPED → SHIPPED, so re-running it is safe in every other case. |
| D26 | Stuck claims are resolved through an admin reconcile endpoint (`RECORD` / `RELEASE`), never automatically and never by editing the database | F8: the existing recording-failed precedent is a log line with no resolution path. A SQL runbook would mean redoing stock, ledger, payment status and emails by hand. Automatic resume is ruled out by F6: after an hour, re-calling Mollie with the same key issues a second partial refund, which Mollie accepts. |

## Design

### Claim lifecycle

| State | `cancelled_at` | `refund_transaction_id` | Meaning |
|---|---|---|---|
| Active | null | null | normal item |
| **Claimed** | set | null | withdrawn from fulfilment; refund in flight or stuck |
| **Settled** | set | set | cancelled and refunded |

A claim is identified by `cancellation_claim_key` (= the Mollie idempotency key, D4) shared by all
its items. Claim age = `now − cancelled_at`: under 5 minutes is **in progress** (one Mollie
round-trip), 5 minutes or more is **stuck**.

### Flow

`OrderService.cancelOrderItems(orderId, dto, admin)` — not `@Transactional`; three steps.

**1. Claim (short write transaction, `findByIdForUpdate`).**
- Order exists; status in `CANCELLABLE ∪ {PARTIALLY_SHIPPED}`, else 409 (D7).
- Payment captured (`paidAt != null`), else 409 (D14).
- Requested ids (de-duplicated) all exist on the order, else 400; all one `BrandPartner`, else 400 (D3).
- Every target item has `customerGrossAfterDiscount != null`, else 409 (D12).
- No target item `isCancelled()`, else 409 — message distinguishes, per claim age, "a cancellation of
  these items by `<admin>` is in progress" from "a cancellation of these items is stuck since `<t>`
  — reconcile it" (D26).
- Target brand's `OrderShipment` isn't `SHIPPED`, else 409 (D7).
- `includesShipping` = every *other* item of the brand `isCancelled()` (D11).
- Write on each target item: `cancelledAt`, `cancellationReason`, `cancellationNote`,
  `cancelledByAdminEmail`, `cancellationIncludesShipping`, `cancellationClaimKey`. Commit.

**2. Refund (no transaction).**
`refundAmount` = Σ `customerGrossAfterDiscount` over the claim's items + (includes shipping ? the
brand's `OrderShippingSnapshot.amount` : 0). `paymentProvider.refundPayment(new RefundCommand(
transactionId, refundAmount, description, claimKey))`.
- Success → step 3.
- **Definitive** failure (D25) → `releaseClaim(orderId, claimKey)`; `PaymentException` → 400 "items
  were NOT cancelled"; retry is safe.
- **Ambiguous** failure (D25) → keep the claim; log `ITEM_CANCEL_AMBIGUOUS` (order, claim key); 409
  "refund outcome unknown — check Mollie, then reconcile".

**3. Finalize — `finalizeClaim(orderId, claimKey, refundId)` (one transaction).**
Reads everything from the database; nothing is carried over from step 1. Shared with reconcile
`RECORD`.
- `StaleSessionGuard.clear(entityManager)`; re-fetch under `findByIdForUpdate`.
- Load the claim's items by `cancellationClaimKey`; each must be claimed and unsettled, else fail
  loudly (a settled claim is never finalized twice).
- Per item: `refundTransactionId = refundId`; `restoreStock(variantId, quantity)`.
- `payment.setStatus(REFUNDED)` (D10).
- `ledgerService.recordItemCancellationReversal(order, items, refundId)` (D2); if the items'
  `cancellationIncludesShipping`, `ledgerService.reverseShippingForEmptiedBrand(order, brandId,
  refundId)` (D11).
- Re-run `syncShipmentStatus(order)` (D27) — an emptied brand may have been the last one the order
  was waiting on.
- If every item on the order `isCancellationSettled()`: `order.setStatus(CANCELLED)`,
  `releaseDiscountUsageOnce(order)` (D6).
- Publish `OrderItemsCancelledEvent` (D8, D17).
- If finalize throws after a successful refund: log `ITEM_CANCEL_RECORDING_FAILED` (order, claim key,
  refund id); the claim stays; the admin reconciles with `RECORD` and that refund id.

**`releaseClaim(orderId, claimKey)`** — one transaction under the Order lock: clears
`cancelledAt`/`cancellationReason`/`cancellationNote`/`cancelledByAdminEmail`/
`cancellationIncludesShipping`/`cancellationClaimKey` on the claim's unsettled items. If the brand's
shipment is now `SHIPPED` (it shipped its other items while the claim held), log
`ITEM_CANCEL_ABORTED_AFTER_SHIPMENT` — the released items read as shipped but never left. Shared by
step 2's definitive-failure path and reconcile `RELEASE`.

### Reconcile endpoint (D26)

`POST /admin/orders/{orderId}/cancel-items/reconcile`, admin-only:

```json
{ "claimKey": "item-cancel-ENS-2026-ABC123-41-42", "action": "RECORD", "refundId": "re_..." }
{ "claimKey": "item-cancel-ENS-2026-ABC123-41-42", "action": "RELEASE" }
```

- Only on a **stuck** claim (≥ 5 minutes), else 409 "in progress" — never races a live call.
- `RECORD` — the admin has confirmed at Mollie that this refund exists → `finalizeClaim`. No Mollie
  call.
- `RELEASE` — the admin has confirmed at Mollie that no refund exists → `releaseClaim`. No Mollie call.
- Every reconcile logs the acting admin and action.

Discovery: the admin order view shows each item's derived `cancellationState` (`ACTIVE` / `PENDING` /
`CANCELLED`) and the claim key; the `ITEM_CANCEL_AMBIGUOUS` and `ITEM_CANCEL_RECORDING_FAILED` log
markers follow the existing `EMAIL_DELIVERY_FAILURE` convention for log-based alerts. A stuck claim is
also queryable: `cancelled_at < now() - interval '5 minutes' AND refund_transaction_id IS NULL`.

### Ledger

```java
public void recordItemCancellationReversal(Order order, List<OrderItem> items, String refundId) {
    if (ledgerRepository.existsByExternalReferenceIdAndEntryType(refundId, LedgerEntryType.REFUND_REVERSAL)) {
        log.warn("LedgerService: REFUND_REVERSAL already recorded for externalRefundId={}; skipping", refundId);
        return;
    }
    // Σ commissionNet / commissionVat / brandPayoutAmount over exactly these items → one
    // REFUND_REVERSAL entry, applyBrandDebit(brandId, payout) — no fraction, no proration.
}

public void reverseShippingForEmptiedBrand(Order order, Long brandId, String refundId) {
    reverseShippingEntries(order, brandId, BigDecimal.ONE, refundId);
}
```

D22: one private filter — the brand's items where `!isCancellationSettled()` — used by both
`brandProductGross` and `reverseProductEntries`, so their basis and sum can never drift apart. The
order-wide `recordRefund(order, amount, id)` also iterates via `reverseProductEntries`; it is
unaffected in practice because the whole-order cancel is blocked once any item-cancel has settled
(D10), and the late-payment refund only runs on orders never paid before cancellation (disjoint from
D14).

### Schema

`V36__order_item_cancellation.sql` (after the uncommitted `V35__return_order_version.sql` — re-check
the next free number at implementation time):

```sql
ALTER TABLE order_items ADD COLUMN cancelled_at TIMESTAMP;
ALTER TABLE order_items ADD COLUMN cancellation_reason VARCHAR(255)
    CHECK (cancellation_reason IN ('FRAUD_SUSPICION','OUT_OF_STOCK','CUSTOMER_REQUEST','TECHNICAL_ERROR','OTHER'));
ALTER TABLE order_items ADD COLUMN cancellation_note VARCHAR(500);
ALTER TABLE order_items ADD COLUMN cancelled_by_admin_email VARCHAR(255);
ALTER TABLE order_items ADD COLUMN cancellation_includes_shipping BOOLEAN;
ALTER TABLE order_items ADD COLUMN cancellation_claim_key VARCHAR(255);
ALTER TABLE order_items ADD COLUMN refund_transaction_id VARCHAR(64);
```

No index: finalize, release and reconcile find a claim's items by filtering the order's own
already-loaded items, never by querying the column.

Widths and the check constraint match the existing `orders.cancellation_*` / `cancelled_by_admin_email`
(`V0.0.1`) and `orders.refund_transaction_id` (`V34`). All nullable; all null on every existing row.

### API

`POST /admin/orders/{orderId}/cancel-items`, admin-only:

```java
public class CancelOrderItemsDto {
    @NotEmpty private List<Long> orderItemIds;
    @NotNull private CancelReason reason;
    @Size(max = 500) @NoHtml private String note;
}
```

A separate class: adding `@NotEmpty orderItemIds` to the shared `CancelOrderDto` would break the
whole-order endpoint's validation. 200 (settled); 400 (unknown item, mixed brands, definitive Mollie
failure — items NOT cancelled); 409 (status, unpaid, legacy row, brand shipped, item claimed/settled,
ambiguous Mollie outcome).

### Touched read and write paths

| Path | Change | Decision |
|---|---|---|
| `confirmShipment`, `reportShippingProblem`, `updateOrderStatus` | `findByIdForUpdate` | D23 |
| `confirmShipment`, `reportShippingProblem` | reject a brand with no non-cancelled items; dispatch email lists non-cancelled items | D18 |
| `brandsOnOrder` | skip `isCancelled()` items | D9 |
| `doRequestReturn` | exclude `isCancelled()` items; naming one → 409 | D19 |
| `allItemsReturned` + `allItemsCovered` | merged: `isCancellationSettled() \|\| returned` | D19 |
| `brandProductGross` + `reverseProductEntries` | shared filter excluding settled-cancelled items | D22 |
| `findVat22fLineItems` | `AND i.cancelledAt IS NULL` | D20 |
| customer stats | none | D21 |

### DTO

Per-item representation in `OrderResponseDto` (customer and admin share this DTO): `cancelledAt`,
`cancellationReason`, derived `cancellationState` (`ACTIVE` / `PENDING` / `CANCELLED`),
`refundTransactionId`, `cancellationClaimKey`. The claim key carries only the order number and a
random UUID, so exposing it to the customer is harmless — a separate admin variant would be a second
DTO for no protection. Brand-scoped item DTO: `cancelledAt`, `cancellationReason`,
`cancellationState` only — never the refund id or claim key (a claimed item already reads
"cancelled — don't ship").
`cancellationNote` is exposed nowhere. Totals unchanged (D16).

### Email

`OrderItemsCancelledEvent(buyerEmail, orderNumber, brandName, brandEmail, itemDescriptions,
refundAmount, currency)`, published by `finalizeClaim` only — customer: what was cancelled and
refunded; brand: which items not to ship. Best-effort, `AFTER_COMMIT`, `EMAIL_DELIVERY_FAILURE` on
send failure, like `RefundCompletedEmailListener`.

## Test plan

New `AdminItemCancellationIntegrationTest` (Testcontainers + `mock-payments`):

**Happy paths**
1. Cancel 1 of 2 brand-A items on a 2-brand order: one Mollie refund of that item's
   `customerGrossAfterDiscount`; item settled with reason/refund id; only its variant's stock
   restored; one `REFUND_REVERSAL` at exactly its fee/vat/payout; order stays `PAID`; brand B
   untouched; customer and brand emails sent.
2. Discounted item: refund = `customerGrossAfterDiscount`, not `lineGross` (D12).
3. Order at `PARTIALLY_SHIPPED` (brand A shipped): cancelling brand B's items succeeds (D7).
4. Emptying a brand (`itemCancelEmptyingBrand_refundsShippingAndReversesLedger`): refund = items +
   shipping snapshot; shipping `REFUND_REVERSAL` under `shippingRef(refundId)`; the order still reaches
   `SHIPPED` once the other brand ships (D9); `bulkMarkAllBrandsShipped` never force-ships it; its
   `confirmShipment` → 409 (D18).
5. Partial cancel keeps the brand active: no shipping in the refund or ledger (D5).
6. Every item cancelled: order `CANCELLED`, discount usage released once (D6).
7. Second item-cancel after the first set the payment `REFUNDED`: still refunds and reverses; the
   whole-order cancel now 409s (D10).
8. Separate calls for A then B: two distinct refunds (D4).

**Rejections — nothing written, no Mollie call**
9. Target brand `SHIPPED` → 409. 10. `PENDING` order → 409 (D14). 11. Item with null
`customerGrossAfterDiscount` → 409 (D12). 12. Mixed brands → 400. 13. Item already claimed → 409 "in
progress"; claim older than 5 minutes → 409 "stuck … reconcile" (D26).

**Concurrency**
14. Overlapping calls {A,B} vs {B,C} (two threads): exactly one succeeds, one Mollie refund, B
    refunded once (D15).
15. Two calls that together empty a brand: exactly one refund includes the shipping (D11, D15).
16. `confirmShipment` concurrent with a claim (two threads): the shipment never includes a claimed
    item, and the dispatch email never lists one (D23, D18).

**Failure handling**
17. Definitive Mollie failure (4xx): 400, claim released, items active; retry succeeds (D25).
18. Ambiguous failure (timeout): claim kept, `ITEM_CANCEL_AMBIGUOUS` logged, 409; a retry of the same
    items 409s and makes no Mollie call (D25).
19. Finalize fails after refund: claim kept, `ITEM_CANCEL_RECORDING_FAILED` logged with the refund id.
20. Reconcile `RECORD` on a stuck claim: settles it exactly like step 3, no Mollie call; `RECORD` on a
    claim under 5 minutes → 409; `RECORD` on a settled claim → 409 (D26).
21. Reconcile `RELEASE`: items active again, no Mollie call; if the brand shipped meanwhile,
    `ITEM_CANCEL_ABORTED_AFTER_SHIPMENT` logged.

**Other flows**
22. Returns: naming a cancelled item → 409; a whole-order return skips cancelled items; an order with
    one item cancelled and the rest returned and refunded reaches `REFUNDED` and releases its discount
    (D19); a *stuck* claim keeps it from reaching `REFUNDED` (D24).
23. Ledger regression (D22): brand has discounted A and undiscounted B; A is item-cancelled, B later
    delivered and returned; assert the brand's exact final balance and each reversal's exact amounts.
24. §22f: a cancelled item is absent; a returned item is present (D20).

## Follow-ups

**Issue draft — "Return refunds: money correctness"** (to file once `gh` is installed; due date:
owner's call)

1. **Over-refund on discounted items.** `OrderService.refundableTotal` sums `lineGross`, which is the
   pre-discount price (`OrderItem.applyMoneySnapshot`). A €119 item bought with a 10% code (paid
   €107.10) is refunded €119 on return. Fix: `customerGrossAfterDiscount`, with the same legacy-row
   refusal as item-cancel D12.
2. **Pro-rata ledger reversal.** Per-brand return reversal applies `refund / brandProductGross` to the
   sum of all the brand's items, so a single return attributes fee/vat/payout wrongly when margins
   differ. Fix: reuse item-cancel's exact per-item reversal (D2), prorated by `quantityReturned`.
3. **Idempotency-only duplicate protection.** `processRefund` calls Mollie before its database
   duplicate guard (previously accepted as a known limitation). Mollie keeps idempotency keys for
   1 hour only (F6): a retry after a recording failure more than an hour later issues a second partial
   refund. Fix: the same claim-before-refund pattern as item-cancel (D15).

**Other**
- Net-of-refund customer spend stats for returns and cancellations together (D21).
- Tax advisor: whether returned items — and, for confirmation, pre-shipment-cancelled items — belong
  in the §22f record (D20).

## Risks

- **A stuck claim nobody reconciles** — money may be back while items read `PENDING`. Mitigated by the
  log markers, the admin order view's `PENDING` state, the stuck-claim query and the 409 text; not
  eliminated — it still needs a human to act.
- **Released claim after the brand shipped its other items** — the released items read as part of a
  `SHIPPED` brand although nothing left for them. Logged as `ITEM_CANCEL_ABORTED_AFTER_SHIPMENT` for an
  admin; requires a definitive Mollie failure or a `RELEASE` during the brand's shipping window.
- **Legacy rows** — pre-V5 items are refused (D12) rather than risk an over-refund; affected orders
  are long shipped in practice.
