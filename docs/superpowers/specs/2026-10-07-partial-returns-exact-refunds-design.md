# Partial returns with exact, tracked refunds — design

Date: 2026-10-07 · Status: approved in brainstorming (sections 1–3 + critique) · Classification: production

## Goal

A customer (or an admin, as goodwill) can return k of n units of an order line. Money goes back
only once the goods are confirmed back (or an admin overrides with a reason), always for the exact
amount the customer paid for those units — plus the brand's shipping once everything from that
brand is back — and the ledger reverses exactly that. A refund is only "done" when Mollie confirms it.

Fixes the three known return-refund bugs (memory `project_returns_overrefund_discount`):

1. Refund ceiling used pre-discount `lineGross` → discounted items over-refunded.
2. Ledger reversal pro-rata across all of a brand's items instead of the returned units.
3. Duplicate-refund guard relied only on Mollie's idempotency key (expires after 1 h).

## Non-goals (v1)

- A second active return per (order, brand) while the first is past REQUESTED — V15 unique index
  stays; customer waits until the first is refunded. Deliberate v1 limitation.
- Admin-entered refund amounts / deductions (Wertersatz). Removed; refund is always calculated.
- Automatic reconciliation by listing Mollie refunds. Manual reconcile stays (with stronger checks).
- Claims for whole-order cancel and late-payment auto-refund paths.
- A shared claim runner for cancellation + returns (architecture candidate 4) — revisit only if the
  duplication proves real after this lands.
- Payout hold window vs. Widerruf, settlement history, `SHIPPING_REVENUE` rename — separate specs.
- `PARTIALLY_REFUNDED` payment status.

## Flow and names

| Step | Who | UI name (DE) | Endpoint | Effect |
|---|---|---|---|---|
| 1 | Customer / admin | Retoure angefragt | `POST /orders/{id}/return`, `POST /admin/orders/{id}/return` (+ optional `quantity`) | `REQUESTED` |
| 2 | Admin | **Retoure genehmigen** | `POST /admin/returns/{n}/approve` (unchanged) | `APPROVED` |
| 3 | Brand | **Ware erhalten** | `POST /brand/returns/{n}/receive` (**new**) | `RECEIVED`, stock restored, `receivedBy = BRAND` |
| 3′ | Admin fallback | Ware erhalten | `POST /admin/returns/{n}/receive` (unchanged) | same, `receivedBy = ADMIN` |
| 4 | Admin | **Erstattung freigeben** | `POST /admin/returns/{n}/release-refund` (**replaces** `/refund`) | claim → Mollie → `refundState = PENDING` |
| 4′ | Admin override | Erstattung ohne Wareneingang | same endpoint from `APPROVED`, body `{ "overrideReason": "…" }` (required there) | as 4, stock **not** restored |
| 5 | Mollie webhook | — | `POST /webhooks/mollie` | `PENDING` → `REFUNDED` (status `REFUNDED`, customer email) or `FAILED` |
| 5′ | Admin | Erneut senden | `POST /admin/returns/{n}/release-refund` while `refundState = FAILED` | new Mollie refund, same amounts, no new ledger entries |

- Brand receive authorisation: the return's brand must belong to the calling brand user (same check as
  `uploadReturnLabel`).
- Late arrival after override: brand/admin receive is also allowed when `status = REFUNDED` and
  `receivedAt == null` → restores stock, sets `receivedAt`/`receivedBy`, status stays `REFUNDED`.
- Removed: `refundAmount` request param, `POST /admin/returns/{n}/refund`,
  deprecated `POST /admin/orders/{id}/return/refund`. Deprecated approve/receive aliases stay.
- Refund overview: `GET /admin/refunds?state=DUE|IN_PROGRESS|PENDING|FAILED|REFUNDED` (paged). Rows: return
  number, order number, brand, state, item amount, shipping amount, total, released by, override
  reason, claim key, Mollie refund id, timestamps.
  - `DUE` = `status = RECEIVED` and no claim.
  - `IN_PROGRESS` = claim exists, no refund id (includes stuck claims).
  - `PENDING` / `FAILED` / `REFUNDED` = `refundState`.

## Return request with quantity

`ReturnRequestDto(Long orderItemId, Integer quantity, ReturnReason reason, String description)`.

- `quantity` optional, `@Positive`; only allowed together with `orderItemId`. Null = all remaining units.
- `orderItemId == null` = every remaining unit of every non-cancelled line (split per brand as today).
- Remaining units of a line = `quantity` − Σ `quantityReturned` over all `ReturnItem`s of that line
  (any return, any status). Request for more than remaining → 400. Line with 0 remaining → skipped
  (whole-order request) or 400 (single-line request). `findReturnedOrderItemIds` is replaced by a
  per-line returned-quantity query.
- Merge into the brand's `REQUESTED` return: if that return already has a `ReturnItem` for the line,
  its `quantityReturned` increases; otherwise a new `ReturnItem` is added. Past `REQUESTED` → blocked as today.
- `ReturnCoverage.allItemsCovered` becomes quantity-aware: a line is covered when cancelled-settled
  or Σ returned quantity on **refunded** returns == line quantity.

## Money: the Refund Quote

A pure module (no DB, no Spring) — the single place that computes money going back for item lines.
Used by return release and by per-item cancellation (cancellation = k = n for each line).

Input: per line the `OrderItem` money snapshot (`quantity`, `customerGrossAfterDiscount`,
`commissionNet`, `commissionVat`, `brandPayoutAmount`), units k, and units already refunded/claimed
before this one; plus the brand's shipping amount and whether shipping is included.

Rule per line and per money field F (customer amount, commissionNet, commissionVat, brandPayout):

```
unitF  = round_half_up(F_line / n, 2)
if alreadyRefundedUnits + k == n:   part = F_line − unitF × alreadyRefundedUnits     (last units take remainder)
else:                               part = unitF × k
```
Example: 3 × €100 paid → €33,33 + €33,33 + €33,34. The rule is applied to the customer amount,
commissionNet and commissionVat; the brandPayout part is derived as
`customer part − commissionNet part − commissionVat part`, so every part sums exactly to the money
going back (the line snapshot satisfies `brandPayout = customerGrossAfterDiscount − commissionNet −
commissionVat`; the quote asserts this per line and refuses a line where it doesn't hold).

Output: item lines (orderItemId, k, customer amount, commissionNet, commissionVat, brandPayout),
`shippingAmount` (0 when not included), `total`.

- Legacy line with `customerGrossAfterDiscount == null` → refused with a clear error (as cancellation).
- Shipping included iff after this return every non-cancelled unit of the brand on the order is on a
  return (including this one) **and** no shipping reversal exists yet for that brand on the order.
  Amount = sum of that brand's `OrderShippingSnapshot.amount`. Override follows the same rule.
- Amounts are computed at claim time and **stored on the return**; finalize, resend and reconcile
  read the stored numbers, never recompute.

## Ledger

- New `LedgerService.recordReturnReversal(order, brandId, quote, refundId)`: one product
  `REFUND_REVERSAL` entry with exactly the quote's summed commissionNet/commissionVat/brandPayout;
  plus — if `shippingAmount > 0` — one shipping reversal via existing `reverseShippingEntries` at
  fraction 1 (ref suffix `:SHIPPING`). Brand debit order unchanged (pending → payout → debt).
  Idempotent on refund id.
- Ledger is written once, at finalize (refund created at Mollie). Not again on resend; not undone on
  FAILED (the return stays visible as FAILED in the overview until resent).
- Deleted: `LedgerService.recordRefund(Order, Long, BigDecimal, String)` (pro-rata per brand),
  `brandProductGross`, and `fractionOf` if no longer used. The order-wide
  `recordRefund(Order, BigDecimal, String)` (whole-order cancel) stays.
- Cancellation switches its amount to the quote; its ledger call
  `recordItemCancellationReversal` keeps working (amounts identical when k = n).

## Release: claim → refund → finalize (ReturnRefundService)

New module `order/ReturnRefundService` beside `OrderItemCancellationService`, same pattern.
Interface: `release(returnNumber, overrideReason, admin)`, `reconcile(returnNumber, dto, admin)`,
`onProviderRefundStatus(paymentTransactionId)`, `overview(state, pageable)`.
`OrderService.processRefund`, `processRefundByOrder`, `refundableTotal` and
`RefundPersistenceHelper` are deleted.

1. **Claim** (transaction, `orderLock.lockFresh(orderId)`):
   - status `RECEIVED`, or `APPROVED` with non-blank `overrideReason` (else 400/409), or
     `refundState = FAILED` (resend path: skips the cancellation check, quote and safety net,
     reuses the stored amounts and claim, increments `refund_resend_count`);
   - no existing claim on the return (else 409 with claim info; stuck → "reconcile");
   - no unsettled item-cancellation claim on the order (else 409);
   - build quote, then **safety net**: Σ refunded/claimed on the order (settled cancellations +
     returns with a claim, any state except released) + this total ≤ payment amount, else refuse
     and log `RETURN_REFUND_OVER_TOTAL`;
   - store `refundClaimKey = "return-refund-{returnNumber}-{uuid}"`, `refundItemsAmount`,
     `refundShippingAmount`, `refundAmount` (total), `refundClaimedAt`, `refundReleasedBy`,
     `refundOverrideReason`; commit.
2. **Refund** outside any transaction: `paymentProvider.refundPayment(...)` with idempotency key =
   claim key (resend: claim key + `-r{n}`), metadata `{claimKey, returnNumber}`.
3. **Finalize** (transaction): re-check claim not settled; refund id not already recorded
   (`isRefundAlreadyRecorded`); store `refundTransactionId`, `refundState = PENDING`; ledger
   (`recordReturnReversal`) unless resend; order status sync. No email yet.

Provider errors (classification shared with cancellation — `MolliePaymentService` maps codes):

| Provider outcome | Handling |
|---|---|
| 400, 404, 409, 422 (rejected) → `PaymentRejectedException` | release claim, return unchanged, 502/409 to admin with Mollie message |
| 429 → new `PaymentRetryLaterException` (subclass of rejected: nothing created) | release claim, log as retry-later (not a business rejection), 503 "try again" |
| 5xx, timeout, I/O, no refund id | claim stays (IN_PROGRESS); log `RETURN_REFUND_AMBIGUOUS`; tell admin to reconcile |
| success, finalize fails | log `RETURN_REFUND_RECORDING_FAILED` with refund id; claim stays; reconcile RECORD |

**Reconcile** `POST /admin/returns/{n}/reconcile-refund {action: RECORD|RELEASE, refundId?}` —
only for a stuck claim (older than `OrderItem.STUCK_AFTER`, no refund id).
RECORD: fetch the refund from the provider and verify it belongs to the order's payment and its amount
equals the stored claim total, and the id is not already recorded; then finalize.
RELEASE: clear the claim fields.

## Refund status tracking

- `PaymentProvider.getRefundStatus(paymentId, refundId) → RefundStatusResult(status, amount)`,
  status ∈ {QUEUED, PENDING, PROCESSING, REFUNDED, FAILED, CANCELED}; Mollie + Mock adapters
  (also used by RECORD verification).
- `MollieWebhookController`: before the existing `PAID → no-op` early return, call
  `returnRefundService.onProviderRefundStatus(id)` — for every return on that payment with
  `refundState = PENDING`, fetch its refund status:
  - REFUNDED → `refundState = REFUNDED`, `status = REFUNDED`, `refundedAt`, order status sync, payment
    status rule, publish `RefundCompletedEvent` (customer email) — idempotent;
  - FAILED / CANCELED → `refundState = FAILED`, log `RETURN_REFUND_FAILED`;
  - otherwise no-op. Errors are logged and don't break the payment webhook.
- Mock profile: `MockPaymentService` reports REFUNDED for created refunds and its existing refund
  webhook dispatch triggers the same path; tests can force FAILED.
- Local dev without a public webhook URL leaves refunds PENDING (known, see memory
  `project_mollie_localhost_webhook`).

## Payment status rule

`Payment.status = REFUNDED` only when Σ confirmed + pending refunds on the order (returns +
settled item cancellations) == payment amount; otherwise it stays `PAID`. Applied in return
finalize/status update and in item-cancellation finalize (which today sets REFUNDED on any cancel).

## Order status

`syncOrderStatus` unchanged in shape. Return `status` gains no new values (refund progress lives in
`refundState`); a return with `refundState` PENDING/FAILED still has status RECEIVED (or APPROVED for
override) until confirmed. REFUNDED order requires all returns REFUNDED and quantity-aware coverage.

## Schema — V37

`return_orders` add: `refund_claim_key varchar(100) unique`, `refund_state varchar(20)`,
`refund_items_amount numeric(10,2)`, `refund_shipping_amount numeric(10,2)`,
`refund_claimed_at timestamp`, `refund_released_by varchar(255)`,
`refund_override_reason varchar(500)`, `refund_transaction_id varchar(100)`,
`refund_resend_count int not null default 0`, `received_by varchar(10)`.
Existing refunded rows: `refund_state = 'REFUNDED'` backfilled where `status = 'REFUNDED'`.
`refund_amount` keeps meaning "total refunded".

## Response DTOs

Return in `OrderResponseDto` gains `refundState`, `refundItemsAmount`, `refundShippingAmount`,
`receivedBy`, `refundOverrideReason` (admin only), and per return item `quantityReturned` (already
present internally). Brand-scoped DTOs keep stripping refund transaction ids.

## Testing

Unit (pure): Refund Quote — even split, 3 × €100 remainder, sequential partials summing to the line,
multi-line, shipping included/excluded, legacy null refused, per-line sum assertion.

Integration (profiles `test` + `mock-payments`, Testcontainers):
- partial return 1 of 2, then the 2nd: amounts, stock, ledger sum == refunded, shipping only on 2nd;
- discounted order: refund == what was paid (bug 1);
- items with different commission rates on one brand: ledger reverses exactly the returned line (bug 2);
- multi-brand worked example (spec §3): per-brand isolation, Σ refunds == order total, payment
  REFUNDED only at the end;
- double release / concurrent release of two brands: second gets 409, safety net counts open claims;
- rejected / 429 / ambiguous provider outcomes; reconcile RECORD (with amount/payment mismatch
  rejected) and RELEASE (bug 3);
- override from APPROVED: reason required, no stock restore, late brand receive restores stock;
- brand receive: own brand only;
- webhook: PENDING → REFUNDED sends email once; FAILED → resend works without new ledger rows;
- over-quantity request 400; merge into REQUESTED increases quantity.
Existing per-item cancellation tests stay green.
