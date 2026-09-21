# Admin cancel of a paid order must refund — design

**Date:** 2026-09-21
**Status:** approved (grilled twice; all open questions settled)

## Problem

`PATCH /admin/orders/{id}/status?status=CANCELLED` reverses the ledger and restores stock for a
paid order, but never calls Mollie. The customer stays charged. Nothing in the system records that
a refund is owed, so the money only goes back if a human remembers.

The flow this serves: customer emails asking to cancel → admin cancels with a reason → money comes
back automatically, with an audit trail the admin, the brand and the settlement reports can read.

## Current state

Two admin cancel paths exist, and the weaker one is doing the paid cancels:

| | `POST /admin/orders/{id}/cancel` | `PATCH /admin/orders/{id}/status?status=CANCELLED` |
|---|---|---|
| Accepts | PENDING only | PAID + the 3 escalation statuses |
| Reason / note | yes (`CancelOrderDto`) | no |
| Records admin identity | yes (`cancelledByAdminEmail`) | no |
| Customer email | yes (`OrderCancelledEvent`) | **no event published** |
| Ledger reversal + stock restore | n/a (never paid) | yes |
| Mollie refund | n/a | **missing** |

Reused unchanged:

- `LedgerService.recordRefund(order, amount, externalRefundId)` — reverses product **and** shipping
  entries for every brand; idempotent per `externalRefundId`; reverses at
  `fraction = amount / order.getTotal()`.
- `restoreVariantStock(order)`, `releaseDiscountUsageOnce(order)`, `anyBrandHasShipped(order)`.
- `OrderCancelledEmailListener` — works, just never triggered on the paid path.

## Goal

One cancel path that, for a paid order, refunds the full captured amount at Mollie, records the
refund id, reverses the ledger under that id, restores stock, and tells the customer — or fails
cleanly, changing nothing.

## Non-goals

- Cancelling after a brand has shipped. Blocked today, stays blocked; returns/refunds cover it.
- Partial refunds. Full-order only; per-brand partials are the return flow.
- Moving cancelled orders' shipment rows out of `AWAITING_SHIPMENT` (handoff §1.2) — needs a new
  `ShipmentStatus` value and its own migration.
- Changing the global `PaymentException` → HTTP 400 mapping (checkout depends on it).

## Decisions

| # | Decision | Rationale |
|---|---|---|
| D1 | Refunds carry an idempotency key: `order-cancel-<orderNumber>`, and the existing late-payment auto-refund uses `late-refund-<orderNumber>` | The refund runs outside the DB transaction, so two concurrent cancels can both reach Mollie. Verified present in Mollie SDK 1.5.4: `CreateRefundRequestBuilder.idempotencyKey(String)`. Keyed on the **order number**, not the id: order numbers are unique forever, ids only within one database, so a restored or reseeded environment cannot collide. |
| D2 | Cancel accepts PENDING, PAID, SHIPPING_PROBLEM, AWAITING_ADMIN, MANUAL_REVIEW | Exactly the set cancellable today across both endpoints — nothing is lost. |
| D3 | An already-refunded payment (`PaymentStatus.REFUNDED`) is rejected, 409 | Refunding again would double-reverse the ledger. Rare enough to fail loudly rather than guess a remaining amount. |
| D4 | `PATCH .../status?status=CANCELLED` **delegates** to the cancel path for one release (synthetic `CancelReason.OTHER`, note `"via deprecated PATCH"`, deprecation warning logged), then is removed | Fixes the money bug on both paths the moment the backend deploys, so frontend deploy order stops mattering. A hard 409 would break admin cancels in the window between backend and frontend deploys; leaving PATCH unchanged would keep the money bug alive in that same window. |
| D5 | Brands see the cancellation in the dashboard; no brand email | Brands get no email for order events today except shipment ones. |
| D6 | Refund id denormalised onto `orders.refund_transaction_id`; ledger stays system of record | The admin dashboard shouldn't join the ledger to show a refund id. |
| D7 | Refund amount and ledger reversal both read `payment.getAmount()`; if it differs from `order.getTotal()`, refuse with 409 and a distinct log marker | The two are equal by construction (`Payment.amount` is set once from `order.getTotal()` at creation; nothing mutates a total afterwards; the webhook verifies the captured amount). A divergence therefore means data corruption. Passing a differing amount to `recordRefund` would silently reverse a *fraction* — and an amount above the total would over-reverse a brand's balance. Refuse instead of guessing. |

## Design

### Flow

`OrderService.cancelOrder(orderId, dto, admin)` becomes the single implementation and stops being
`@Transactional` — it orchestrates three phases, the same shape as `processRefund` and
`confirmPaymentByWebhook`:

**Phase 1 — validate (short read transaction).**
- Order exists; status is in D2's set, else 409.
- No brand has shipped, else 409 (existing message).
- Payment is not already REFUNDED, else 409 (D3).
- If paid: `payment.getAmount()` equals `order.getTotal()`, else 409 + `CANCEL_AMOUNT_MISMATCH` log (D7).
- Compute `wasPaid` = payment exists, status PAID, `paidAt` non-null.

**Phase 2 — refund (no transaction).** If `wasPaid`:

```java
paymentProvider.refundPayment(new RefundCommand(
        payment.getTransactionId(),
        payment.getAmount(),
        "Admin cancellation of order " + order.getOrderNumber(),
        "order-cancel-" + order.getOrderNumber()));
```

On failure throw `PaymentException` stating the order was **not** cancelled (surfaces as 400).
Nothing has been written yet, so there is no half-state and the admin simply retries — the
idempotency key makes the retry safe.

**Phase 3 — persist (one transaction, `findByIdForUpdate`).**
- Re-read the status under the lock and re-validate it against **the whole D2 set** — not merely
  "is it CANCELLED". Two distinct races end up here:
  - **Already CANCELLED** — a concurrent cancel won. Return 409 naming the winner's
    `refundTransactionId` and write nothing. The loser's own phase-2 call returned the *same* refund
    (same idempotency key), so there is no second refund to reconcile.
  - **Now PARTIALLY_SHIPPED / SHIPPED** — a brand confirmed shipment during the Mollie call
    (`confirmShipment` → `syncShipmentStatus` rewrites the order status, `OrderService.java:678`).
    The goods are physically gone, so the cancel must **not** complete: cancelling would restore
    stock for a parcel in transit and claw back payout for goods actually delivered. But the money
    *has* gone back, so record that truthfully and refuse the rest — set payment REFUNDED, store
    `refundTransactionId`, log `CANCEL_RACE_SHIPPED`, and return 409. No status change, no ledger
    reversal, no stock restore. One human decision (customer refunded and holding goods) is left
    flagged rather than silently resolved the wrong way.
- Otherwise: set CANCELLED, `cancellationReason`, `cancellationNote`, `cancelledByAdminEmail`,
  `refundTransactionId`; if paid — `restoreVariantStock`,
  `ledgerService.recordRefund(order, payment.getAmount(), refundId)`, payment → REFUNDED;
  `releaseDiscountUsageOnce`; publish `OrderCancelledEvent`.

Unpaid (PENDING) cancels skip phase 2 and the money parts of phase 3 — behaviour identical to today.

### Transaction boundary

The Mollie call must never run inside a DB transaction: it holds a pooled connection open across an
external call (handoff §5.4), which is why `processRefund` is deliberately non-transactional.

**This is the trap in D4.** `updateOrderStatus` is `@Transactional`, so having it call `cancelOrder`
would put the Mollie call inside its transaction. So `updateOrderStatus` becomes a non-transactional
dispatcher — the same restructuring already applied to `confirmPaymentByWebhook`:

```java
@PreAuthorize("hasRole('ADMIN')")   // stays on the public method
public OrderResponseDto updateOrderStatus(Long orderId, OrderStatus newStatus, User admin) {
    if (newStatus == OrderStatus.CANCELLED) {
        log.warn("DEPRECATED_CANCEL_PATH: order {} cancelled via PATCH status; use POST /admin/orders/{}/cancel",
                orderId, orderId);
        CancelOrderDto dto = new CancelOrderDto();
        dto.setReason(CancelReason.OTHER);
        dto.setNote("via deprecated PATCH");
        return cancelOrder(orderId, dto, admin);
    }
    return transactionTemplate.execute(status -> applyStatusTransition(orderId, newStatus));
}
```

The existing body moves verbatim into the private `applyStatusTransition`, minus its
`postPaymentCancel` branch (now dead — CANCELLED never reaches it).

**Signature change.** `updateOrderStatus` gains a `User admin` parameter, because the cancel path
records `cancelledByAdminEmail` and the status endpoint never had an admin to hand. `@PreAuthorize`
stays on the public method; the self-invoked `cancelOrder` is not re-checked by the proxy, which is
fine — the caller already proved ADMIN.

`admin` is never null and needs no `"system"` fallback: the only production caller is an HTTP
handler behind `@PreAuthorize("hasRole('ADMIN')")`, and `cancelOrder` already calls
`admin.getEmail()` unguarded today. A fallback would be unreachable code that invents an audit
identity.

Verified complete caller list (`grep -rn "updateOrderStatus("` over `backend/src`) — three sites, no
`@Scheduled` job and no service-account path:

- `AdminController:255` → add `@AuthenticationPrincipal User admin` to the handler (its own cancel
  endpoint already does exactly this) and pass it through.
- `DiscountPaymentFlowIntegrationTest:92` and `:145` call
  `orderService.updateOrderStatus(oid, CANCELLED)` directly inside `asAdmin(...)` — both need the
  admin `User` they already hold passed as the third argument.

### Ledger

`recordRefund` is called with the **real Mollie refund id** instead of today's synthetic
`"ADMIN_CANCEL_<id>"`, making the reversal idempotent against the actual refund and linking the
ledger row to Mollie for reconciliation. Settlement needs no change. Note: cancelling an order from
an already-settled month lands the reversal in the **current** month's figures — `SettlementRun`
freezes closed periods by design.

### Payment provider

`RefundCommand` gains a fourth component:

```java
public record RefundCommand(String paymentId, BigDecimal amount, String reason, String idempotencyKey) {}
```

- `MolliePaymentService.refundPayment` passes it through
  (`.idempotencyKey(command.idempotencyKey())`), skipping it when null.
- `MockPaymentService` keeps a key → `refundId` map and returns the **same** `refundId` for a key it
  has seen, so the duplicate-cancel test exercises replay rather than trusting Mollie. In-process is
  correct here: Mollie's state is not our state, and the app runs in one JVM per test.
- `MockPaymentStore` gains `reset()`, called from `AbstractDiscountIntegrationTest.cleanDatabase()`.
  Without it the store outlives `TRUNCATE ... RESTART IDENTITY`, and keys would collide across tests.
- The existing auto-refund in `confirmPaymentByWebhook` passes `late-refund-<orderNumber>`.

### Schema

`V34__order_refund_transaction_id.sql`:

```sql
ALTER TABLE orders ADD COLUMN refund_transaction_id VARCHAR(64);
```

Nullable; null for existing rows and for unpaid cancels. New migration — never edit an applied one.

### API

- `POST /admin/orders/{id}/cancel` — body unchanged (`CancelOrderDto`: `reason` required, `note`
  optional ≤500). Accepts the D2 status set. 200; 409 (wrong status / shipped / already refunded /
  amount mismatch / lost the race); 400 (refund failed at Mollie, order unchanged).
- `PATCH .../status?status=CANCELLED` — same behaviour via delegation, with `CancelReason.OTHER`.
  Deprecated; removed next release.

### DTO

`OrderResponseDto` gains `cancellationReason` and `refundTransactionId`, populated wherever
`molliePaymentId` already is. The brand-scoped DTO gets **only** `cancellationReason`.
`cancellationNote` is exposed nowhere — it carries the internal `REFUND_REQUIRED` /
`AUTO_REFUNDED` markers from the late-payment work.

### Email

`OrderCancelledEvent` gains nullable `refundAmount` + `currency`. The existing listener appends a
refund sentence when present and reads exactly as today when absent.

## Test plan

Integration tests (Testcontainers + `mock-payments`, extending `AbstractDiscountIntegrationTest`):

1. **Paid cancel** → refunded once at the provider; order CANCELLED with reason, admin email and
   refund id; payment REFUNDED; REFUND_REVERSAL recorded under the refund id; stock restored;
   discount usage released; customer email mentions the refund.
2. **Refund fails** → 400; order still PAID; no ledger entry; no stock change; no email.
3. **Unpaid PENDING cancel** → unchanged behaviour; no refund call; email without refund wording.
4. **Payment already REFUNDED** → 409, nothing written.
5. **A brand has shipped** → 409 (existing rule).
6. **`PATCH status=CANCELLED`** → delegates: refunds, order CANCELLED with `OTHER` and
   `"via deprecated PATCH"`.
7. **Amount mismatch** (`payments.amount` tampered via JDBC) → 409, nothing written, no refund call.
8. **Concurrent double cancel** (two threads, as `ConcurrentWebhookIdempotencyTest` does) → exactly
   one refund at the provider; loser gets 409 naming the refund id; one REFUND_REVERSAL.
9. **Brand-scoped DTO** exposes `cancellationReason`, never `cancellationNote`.
10. **Brand ships during the refund** (Mockito `doAnswer` on `refundPayment` confirms the brand's
    shipment mid-call) → 409; order still PARTIALLY_SHIPPED/SHIPPED; no ledger reversal; stock NOT
    restored; payment REFUNDED with the refund id stored.

### Existing tests

The seven `PATCH status=CANCELLED` call sites across six classes now exercise the delegation path,
so none need migrating this release. Each was checked against the new behaviour:

| Test | Assertion | Under delegation |
|---|---|---|
| `ShippingLedgerIntegrationTest:72` | `external_reference_id LIKE '%:SHIPPING'` | Passes — prefix changes from `ADMIN_CANCEL_<id>` to the real refund id, the suffix match is unaffected |
| `ReconciliationServiceShippingTest:75` | `brandPending == 0.00` | Passes — same full reversal, `fraction = payment.amount / order.total = 1` |
| `SettlementAccountingReportIntegrationTest:189,225` | `refundAmount == 123.99`, payout nets to 0 | Passes — reversal amount is unchanged, only its reference id differs |
| `ReturnLifecyclePhase3Test:310` | discount usage released | Passes — `releaseDiscountUsageOnce` still runs; the comment "via the postPaymentCancel path" goes stale and should be updated |
| `AccountErasureIntegrationTest:56` | erase after cancel | Passes — now also issues a mock refund, which the erase flow does not touch |
| `LatePaymentAutoRefundIntegrationTest:105` | status CANCELLED, `paidAt` non-null | Passes — additionally the payment becomes REFUNDED, which this test does not assert on |

Every one of these cancels a *paid* order, so each now triggers a mock refund. `MockPaymentService`
finds the payment because `createPayment` registered it, and `reset()` only runs in `@AfterEach`.

## Follow-up (next release)

- Remove CANCELLED handling from `PATCH .../status`; migrate the seven call sites and the admin UI
  to `POST /cancel`.
- Frontend: send a reason, show reason + refund id to admins, show reason to brands.

## Risks

- **Brand ships during the refund.** Window is the Mollie call's duration. Phase 3 detects it via
  the status re-check and refuses the cancel, recording the refund against the payment and logging
  `CANCEL_RACE_SHIPPED`. The customer then holds both goods and their money until an admin resolves
  it — deliberately a human decision, not an automatic one.
- **Refund succeeds, phase 3 fails.** Money is back, order still reads PAID. Retrying the cancel
  reuses the idempotency key, gets the same refund, and completes phase 3. A distinct log marker
  makes the window findable.
- **Synthetic reason on the PATCH path.** For one release, cancels made through the old endpoint
  carry `OTHER` rather than a real reason — deliberate, and the deprecation log makes the callers
  visible.
