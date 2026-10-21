# Partial Returns with Exact, Tracked Refunds — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Customers/admins can return k of n units of an order line; the refund is always the exact amount paid (after discount) plus the brand's shipping once everything from that brand is back, goes out only after brand confirmation + admin release (or an admin override with reason), is guarded by a DB claim before Mollie is called, and is only "done" once Mollie confirms it.

**Architecture:** A pure `RefundQuote` module computes every money-back amount and its ledger split (shared by item cancellation and returns). A new `ReturnRefundService` owns the return refund lifecycle (claim → Mollie → finalize, reconcile, provider status updates, overview), replacing `OrderService.processRefund` + `RefundPersistenceHelper`. `PaymentProvider` gains a refund-status read; the Mollie webhook routes refund status changes to the service.

**Tech Stack:** Java 21, Spring Boot 4.0.5, Spring Data JPA, Flyway (PostgreSQL), Lombok, Mollie Java SDK 1.5.4, JUnit 5 + AssertJ + Mockito, Testcontainers (`jdbc:tc:` URL).

**Spec:** `docs/superpowers/specs/2026-10-07-partial-returns-exact-refunds-design.md` — read it before your task.

## Global Constraints

- All paths below are relative to `backend/`. Run tests from `backend/` with `./mvnw test -Dtest=<Class>` (Windows: `mvnw.cmd`). Do **not** run `mvnw clean` (OneDrive file locks — mass `NoClassDefFoundError` means lock interference, not a regression; rerun without clean).
- **No git commands.** The user stages and commits everything themselves. There are no commit steps in this plan.
- **Never edit an applied Flyway migration.** The only new migration is `V37__return_refund_claims.sql`.
- Integration tests: `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `@ActiveProfiles({"test","mock-payments"})`, no `@Transactional` on tests, extend the existing bases (they truncate after each test and reset `MockPaymentStore`). `IllegalStateException → 409`, `IllegalArgumentException → 400`, `SecurityException → 403`.
- Money is `BigDecimal` scale 2, `RoundingMode.HALF_UP`. Euros, never cents.
- Every implementer works under the `ponytail` skill (full): shortest working diff, reuse before writing, no new dependencies.
- TDD: write the failing test first, see it fail for the right reason, then implement.
- Customer-facing/admin UI names (German): Retoure genehmigen (approve), Ware erhalten (receive), Erstattung freigeben (release refund). Endpoint names are English as given per task.

## Review Focus

1. A whole-order return request (`orderItemId: null`) after a partial return must only take the **remaining** units of each line, and lines with 0 remaining are skipped, not an error — test in Task 3.
2. Two brands' returns released back-to-back where the first is still an open claim: the safety net must count the open claim — test in Task 7 (`safetyNetCountsOpenClaims`).
3. A webhook for a payment that is already `PAID` must still process refund status changes (today it early-returns) — test in Task 8.
4. A refund confirmed twice by the webhook must not send the customer email twice or re-run status side effects — test in Task 8.
5. An order with an item cancellation (shipping already reversed when the brand was emptied) followed by a return from the other brand: shipping must not be refunded twice — test in Task 7 (`shippingNotRefundedTwiceAfterCancellation`).

---

## File Structure

| File | Responsibility |
|---|---|
| Create `src/main/java/com/enunas/backend/order/RefundQuote.java` | Pure money module: line parts, shipping line, totals, rounding rule |
| Create `src/main/resources/db/migration/V37__return_refund_claims.sql` | New `return_orders` columns + backfill |
| Create `src/main/java/com/enunas/backend/order/ReturnRefundState.java` | `PENDING, REFUNDED, FAILED` |
| Create `src/main/java/com/enunas/backend/order/ReceivedBy.java` | `BRAND, ADMIN` |
| Create `src/main/java/com/enunas/backend/order/ReturnRefundService.java` | release / reconcile / onProviderRefundStatus / overview |
| Create `src/main/java/com/enunas/backend/order/dto/ReleaseRefundDto.java`, `ReconcileReturnRefundDto.java`, `RefundOverviewRowDto.java` | Request/response DTOs |
| Create `src/main/java/com/enunas/backend/admin/AdminRefundController.java` | `/admin/returns/{n}/release-refund`, `/reconcile-refund`, `GET /admin/refunds` |
| Create `src/main/java/com/enunas/backend/exception/PaymentRetryLaterException.java` | 429 from provider |
| Create `src/main/java/com/enunas/backend/payment/RefundStatusResult.java` | provider refund status read |
| Modify `order/ReturnOrder.java`, `order/ReturnItem.java` (none), `order/ReturnOrderRepository.java`, `order/ReturnCoverage.java`, `order/OrderService.java`, `order/OrderItemCancellationService.java`, `order/BrandPartnerReturnController.java`, `order/dto/ReturnRequestDto.java`, `order/dto/ReturnSummaryDto.java`, `ledger/LedgerService.java`, `ledger/LedgerRepository.java`, `payment/PaymentProvider.java`, `payment/RefundCommand.java`, `payment/MolliePaymentService.java`, `payment/mock/MockPaymentService.java`, `payment/mock/MockPaymentStore.java`, `payment/MollieWebhookController.java`, `admin/AdminController.java`, `exception/GlobalExceptionHandler.java` | as per tasks |
| Delete `order/RefundPersistenceHelper.java` | replaced by ReturnRefundService |

---

### Task 1: RefundQuote (pure money module)

**Files:**
- Create: `src/main/java/com/enunas/backend/order/RefundQuote.java`
- Test: `src/test/java/com/enunas/backend/order/RefundQuoteTest.java`

**Interfaces:**
- Produces:
  ```java
  public record RefundQuote(List<Line> lines, BigDecimal shippingAmount) {
      public record LineInput(Long orderItemId, int quantity, BigDecimal customerGross,
                              BigDecimal commissionNet, BigDecimal commissionVat, BigDecimal brandPayout,
                              int alreadyRefundedUnits, int units) {
          public static LineInput of(OrderItem item, int alreadyRefundedUnits, int units) { ... }
      }
      public record Line(Long orderItemId, int units, BigDecimal customerAmount,
                         BigDecimal commissionNet, BigDecimal commissionVat, BigDecimal brandPayout) {}
      public static RefundQuote of(List<LineInput> inputs, BigDecimal shippingAmount);
      public BigDecimal itemsAmount();     // Σ line.customerAmount
      public BigDecimal total();           // itemsAmount + shippingAmount
      public BigDecimal commissionNet();   // Σ
      public BigDecimal commissionVat();   // Σ
      public BigDecimal brandPayout();     // Σ
  }
  ```
  `LineInput.of(item, …)` reads `item.getQuantity()`, `getCustomerGrossAfterDiscount()`, `getCommissionNet()`, `getCommissionVat()`, `getBrandPayoutAmount()` and throws `IllegalStateException("Item <id> predates per-item pricing (legacy order) …")` when `customerGrossAfterDiscount` is null.

- [ ] **Step 1: Write the failing tests**

```java
package com.enunas.backend.order;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefundQuoteTest {

    private static BigDecimal e(String v) { return new BigDecimal(v); }

    /** 3 units, paid €300; commission net 45.00, vat 8.55, payout 246.45 (sums to 300.00). */
    private static RefundQuote.LineInput line(int already, int units) {
        return new RefundQuote.LineInput(1L, 3, e("300.00"), e("45.00"), e("8.55"), e("246.45"), already, units);
    }

    @Test
    void unitsBeforeTheLastAreRoundedUnitShares() {
        RefundQuote q = RefundQuote.of(List.of(line(0, 1)), BigDecimal.ZERO);
        assertThat(q.itemsAmount()).isEqualByComparingTo("100.00");
        assertThat(q.commissionNet()).isEqualByComparingTo("15.00");
        assertThat(q.commissionVat()).isEqualByComparingTo("2.85");
        assertThat(q.brandPayout()).isEqualByComparingTo("82.15");
    }

    @Test
    void lastUnitTakesTheRemainder() {
        RefundQuote.LineInput odd = new RefundQuote.LineInput(1L, 3, e("100.00"), e("15.00"), e("2.85"), e("82.15"), 0, 0);
        BigDecimal first = RefundQuote.of(List.of(withUnits(odd, 0, 1)), BigDecimal.ZERO).itemsAmount();
        BigDecimal second = RefundQuote.of(List.of(withUnits(odd, 1, 1)), BigDecimal.ZERO).itemsAmount();
        BigDecimal last = RefundQuote.of(List.of(withUnits(odd, 2, 1)), BigDecimal.ZERO).itemsAmount();
        assertThat(first).isEqualByComparingTo("33.33");
        assertThat(second).isEqualByComparingTo("33.33");
        assertThat(last).isEqualByComparingTo("33.34");
        assertThat(first.add(second).add(last)).isEqualByComparingTo("100.00");
    }

    @Test
    void sequentialPartialsSumExactlyToTheLineForEveryField() {
        RefundQuote a = RefundQuote.of(List.of(line(0, 2)), BigDecimal.ZERO);
        RefundQuote b = RefundQuote.of(List.of(line(2, 1)), BigDecimal.ZERO);
        assertThat(a.itemsAmount().add(b.itemsAmount())).isEqualByComparingTo("300.00");
        assertThat(a.commissionNet().add(b.commissionNet())).isEqualByComparingTo("45.00");
        assertThat(a.commissionVat().add(b.commissionVat())).isEqualByComparingTo("8.55");
        assertThat(a.brandPayout().add(b.brandPayout())).isEqualByComparingTo("246.45");
    }

    @Test
    void everyLinePartSumsToItsCustomerAmount() {
        RefundQuote q = RefundQuote.of(List.of(line(0, 1)), BigDecimal.ZERO);
        RefundQuote.Line l = q.lines().get(0);
        assertThat(l.commissionNet().add(l.commissionVat()).add(l.brandPayout()))
                .isEqualByComparingTo(l.customerAmount());
    }

    @Test
    void multipleLinesAndShippingAddUp() {
        RefundQuote.LineInput other = new RefundQuote.LineInput(2L, 1, e("27.00"), e("4.05"), e("0.77"), e("22.18"), 0, 1);
        RefundQuote q = RefundQuote.of(List.of(line(0, 3), other), e("4.99"));
        assertThat(q.itemsAmount()).isEqualByComparingTo("327.00");
        assertThat(q.shippingAmount()).isEqualByComparingTo("4.99");
        assertThat(q.total()).isEqualByComparingTo("331.99");
    }

    @Test
    void lineWhoseSnapshotDoesNotSumIsRefused() {
        RefundQuote.LineInput broken = new RefundQuote.LineInput(9L, 1, e("10.00"), e("1.00"), e("0.19"), e("5.00"), 0, 1);
        assertThatThrownBy(() -> RefundQuote.of(List.of(broken), BigDecimal.ZERO))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("9");
    }

    @Test
    void moreUnitsThanRemainingIsRefused() {
        assertThatThrownBy(() -> RefundQuote.of(List.of(line(2, 2)), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RefundQuote.LineInput withUnits(RefundQuote.LineInput in, int already, int units) {
        return new RefundQuote.LineInput(in.orderItemId(), in.quantity(), in.customerGross(), in.commissionNet(),
                in.commissionVat(), in.brandPayout(), already, units);
    }
}
```

Also add a legacy test (null customer gross refused) using `LineInput.of` with an `OrderItem` built via `OrderItem.builder()`/setters with `quantity(1)` and `customerGrossAfterDiscount(null)`: expect `IllegalStateException` containing `"legacy"`.

- [ ] **Step 2: Run to verify failure**

Run: `./mvnw test -Dtest=RefundQuoteTest` → FAIL (compilation: `RefundQuote` missing).

- [ ] **Step 3: Implement**

```java
package com.enunas.backend.order;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.function.Function;

/**
 * The single place that computes money going back to a customer for order lines, and how it splits
 * into commission net / commission VAT / brand payout for the ledger. Pure — no DB, no Spring.
 * Rule per field: unit share = round(field ÷ quantity, 2); the call that brings a line to "all units
 * refunded" takes field − unit share × already refunded, so repeated partials sum exactly to the line.
 * Brand payout is derived (customer − commission net − commission VAT) so every part sums exactly.
 */
public record RefundQuote(List<Line> lines, BigDecimal shippingAmount) {

    public record LineInput(Long orderItemId, int quantity, BigDecimal customerGross,
                            BigDecimal commissionNet, BigDecimal commissionVat, BigDecimal brandPayout,
                            int alreadyRefundedUnits, int units) {
        public static LineInput of(OrderItem item, int alreadyRefundedUnits, int units) {
            if (item.getCustomerGrossAfterDiscount() == null) {
                throw new IllegalStateException("Item " + item.getId()
                        + " predates per-item pricing (legacy order) — refund it manually.");
            }
            return new LineInput(item.getId(), item.getQuantity(), item.getCustomerGrossAfterDiscount(),
                    nz(item.getCommissionNet()), nz(item.getCommissionVat()), nz(item.getBrandPayoutAmount()),
                    alreadyRefundedUnits, units);
        }
    }

    public record Line(Long orderItemId, int units, BigDecimal customerAmount,
                       BigDecimal commissionNet, BigDecimal commissionVat, BigDecimal brandPayout) {}

    public static RefundQuote of(List<LineInput> inputs, BigDecimal shippingAmount) {
        return new RefundQuote(inputs.stream().map(RefundQuote::line).toList(),
                shippingAmount.setScale(2, RoundingMode.HALF_UP));
    }

    private static Line line(LineInput in) {
        if (in.units() <= 0 || in.alreadyRefundedUnits() + in.units() > in.quantity()) {
            throw new IllegalArgumentException("Item " + in.orderItemId() + ": cannot refund " + in.units()
                    + " of " + in.quantity() + " units (" + in.alreadyRefundedUnits() + " already refunded).");
        }
        if (in.commissionNet().add(in.commissionVat()).add(in.brandPayout()).compareTo(in.customerGross()) != 0) {
            throw new IllegalStateException("Item " + in.orderItemId()
                    + " money snapshot does not sum to what the customer paid — refund it manually.");
        }
        boolean last = in.alreadyRefundedUnits() + in.units() == in.quantity();
        BigDecimal customer = part(in.customerGross(), in, last);
        BigDecimal net = part(in.commissionNet(), in, last);
        BigDecimal vat = part(in.commissionVat(), in, last);
        return new Line(in.orderItemId(), in.units(), customer, net, vat, customer.subtract(net).subtract(vat));
    }

    private static BigDecimal part(BigDecimal lineField, LineInput in, boolean last) {
        BigDecimal unit = lineField.divide(BigDecimal.valueOf(in.quantity()), 2, RoundingMode.HALF_UP);
        return last
                ? lineField.subtract(unit.multiply(BigDecimal.valueOf(in.alreadyRefundedUnits())))
                : unit.multiply(BigDecimal.valueOf(in.units()));
    }

    public BigDecimal itemsAmount()   { return sum(Line::customerAmount); }
    public BigDecimal commissionNet() { return sum(Line::commissionNet); }
    public BigDecimal commissionVat() { return sum(Line::commissionVat); }
    public BigDecimal brandPayout()   { return sum(Line::brandPayout); }
    public BigDecimal total()         { return itemsAmount().add(shippingAmount); }

    private BigDecimal sum(Function<Line, BigDecimal> f) {
        return lines.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./mvnw test -Dtest=RefundQuoteTest` → PASS.

---

### Task 2: Item cancellation computes its refund through RefundQuote

**Files:**
- Modify: `src/main/java/com/enunas/backend/order/OrderItemCancellationService.java` (`refundAmountOf`, lines ~267-279)
- Test: existing `AdminItemCancellationIntegrationTest`, `AdminItemCancellationFailureIntegrationTest`, `AdminItemCancellationConcurrencyIntegrationTest`, `LedgerItemCancellationIntegrationTest`, `ItemCancellationDtoIntegrationTest` must stay green.

**Interfaces:**
- Consumes: `RefundQuote.of`, `RefundQuote.LineInput.of(item, 0, item.getQuantity())`.

- [ ] **Step 1: Write a failing test** in `src/test/java/com/enunas/backend/order/integration/AdminItemCancellationIntegrationTest.java`: a cancellation whose item snapshot doesn't sum (corrupt `brand_payout` via `jdbc.update("UPDATE order_items SET brand_payout = brand_payout - 1, brand_payout_amount = brand_payout_amount - 1 WHERE id = ?", f.a1())`) is refused with 409 **before** any refund (`mockPaymentStore` shows no refund; item not cancelled).

```java
@Test
void snapshotThatDoesNotSumIsRefusedBeforeMoneyMoves() {
    Fixture f = paidTwoBrandOrder(null);
    jdbc.update("UPDATE order_items SET brand_payout_amount = brand_payout_amount - 1 WHERE id = ?", f.a1());
    ResponseEntity<Map> res = cancelItems(f.adminToken(), f.orderId(), List.of(f.a1()));
    assertThat(res.getStatusCode().value()).isEqualTo(409);
    assertThat(itemRow(f.a1()).get("cancelled_at")).isNull();
    assertThat(paymentStatus(f.orderId())).isEqualTo("PAID");
}
```

- [ ] **Step 2: Run** `./mvnw test -Dtest=AdminItemCancellationIntegrationTest#snapshotThatDoesNotSumIsRefusedBeforeMoneyMoves` → FAIL (today it refunds).

- [ ] **Step 3: Implement.** In `claim(...)`, compute the quote **before** `claimCancellation` writes (so a refusal leaves no claim), and pass `quote.total()` into the `Claim` record. Replace `refundAmountOf` with:

```java
/** Σ what the customer paid for the items, plus the brand's shipping when the claim empties it (D11/D12). */
private BigDecimal refundAmountOf(Long orderId, List<OrderItem> items, boolean includesShipping) {
    BigDecimal shipping = BigDecimal.ZERO;
    if (includesShipping) {
        Long brandId = items.get(0).getBrandId();
        shipping = orderShippingSnapshotRepository.findByOrderIdOrderByIdAsc(orderId).stream()
                .filter(s -> brandId.equals(s.getBrandPartnerId()))
                .map(OrderShippingSnapshot::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    return RefundQuote.of(items.stream()
            .map(i -> RefundQuote.LineInput.of(i, 0, i.getQuantity())).toList(), shipping).total();
}
```
Callers: in `claim` call `refundAmountOf(orderId, targets, includesShipping)` before the claim loop; in `finalizeClaim` call `refundAmountOf(orderId, items, Boolean.TRUE.equals(items.get(0).getCancellationIncludesShipping()))`. The existing legacy check stays (it runs first and keeps its message).

- [ ] **Step 4: Run** `./mvnw test -Dtest='AdminItemCancellation*IntegrationTest,LedgerItemCancellationIntegrationTest,ItemCancellationDtoIntegrationTest'` → all PASS.

---

### Task 3: Schema + partial return request (quantity, remaining units, merge, coverage)

**Files:**
- Create: `src/main/resources/db/migration/V37__return_refund_claims.sql`
- Create: `src/main/java/com/enunas/backend/order/ReturnRefundState.java`, `src/main/java/com/enunas/backend/order/ReceivedBy.java`
- Modify: `src/main/java/com/enunas/backend/order/ReturnOrder.java` (new fields), `order/dto/ReturnRequestDto.java`, `order/ReturnOrderRepository.java` (replace `findReturnedOrderItemIds`), `order/OrderService.java` (`doRequestReturn` lines ~549-634), `order/ReturnCoverage.java`, `order/dto/ReturnSummaryDto.java`
- Test: `src/test/java/com/enunas/backend/order/integration/AbstractReturnRefundIntegrationTest.java` (new base), `src/test/java/com/enunas/backend/order/integration/PartialReturnRequestIntegrationTest.java`, `src/test/java/com/enunas/backend/order/ReturnDtoValidationTest.java` (extend)

**Interfaces:**
- Produces (used by Tasks 4–10):
  - `ReturnOrder` fields (Lombok getters/setters): `String refundClaimKey`, `ReturnRefundState refundState` (`@Enumerated(STRING)`), `BigDecimal refundItemsAmount`, `BigDecimal refundShippingAmount`, `LocalDateTime refundClaimedAt`, `String refundReleasedBy`, `String refundOverrideReason`, `String refundTransactionId`, `int refundResendCount`, `ReceivedBy receivedBy` (`@Enumerated(STRING)`). Existing `refundAmount` = claim total.
  - `enum ReturnRefundState { PENDING, REFUNDED, FAILED }`, `enum ReceivedBy { BRAND, ADMIN }`.
  - `ReturnOrderRepository.returnedQuantityByItem(Long orderId)` → `List<Object[]>` (`orderItemId`, `Σ quantityReturned`) — wrap in `OrderService` as `Map<Long,Integer> returnedUnits(Long orderId)`.
  - `ReturnRequestDto(Long orderItemId, @Positive Integer quantity, @NotNull ReturnReason reason, @Size(max=500) @NoHtml String description)` with `@AssertTrue(message = "quantity requires orderItemId") boolean isQuantityWithItem()` → `quantity == null || orderItemId != null`.
  - `ReturnSummaryDto` adds `List<ReturnItemDto> items` (`orderItemId`, `quantityReturned`), `refundState`, `refundItemsAmount`, `refundShippingAmount`, `receivedBy`, `refundOverrideReason`.
  - Test base `AbstractReturnRefundIntegrationTest` (extends `AbstractItemCancellationIntegrationTest`): `Fixture deliveredOrder(String discountCode)` — brand A: a1 = listing €119.00 **× 2**, a2 €59.50 × 1; brand B: b1 €100.00 × 1 (record `ReturnFixture(long orderId, long brandAId, long brandBId, long a1, long a2, long b1, String adminToken, String customerToken, String brandAToken, String brandBToken)`); helpers `requestReturn(token, orderId, Long itemId, Integer qty)`, `returnNumberOf(orderId, brandId)`, `approve(adminToken, returnNumber)`, `adminReceive(adminToken, returnNumber)`, `returnRow(returnNumber)`, `quantityOnReturn(returnNumber, itemId)`.

- [ ] **Step 1: Migration**

```sql
-- V37: refund claim + tracking state on returns (spec 2026-10-07-partial-returns-exact-refunds-design).
ALTER TABLE return_orders
    ADD COLUMN refund_claim_key        varchar(100),
    ADD COLUMN refund_state            varchar(20),
    ADD COLUMN refund_items_amount     numeric(10,2),
    ADD COLUMN refund_shipping_amount  numeric(10,2),
    ADD COLUMN refund_claimed_at       timestamp,
    ADD COLUMN refund_released_by      varchar(255),
    ADD COLUMN refund_override_reason  varchar(500),
    ADD COLUMN refund_transaction_id   varchar(100),
    ADD COLUMN refund_resend_count     integer NOT NULL DEFAULT 0,
    ADD COLUMN received_by             varchar(10);

ALTER TABLE return_orders ADD CONSTRAINT uq_return_orders_refund_claim_key UNIQUE (refund_claim_key);

UPDATE return_orders SET refund_state = 'REFUNDED' WHERE status = 'REFUNDED';
```
Add the matching entity fields to `ReturnOrder` (`@Column(name = "...")` as named above, `@Enumerated(EnumType.STRING)` for the two enums, `@Column(nullable = false) private int refundResendCount;`).

- [ ] **Step 2: Write the failing tests** — `PartialReturnRequestIntegrationTest extends AbstractReturnRefundIntegrationTest`:

```java
@Test
void returnsOneOfTwoUnits() {
    ReturnFixture f = deliveredOrder(null);
    assertThat(requestReturn(f.customerToken(), f.orderId(), f.a1(), 1).getStatusCode().value()).isEqualTo(200);
    assertThat(quantityOnReturn(returnNumberOf(f.orderId(), f.brandAId()), f.a1())).isEqualTo(1);
}

@Test
void secondRequestWhileRequestedMergesIntoTheSameItem() {
    ReturnFixture f = deliveredOrder(null);
    requestReturn(f.customerToken(), f.orderId(), f.a1(), 1);
    requestReturn(f.customerToken(), f.orderId(), f.a1(), 1);
    String rn = returnNumberOf(f.orderId(), f.brandAId());
    assertThat(quantityOnReturn(rn, f.a1())).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM return_items WHERE order_item_id = ?", Integer.class, f.a1()))
            .isEqualTo(1);
}

@Test
void moreThanRemainingIs400() {
    ReturnFixture f = deliveredOrder(null);
    requestReturn(f.customerToken(), f.orderId(), f.a1(), 1);
    assertThat(requestReturn(f.customerToken(), f.orderId(), f.a1(), 2).getStatusCode().value()).isEqualTo(400);
}

@Test
void wholeOrderRequestTakesOnlyRemainingUnitsAndSkipsFullyReturnedLines() {
    ReturnFixture f = deliveredOrder(null);
    requestReturn(f.customerToken(), f.orderId(), f.a1(), 1);
    requestReturn(f.customerToken(), f.orderId(), f.a2(), null);           // a2 fully on return
    assertThat(requestReturn(f.customerToken(), f.orderId(), null, null).getStatusCode().value()).isEqualTo(200);
    String rnA = returnNumberOf(f.orderId(), f.brandAId());
    assertThat(quantityOnReturn(rnA, f.a1())).isEqualTo(2);
    assertThat(quantityOnReturn(rnA, f.a2())).isEqualTo(1);
    assertThat(quantityOnReturn(returnNumberOf(f.orderId(), f.brandBId()), f.b1())).isEqualTo(1);
}

@Test
void singleLineWithNothingRemainingIs409() {
    ReturnFixture f = deliveredOrder(null);
    requestReturn(f.customerToken(), f.orderId(), f.a2(), null);
    assertThat(requestReturn(f.customerToken(), f.orderId(), f.a2(), null).getStatusCode().value()).isEqualTo(409);
}

@Test
void quantityOmittedMeansAllRemainingUnitsOfTheLine() {
    ReturnFixture f = deliveredOrder(null);
    requestReturn(f.customerToken(), f.orderId(), f.a1(), null);
    assertThat(quantityOnReturn(returnNumberOf(f.orderId(), f.brandAId()), f.a1())).isEqualTo(2);
}
```
In `ReturnDtoValidationTest` add: `quantity` 0 → violation; `quantity` without `orderItemId` → violation.

Base-class helpers (put in `AbstractReturnRefundIntegrationTest`; add `@MockitoBean EmailService emailService` if the parent hierarchy doesn't already mock it — shipping sends mail inline):

```java
protected ReturnFixture deliveredOrder(String discountCode) {
    seedCustomer(); seedAdmin();
    BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
    BrandFixture b = seedBrand("BrandB", "brand-b", "0.18");
    long la1 = seedListing(a.brand(), a.user(), "119.00", 5);
    long la2 = seedListing(a.brand(), a.user(), "59.50", 5);
    long lb1 = seedListing(b.brand(), b.user(), "100.00", 5);
    String admin = login("admin@it.local", "Admin123!");
    if (discountCode != null) {
        assertThat(createAdminDiscount(admin, Map.of("code", discountCode, "percent", 0.10)).getStatusCode().value()).isEqualTo(201);
    }
    String customer = login("customer@it.local", "Customer123!");
    long oid = orderId(postOrder(customer, discountCode, List.of(item(la1, 2), item(la2, 1), item(lb1, 1))));
    confirmPaid(oid);
    String brandA = login("brand-a@it.local", "Brand123!"), brandB = login("brand-b@it.local", "Brand123!");
    assertThat(ship(brandA, oid).getStatusCode().value()).isEqualTo(200);
    assertThat(ship(brandB, oid).getStatusCode().value()).isEqualTo(200);
    assertThat(adminSetStatus(admin, oid, "DELIVERED").getStatusCode().value()).isEqualTo(200);
    return new ReturnFixture(oid, a.brand().getId(), b.brand().getId(),
            itemIdOf(oid, la1), itemIdOf(oid, la2), itemIdOf(oid, lb1), admin, customer, brandA, brandB);
}

protected ResponseEntity<Map> requestReturn(String token, long orderId, Long itemId, Integer qty) {
    Map<String, Object> body = new HashMap<>();
    body.put("reason", "NO_LONGER_WANTED");
    if (itemId != null) body.put("orderItemId", itemId);
    if (qty != null) body.put("quantity", qty);
    return rest.exchange("/orders/" + orderId + "/return", HttpMethod.POST, new HttpEntity<>(body, auth(token)), Map.class);
}

protected String returnNumberOf(long orderId, long brandId) {
    return jdbc.queryForObject("SELECT return_number FROM return_orders WHERE order_id = ? AND brand_id = ? "
            + "ORDER BY id DESC LIMIT 1", String.class, orderId, brandId);
}

protected int quantityOnReturn(String returnNumber, long itemId) {
    return jdbc.queryForObject("SELECT ri.quantity_returned FROM return_items ri JOIN return_orders r "
            + "ON r.id = ri.return_order_id WHERE r.return_number = ? AND ri.order_item_id = ?",
            Integer.class, returnNumber, itemId);
}

protected ResponseEntity<Map> approve(String adminToken, String rn) {
    return rest.exchange("/admin/returns/" + rn + "/approve", HttpMethod.POST, new HttpEntity<>(null, auth(adminToken)), Map.class);
}

protected ResponseEntity<Map> adminReceive(String adminToken, String rn) {
    return rest.exchange("/admin/returns/" + rn + "/receive", HttpMethod.POST, new HttpEntity<>(null, auth(adminToken)), Map.class);
}

protected Map<String, Object> returnRow(String rn) {
    return jdbc.queryForMap("SELECT * FROM return_orders WHERE return_number = ?", rn);
}
```
(Check the column/table names against `V0.0.1__baseline_schema.sql`/later migrations: `return_items.quantity_returned`, `return_orders.brand_id`, `order_items.listing_id_snapshot`.)

- [ ] **Step 3: Run** `./mvnw test -Dtest='PartialReturnRequestIntegrationTest,ReturnDtoValidationTest'` → FAIL.

- [ ] **Step 4: Implement**

`ReturnOrderRepository` — replace `findReturnedOrderItemIds` with:
```java
/** Σ units already on a return (any return, any status), per order item of this order. */
@Query("SELECT ri.orderItem.id, SUM(ri.quantityReturned) FROM ReturnItem ri "
        + "WHERE ri.returnOrder.order.id = :orderId GROUP BY ri.orderItem.id")
List<Object[]> returnedQuantityByItem(@Param("orderId") Long orderId);
```
`OrderService` helper:
```java
private Map<Long, Integer> returnedUnits(Long orderId) {
    Map<Long, Integer> m = new HashMap<>();
    for (Object[] row : returnOrderRepository.returnedQuantityByItem(orderId)) {
        m.put((Long) row[0], ((Number) row[1]).intValue());
    }
    return m;
}
```
`doRequestReturn` — replace the "which items / already returned" block with units per item:
```java
Map<Long, Integer> returned = returnedUnits(orderId);
Map<OrderItem, Integer> unitsToReturn = new LinkedHashMap<>();
if (dto.orderItemId() == null) {
    for (OrderItem i : order.getItems()) {
        int remaining = i.isCancelled() ? 0 : i.getQuantity() - returned.getOrDefault(i.getId(), 0);
        if (remaining > 0) unitsToReturn.put(i, remaining);
    }
} else {
    OrderItem item = /* existing lookup + cancelled check, unchanged */;
    int remaining = item.getQuantity() - returned.getOrDefault(item.getId(), 0);
    if (remaining <= 0) {
        throw new IllegalStateException("All units of item " + item.getId() + " are already on a return.");
    }
    int units = dto.quantity() != null ? dto.quantity() : remaining;
    if (units > remaining) {
        throw new IllegalArgumentException("Only " + remaining + " unit(s) of item " + item.getId() + " can still be returned.");
    }
    unitsToReturn.put(item, units);
}
if (unitsToReturn.isEmpty()) {
    throw new IllegalStateException("All requested items on order " + order.getOrderNumber() + " have already been returned");
}
```
Group `unitsToReturn` by brand exactly as `itemsByBrand` does today. When adding to the return:
```java
for (Map.Entry<OrderItem, Integer> e : brandUnits.entrySet()) {
    returnOrder.getItems().stream()
            .filter(ri -> ri.getOrderItem().getId().equals(e.getKey().getId()))
            .findFirst()
            .ifPresentOrElse(
                    ri -> ri.setQuantityReturned(ri.getQuantityReturned() + e.getValue()),
                    () -> returnOrder.addItem(ReturnItem.builder().orderItem(e.getKey()).quantityReturned(e.getValue()).build()));
}
```
`ReturnCoverage.allItemsCovered` — quantity-aware, counting only REFUNDED returns:
```java
static boolean allItemsCovered(Order order, List<ReturnOrder> returns) {
    Map<Long, Integer> refundedUnits = returns.stream()
            .filter(r -> r.getStatus() == ReturnStatus.REFUNDED)
            .flatMap(r -> r.getItems().stream())
            .collect(Collectors.groupingBy(ri -> ri.getOrderItem().getId(),
                    Collectors.summingInt(ReturnItem::getQuantityReturned)));
    return order.getItems().stream().allMatch(i -> i.isCancellationSettled()
            || refundedUnits.getOrDefault(i.getId(), 0) >= i.getQuantity());
}
```
`ReturnSummaryDto`: add `items` (nested `@Getter @Builder` record-like `ReturnItemDto(Long orderItemId, int quantityReturned)`) and the refund fields listed in Interfaces; keep `orderItemIds` for compatibility.

- [ ] **Step 5: Run** `./mvnw test -Dtest='PartialReturnRequestIntegrationTest,ReturnDtoValidationTest,MultiBrandReturnTest,ReturnLifecyclePhase3Test,ReturnsWithCancelledItemsIntegrationTest'` → PASS (the last three exercise existing return requests; refund endpoints in them still use the old path until Task 7).

---

### Task 4: Brand confirms goods received

**Files:**
- Modify: `src/main/java/com/enunas/backend/order/OrderService.java` (`receiveReturn`), `order/BrandPartnerReturnController.java`, `admin/AdminController.java` (admin receive passes `ReceivedBy.ADMIN`)
- Test: `src/test/java/com/enunas/backend/order/integration/BrandReceiveReturnIntegrationTest.java`

**Interfaces:**
- Produces: `OrderService.receiveReturn(String returnNumber, ReceivedBy by, User actor)` (admin: `actor` may be the admin; brand: ownership checked); endpoint `POST /brand/returns/{returnNumber}/receive`. Brand helper in test base: `brandReceive(brandToken, rn)`.
- Rule: allowed when `status == APPROVED` (→ `RECEIVED`, `receivedAt`, `receivedBy`, stock restored by `quantityReturned`; this also covers an override refund still in flight), **or** when `status == REFUNDED && receivedAt == null` (late arrival after override: stock restored, `receivedAt`/`receivedBy` set, status stays `REFUNDED`). Anything else → 409.

- [ ] **Step 1: Failing tests**

```java
@Test
void brandConfirmsReceiptOfItsOwnReturn() {
    ReturnFixture f = deliveredOrder(null);
    requestReturn(f.customerToken(), f.orderId(), f.a1(), 1);
    String rn = returnNumberOf(f.orderId(), f.brandAId());
    approve(f.adminToken(), rn);
    int before = stockOfItem(f.a1());
    assertThat(brandReceive(f.brandAToken(), rn).getStatusCode().value()).isEqualTo(200);
    assertThat(returnRow(rn).get("status")).isEqualTo("RECEIVED");
    assertThat(returnRow(rn).get("received_by")).isEqualTo("BRAND");
    assertThat(stockOfItem(f.a1())).isEqualTo(before + 1);
}

@Test
void otherBrandCannotConfirm() {
    ReturnFixture f = deliveredOrder(null);
    requestReturn(f.customerToken(), f.orderId(), f.a1(), 1);
    String rn = returnNumberOf(f.orderId(), f.brandAId());
    approve(f.adminToken(), rn);
    assertThat(brandReceive(f.brandBToken(), rn).getStatusCode().value()).isEqualTo(403);
}

@Test
void adminFallbackIsRecordedAsAdmin() {
    ReturnFixture f = deliveredOrder(null);
    requestReturn(f.customerToken(), f.orderId(), f.a1(), 1);
    String rn = returnNumberOf(f.orderId(), f.brandAId());
    approve(f.adminToken(), rn);
    adminReceive(f.adminToken(), rn);
    assertThat(returnRow(rn).get("received_by")).isEqualTo("ADMIN");
}

@Test
void cannotConfirmBeforeApproval() {
    ReturnFixture f = deliveredOrder(null);
    requestReturn(f.customerToken(), f.orderId(), f.a1(), 1);
    assertThat(brandReceive(f.brandAToken(), returnNumberOf(f.orderId(), f.brandAId())).getStatusCode().value()).isEqualTo(409);
}
```
(The late-arrival-after-override case is tested in Task 7 once override exists.)

- [ ] **Step 2: Run** `./mvnw test -Dtest=BrandReceiveReturnIntegrationTest` → FAIL (404 on brand endpoint).

- [ ] **Step 3: Implement.** `OrderService.receiveReturn(String returnNumber, ReceivedBy by, User actor)`:
```java
@Transactional
public OrderResponseDto receiveReturn(String returnNumber, ReceivedBy by, User actor) {
    ReturnOrder returnOrder = findReturnByNumber(returnNumber);
    if (by == ReceivedBy.BRAND && (returnOrder.getBrand() == null || returnOrder.getBrand().getUser() == null
            || !returnOrder.getBrand().getUser().getId().equals(actor.getId()))) {
        throw new SecurityException("This return does not belong to your brand");
    }
    boolean lateAfterRefund = returnOrder.getStatus() == ReturnStatus.REFUNDED && returnOrder.getReceivedAt() == null;
    if (returnOrder.getStatus() != ReturnStatus.APPROVED && !lateAfterRefund) {
        throw new IllegalStateException("Can only receive a return in APPROVED status. Current: " + returnOrder.getStatus());
    }
    for (ReturnItem item : returnOrder.getItems()) {
        productVariantRepository.restoreStock(item.getOrderItem().getVariant().getId(), item.getQuantityReturned());
    }
    if (!lateAfterRefund) returnOrder.setStatus(ReturnStatus.RECEIVED);
    returnOrder.setReceivedAt(LocalDateTime.now());
    returnOrder.setReceivedBy(by);
    returnOrderRepository.save(returnOrder);
    log.info("Return {} received ({}) — variant stock restored", returnNumber, by);
    return toDto(orderRepository.save(syncOrderStatus(returnOrder.getOrder())));
}
```
Note: once Task 7's override leaves a return `APPROVED` with a refund in flight, the `APPROVED` branch covers that too (status → `RECEIVED`). `@PreAuthorize` moves to the controllers (admin controller is already admin-only; brand controller is `hasRole('BRAND_PARTNER')`). Update `receiveReturnByOrder` (deprecated alias) to call `receiveReturn(rn, ReceivedBy.ADMIN, null)`. Brand controller:
```java
@PostMapping("/{returnNumber}/receive")
public ResponseEntity<OrderResponseDto> receive(@PathVariable String returnNumber, @AuthenticationPrincipal User brandPartner) {
    OrderResponseDto dto = orderService.receiveReturn(returnNumber, ReceivedBy.BRAND, brandPartner);
    return ResponseEntity.ok(dto.toBuilder().refundTransactionId(null).build());
}
```
Admin controller `/returns/{returnNumber}/receive` → `orderService.receiveReturn(returnNumber, ReceivedBy.ADMIN, admin)` (add `@AuthenticationPrincipal User admin`).

- [ ] **Step 4: Run** `./mvnw test -Dtest='BrandReceiveReturnIntegrationTest,ReturnLifecyclePhase3Test,DeprecatedOrderScopedReturnShimsIntegrationTest'` → PASS.

---

### Task 5: Payment provider: refund status, metadata, 429

**Files:**
- Create: `src/main/java/com/enunas/backend/payment/RefundStatusResult.java`, `src/main/java/com/enunas/backend/exception/PaymentRetryLaterException.java`
- Modify: `payment/PaymentProvider.java`, `payment/RefundCommand.java`, `payment/MolliePaymentService.java`, `payment/mock/MockPaymentService.java`, `payment/mock/MockPaymentStore.java`, all `new RefundCommand(...)` call sites (`OrderService` ×3, `OrderItemCancellationService`), `exception/GlobalExceptionHandler.java`
- Test: `src/test/java/com/enunas/backend/payment/mock/MockPaymentRefundStatusTest.java` (plain unit test, no Spring)

**Interfaces:**
- Produces:
  ```java
  public record RefundCommand(String paymentId, BigDecimal amount, String reason, String idempotencyKey,
                              Map<String, Object> metadata) {
      public RefundCommand(String paymentId, BigDecimal amount, String reason, String idempotencyKey) {
          this(paymentId, amount, reason, idempotencyKey, Map.of());
      }
  }
  public record RefundStatusResult(Status status, BigDecimal amount, String paymentId) {
      public enum Status { QUEUED, PENDING, PROCESSING, REFUNDED, FAILED, CANCELED }
      public boolean isFinalSuccess() { return status == Status.REFUNDED; }
      public boolean isFinalFailure() { return status == Status.FAILED || status == Status.CANCELED; }
  }
  // PaymentProvider:
  RefundStatusResult getRefundStatus(String paymentId, String refundId);
  // exception — nothing created at the provider, but not a business rejection:
  public class PaymentRetryLaterException extends PaymentRejectedException { ctor(String, Throwable) }
  // MockPaymentStore test hooks:
  public void forceNextRefundStatus(RefundStatusResult.Status status);   // applies to the next created refund
  public void setRefundStatus(String refundId, RefundStatusResult.Status status);
  public RefundStatusResult refundStatus(String refundId);               // null if unknown
  ```
- Mock semantics: each newly minted refund is stored with `(paymentId, amount, status)`; default status `REFUNDED` unless `forceNextRefundStatus` was set (consumed once). `reset()` clears them. `getRefundStatus` throws `PaymentRejectedException` for an unknown id (mirrors Mollie 404).
- Mollie semantics: `getRefundStatus` → `mollieClient.refunds().get().request(GetRefundRequest.builder().paymentId(p).refundId(r).build()).call().entityRefundResponse()`, map `EntityRefundResponseStatus.value()` (`"queued"`, …) to `Status.valueOf(value.toUpperCase())`, amount from `amount().value()`, paymentId from `paymentId().orElse(p)`. `refundPayment` adds `.metadata(Metadata.of(command.metadata()))` when non-empty. Error mapping in both methods: `ClientError` with code 429 → `PaymentRetryLaterException`; other 4xx → `PaymentRejectedException`; everything else → `PaymentException`.
- `GlobalExceptionHandler`: `PaymentRetryLaterException` → 503 with message "The payment provider is busy — try again in a minute." (register before the `PaymentException` handler).

- [ ] **Step 1: Failing test**

```java
class MockPaymentRefundStatusTest {
    private final MockPaymentStore store = new MockPaymentStore();
    private final MockPaymentService service = new MockPaymentService(store, mock(MockWebhookDispatcher.class));

    @BeforeEach void seed() { store.save(new MockPayment("pay_1", new BigDecimal("50.00"))); }

    @Test void createdRefundsReportRefundedWithAmountAndPayment() {
        String id = service.refundPayment(new RefundCommand("pay_1", new BigDecimal("10.00"), "r", "k1")).refundId();
        RefundStatusResult s = service.getRefundStatus("pay_1", id);
        assertThat(s.status()).isEqualTo(RefundStatusResult.Status.REFUNDED);
        assertThat(s.amount()).isEqualByComparingTo("10.00");
        assertThat(s.paymentId()).isEqualTo("pay_1");
    }

    @Test void forcedStatusAppliesToTheNextRefundOnly() {
        store.forceNextRefundStatus(RefundStatusResult.Status.FAILED);
        String a = service.refundPayment(new RefundCommand("pay_1", new BigDecimal("1.00"), "r", "k1")).refundId();
        String b = service.refundPayment(new RefundCommand("pay_1", new BigDecimal("1.00"), "r", "k2")).refundId();
        assertThat(service.getRefundStatus("pay_1", a).status()).isEqualTo(RefundStatusResult.Status.FAILED);
        assertThat(service.getRefundStatus("pay_1", b).status()).isEqualTo(RefundStatusResult.Status.REFUNDED);
    }

    @Test void unknownRefundIsRejected() {
        assertThatThrownBy(() -> service.getRefundStatus("pay_1", "ref_nope")).isInstanceOf(PaymentRejectedException.class);
    }
}
```
(Check `MockPayment`'s constructor; use what exists.)

- [ ] **Step 2: Run** `./mvnw test -Dtest=MockPaymentRefundStatusTest` → FAIL (compilation).
- [ ] **Step 3: Implement** per Interfaces. Keep the four-arg `RefundCommand` constructor so existing call sites compile unchanged.
- [ ] **Step 4: Run** `./mvnw test -Dtest='MockPaymentRefundStatusTest,AdminItemCancellationFailureIntegrationTest,LatePaymentAutoRefundIntegrationTest'` → PASS.

---

### Task 6: Ledger books a quote exactly

**Files:**
- Modify: `src/main/java/com/enunas/backend/ledger/LedgerService.java`, `ledger/LedgerRepository.java`
- Test: `src/test/java/com/enunas/backend/ledger/LedgerServiceReturnReversalTest.java` (follow the style/mocks of `LedgerServiceRefundSplitTest`), then delete `LedgerServiceRefundSplitTest`'s cases for the per-brand pro-rata method (keep any order-wide `recordRefund(Order, BigDecimal, String)` cases).

**Interfaces:**
- Consumes: `RefundQuote` (Task 1).
- Produces:
  ```java
  /** Exactly the quote: one product REFUND_REVERSAL (+ one shipping reversal when quote.shippingAmount() > 0). Idempotent on refundId. */
  @Transactional public void recordReturnReversal(Order order, Long brandId, RefundQuote quote, String refundId);
  /** True once any shipping REFUND_REVERSAL exists for this brand on this order (any claim, cancellation or return). */
  public boolean isShippingReversed(Long orderId, Long brandId);
  ```
  `LedgerRepository`:
  ```java
  @Query("SELECT COUNT(le) > 0 FROM LedgerEntry le WHERE le.orderId = :orderId AND le.brandPartnerId = :brandId "
       + "AND le.entryType = com.enunas.backend.ledger.LedgerEntryType.REFUND_REVERSAL "
       + "AND le.externalReferenceId LIKE '%:SHIPPING'")
  boolean existsShippingReversal(@Param("orderId") Long orderId, @Param("brandId") Long brandId);
  ```
- Deletes: `recordRefund(Order, Long, BigDecimal, String)` (per-brand pro-rata) and `brandProductGross`. Keep `fractionOf` (used by the order-wide `recordRefund`). `RefundPersistenceHelper` still calls the deleted method → switch it in this task to `recordReturnReversal` with a quote built from the return's items (`LineInput.of(ri.getOrderItem(), 0, ri.getQuantityReturned())`, shipping 0) so the build stays green until Task 7 deletes the helper.

- [ ] **Step 1: Failing tests** (Mockito, like `LedgerServiceRefundSplitTest`): build a `RefundQuote` with two lines (net 15.00/2.85/82.15 and 4.05/0.77/22.18) and shipping 4.99; call `recordReturnReversal(order, 7L, quote, "re_1")`; capture saved `LedgerEntry`s:
  - product entry: `commissionNet == -19.05`, `commissionVat == -3.62`, `brandPayout == -104.33`, `totalAmount == -127.00`, `entryType REFUND_REVERSAL`, `externalReferenceId "re_1"`;
  - shipping entry: `brandPayout == -4.99`, `externalReferenceId "re_1:SHIPPING"` (requires `findActiveShippingEntriesByOrderAndBrand` stub returning one 4.99 entry);
  - brand economics debited by 104.33 + 4.99;
  - second call with same `refundId` (stub `existsByExternalReferenceIdAndEntryType("re_1", REFUND_REVERSAL)` → true) saves nothing.
  - quote with shipping 0 → no shipping entry.
- [ ] **Step 2: Run** `./mvnw test -Dtest=LedgerServiceReturnReversalTest` → FAIL.
- [ ] **Step 3: Implement**

```java
@Transactional
public void recordReturnReversal(Order order, Long brandId, RefundQuote quote, String refundId) {
    if (isRefundAlreadyRecorded(refundId)) {
        log.warn("LedgerService: REFUND_REVERSAL already recorded for externalRefundId={}; skipping", refundId);
        return;
    }
    List<LedgerEntry> originals = ledgerRepository.findActivePaymentEntriesByOrderAndBrand(order.getId(), brandId);
    BigDecimal net = quote.commissionNet(), vat = quote.commissionVat(), payout = quote.brandPayout();
    ledgerRepository.save(LedgerEntry.builder()
            .orderId(order.getId())
            .orderItemId(quote.lines().get(0).orderItemId())
            .brandPartnerId(brandId)
            .totalAmount(net.add(vat).add(payout).negate())
            .platformFee(net.negate())
            .brandPayout(payout.negate())
            .commissionNet(net.negate())
            .commissionVat(vat.negate())
            .commissionRate(originals.isEmpty() ? resolveBrandRate(brandId) : originals.get(0).getCommissionRate())
            .currency(order.getCurrency())
            .entryType(LedgerEntryType.REFUND_REVERSAL)
            .status(LedgerEntryStatus.REVERSED)
            .payoutEligibleAt(LocalDateTime.now())
            .movedToAvailable(false)
            .reversalOfEntryId(originals.isEmpty() ? null : originals.get(0).getId())
            .externalReferenceId(refundId)
            .build());
    applyBrandDebit(brandId, payout);
    if (quote.shippingAmount().signum() > 0) {
        reverseShippingEntries(order, brandId, BigDecimal.ONE, refundId);
    }
    log.info("LedgerService: reversed return exactly for orderId={} brandId={} payout={} shipping={}",
            order.getId(), brandId, payout, quote.shippingAmount());
}

public boolean isShippingReversed(Long orderId, Long brandId) {
    return ledgerRepository.existsShippingReversal(orderId, brandId);
}
```
Update the javadoc of the order-wide `recordRefund` that pointed to the deleted method.
- [ ] **Step 4: Run** `./mvnw test -Dtest='LedgerService*Test,ReturnLifecyclePhase3Test,MultiBrandReturnTest'` → PASS.

---

### Task 7: ReturnRefundService — release (claim → refund → finalize), override, safety net, confirmation

**Files:**
- Create: `src/main/java/com/enunas/backend/order/ReturnRefundService.java`, `order/dto/ReleaseRefundDto.java`, `src/main/java/com/enunas/backend/admin/AdminRefundController.java`
- Modify: `order/OrderService.java` (delete `processRefund`, `processRefundByOrder`, `refundableTotal`; make `syncOrderStatus` package-private), `admin/AdminController.java` (delete `/returns/{n}/refund` and deprecated `/orders/{id}/return/refund`), `order/ReturnOrderRepository.java`, `order/OrderItemCancellationService.java` (payment status rule)
- Delete: `order/RefundPersistenceHelper.java`
- Migrate tests: every test calling `/admin/returns/{n}/refund` or `/admin/orders/{id}/return/refund` (`ReturnLifecyclePhase3Test`, `MultiBrandReturnTest`, `RefundStaleReadIntegrationTest`, `DeprecatedOrderScopedReturnShimsIntegrationTest`, `RefundOrderItemImageSnapshotIntegrationTest`, `ReturnsWithCancelledItemsIntegrationTest`, `DiscountPaymentFlowIntegrationTest`, `SettlementAccountingReportIntegrationTest`, `LedgerServiceSnapshotTest` — grep for `refund"` / `processRefund` / `refundAmount=`) to `releaseRefund(...)` + `confirmRefunds(orderId)`; remove assertions on custom `refundAmount`; delete `RefundStaleReadIntegrationTest` (its race is now covered by the claim — replace with the double-release test below) and the deprecated refund shim test case.
- Test: `src/test/java/com/enunas/backend/order/integration/ReturnRefundReleaseIntegrationTest.java`

**Interfaces:**
- Consumes: Tasks 1, 3, 5, 6.
- Produces:
  ```java
  public record ReleaseRefundDto(@Size(max = 500) @NoHtml String overrideReason) {}
  // ReturnRefundService
  public OrderResponseDto release(String returnNumber, String overrideReason, User admin);
  public void onProviderRefundStatus(String paymentTransactionId);   // confirms/fails PENDING refunds (webhook in Task 8)
  // AdminRefundController (class-level @PreAuthorize("hasRole('ADMIN')"), @RequestMapping("/admin"))
  POST /admin/returns/{returnNumber}/release-refund   body ReleaseRefundDto (optional) → OrderResponseDto
  // ReturnOrderRepository
  @Query("SELECT r FROM ReturnOrder r WHERE r.order.id = :orderId AND r.refundClaimKey IS NOT NULL")
  List<ReturnOrder> findClaimedByOrderId(@Param("orderId") Long orderId);
  // test base helpers
  ResponseEntity<Map> releaseRefund(String adminToken, String rn, String overrideReason);  // POST release-refund, body {"overrideReason": …} only when non-null
  void confirmRefunds(long orderId);   // returnRefundService.onProviderRefundStatus(transactionIdOf(orderId))
  String transactionIdOf(long orderId);   // SELECT transaction_id FROM payments WHERE order_id = ?
  /** request (itemId/qty may be null = whole order) + approve + brand receive; returns the brand's return number.
   *  With itemId == null, receives every brand return the request touched and returns brand A's. */
  String receivedReturn(ReturnFixture f, Long itemId, Integer qty);
  ResponseEntity<Map> brandReceive(String brandToken, String rn);   // POST /brand/returns/{rn}/receive (defined in Task 4)
  // Task 8 adds: void postMollieWebhook(String paymentId)  — POST /webhooks/mollie, form-encoded id=<paymentId>
  ```
- Behaviour (spec "Release"):
  - Claim tx (`transactionTemplate`, `orderLock.lockFresh(orderId)`): resolve return; if `refundState == FAILED` → resend path; else require no claim (`refundClaimKey == null`, else 409 "refund already in progress/released"), status `RECEIVED`, or `APPROVED` with non-blank `overrideReason` (APPROVED without reason → 409 "Ware noch nicht bestätigt — override needs a reason"); refuse when `order.hasUnsettledCancellationClaim()` (409).
  - Quote: units per line from the return's items; `alreadyRefundedUnits` = Σ `quantityReturned` of that line on **other** returns of the order that have a claim (any state). Shipping: included iff `!ledgerService.isShippingReversed(orderId, brandId)` and every non-cancelled unit of the brand is on some return of the order (`returnedUnits(orderId)` covers `item.getQuantity()` for every `order.activeItemsOf(brandId)`), amount = Σ that brand's `OrderShippingSnapshot.amount`.
  - Safety net: `claimedSoFar` = Σ `refundAmount` of `findClaimedByOrderId(orderId)` excluding this return (any refund state — open claims count) **+** cancellation money = Σ `customerGrossAfterDiscount` of every item with `isCancelled()` (claimed or settled) **+** Σ shipping snapshot amount of each brand that has any item with `cancellationIncludesShipping == TRUE`. If `claimedSoFar + quote.total() > payment.getAmount()` → log `RETURN_REFUND_OVER_TOTAL` and throw `IllegalStateException` (409). Resend skips this check (its amount is already counted).
  - Store claim: `refundClaimKey = "return-refund-" + rn + "-" + UUID.randomUUID()`, `refundItemsAmount`, `refundShippingAmount`, `refundAmount = quote.total()`, `refundClaimedAt`, `refundReleasedBy = admin.getEmail()`, `refundOverrideReason` (trimmed, null when RECEIVED). Commit.
  - Refund (no tx): `paymentProvider.refundPayment(new RefundCommand(txId, total, "Refund for return " + rn + " (order " + on + ")", key, Map.of("claimKey", claimKey, "returnNumber", rn)))` where `key = claimKey` (resend: `claimKey + "-r" + refundResendCount`). Error handling copies `OrderItemCancellationService.refund` (rejected → release claim (non-resend) / keep FAILED (resend) and throw `PaymentException`; `PaymentRetryLaterException` → same release but log at INFO `RETURN_REFUND_RETRY_LATER` and rethrow it (→ 503); other → log `RETURN_REFUND_AMBIGUOUS`, keep claim, throw `IllegalStateException` telling the admin to reconcile).
  - Finalize tx: lock order, reload return; if `refundTransactionId != null && !resend` → 409; `ledgerService.isRefundAlreadyRecorded(refundId)` → 409; set `refundTransactionId`, `refundState = PENDING`; non-resend: `ledgerService.recordReturnReversal(order, brandId, quote rebuilt from stored claim lines … )` — **store-and-read rule**: rebuild the quote in finalize from the same inputs (units + `alreadyRefundedUnits` computed again under the lock are identical because claims are serialized by the lock) and assert `quote.total().compareTo(returnOrder.getRefundAmount()) == 0` (else `IllegalStateException`, log `RETURN_REFUND_RECORDING_FAILED`). Resend: increment nothing more (already incremented at claim), no ledger. Then `orderService.syncOrderStatus(order)`. On exception after a successful refund → log `RETURN_REFUND_RECORDING_FAILED` with refund id and rethrow.
  - `onProviderRefundStatus(txId)`: for every return of the order of that payment with `refundState == PENDING`: `getRefundStatus(txId, refundTransactionId)`; `REFUNDED` → in a tx under the order lock (re-check still PENDING): `refundState = REFUNDED`, `status = REFUNDED`, `refundedAt = now`, `syncOrderStatus`, payment status rule, `discountService.releaseUsageOnce(order)` when order now `REFUNDED`, publish `RefundCompletedEvent(buyerEmail, orderNumber, rn, brandName, refundAmount, currency)`; `FAILED/CANCELED` → `refundState = FAILED`, log `RETURN_REFUND_FAILED`; other → no-op. Each return handled independently; exceptions logged, not rethrown.
  - Payment status rule (shared private helper, also applied in `OrderItemCancellationService.finalizeClaim` instead of the unconditional `REFUNDED`): `REFUNDED` iff Σ refunded/pending (returns with `refundState` PENDING or REFUNDED → `refundAmount`; settled cancellations → their amounts as in the safety net) `>= payment.getAmount()`, else leave as is. Put it in one package-private class `order/PaymentRefundStatus.java` with `static void apply(Payment p, BigDecimal refundedSoFar)` and a `refundedSoFar(Order, List<ReturnOrder>, Set<ReturnRefundState>, OrderShippingSnapshotRepository)` helper used by both safety net and payment rule (claimed returns for the net; PENDING/REFUNDED for payment).

- [ ] **Step 1: Failing tests** (`ReturnRefundReleaseIntegrationTest extends AbstractReturnRefundIntegrationTest`)

```java
@Test
void releaseAfterBrandConfirmationRefundsExactlyWhatWasPaid_discounted() {
    ReturnFixture f = deliveredOrder("TEN");                          // 10% → a1 line paid 214.20 for 2 units
    requestReturn(f.customerToken(), f.orderId(), f.a1(), 1);
    String rn = returnNumberOf(f.orderId(), f.brandAId());
    approve(f.adminToken(), rn);
    brandReceive(f.brandAToken(), rn);
    assertThat(releaseRefund(f.adminToken(), rn, null).getStatusCode().value()).isEqualTo(200);
    BigDecimal paidPerUnit = itemMoney(f.a1(), "customer_gross_after_discount").divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
    assertThat((BigDecimal) returnRow(rn).get("refund_amount")).isEqualByComparingTo(paidPerUnit);   // bug 1
    assertThat((BigDecimal) returnRow(rn).get("refund_shipping_amount")).isEqualByComparingTo("0.00");
    assertThat(returnRow(rn).get("refund_state")).isEqualTo("PENDING");
    assertThat(returnRow(rn).get("status")).isEqualTo("RECEIVED");
    confirmRefunds(f.orderId());
    assertThat(returnRow(rn).get("status")).isEqualTo("REFUNDED");
    assertThat(paymentStatus(f.orderId())).isEqualTo("PAID");
}

@Test
void ledgerReversesExactlyTheReturnedUnits() {                           // bug 2
    ReturnFixture f = deliveredOrder(null);
    String rn = receivedReturn(f, f.a1(), 1);                           // helper: request+approve+brandReceive
    releaseRefund(f.adminToken(), rn, null);
    Map<String, Object> rev = reversals(f.orderId()).get(0);
    BigDecimal half = itemMoney(f.a1(), "commission_net").divide(new BigDecimal("2"), 2, RoundingMode.HALF_UP);
    assertThat((BigDecimal) rev.get("commission_net")).isEqualByComparingTo(half.negate());
    BigDecimal sum = ((BigDecimal) rev.get("commission_net")).add((BigDecimal) rev.get("commission_vat"))
            .add((BigDecimal) rev.get("brand_payout"));
    assertThat(sum.negate()).isEqualByComparingTo((BigDecimal) returnRow(rn).get("refund_amount"));
}

@Test
void multiBrandWorkedExampleAddsUpToTheOrderTotal() {
    ReturnFixture f = deliveredOrder(null);
    String a1 = receivedReturn(f, f.a1(), 1);
    releaseRefund(f.adminToken(), a1, null); confirmRefunds(f.orderId());
    String b = receivedReturn(f, f.b1(), 1);
    releaseRefund(f.adminToken(), b, null); confirmRefunds(f.orderId());
    assertThat((BigDecimal) returnRow(b).get("refund_shipping_amount")).isEqualByComparingTo(shippingOf(f.orderId(), f.brandBId()));
    String a2 = receivedReturn(f, null, null);                          // remaining: 1× a1, 1× a2
    releaseRefund(f.adminToken(), a2, null); confirmRefunds(f.orderId());
    BigDecimal total = jdbc.queryForObject("SELECT SUM(refund_amount) FROM return_orders WHERE order_id = ?", BigDecimal.class, f.orderId());
    assertThat(total).isEqualByComparingTo((BigDecimal) orderRow(f.orderId()).get("total"));
    assertThat(paymentStatus(f.orderId())).isEqualTo("REFUNDED");
    assertThat(orderStatus(f.orderId())).isEqualTo("REFUNDED");
    // ledger per brand: brand B reversals only reference brand B
    assertThat(reversals(f.orderId())).allSatisfy(r -> assertThat(r.get("brand_partner_id")).isNotNull());
}

@Test
void doubleReleaseIs409AndCallsMollieOnce() {                         // bug 3
    ReturnFixture f = deliveredOrder(null);
    String rn = receivedReturn(f, f.a1(), 1);
    assertThat(releaseRefund(f.adminToken(), rn, null).getStatusCode().value()).isEqualTo(200);
    assertThat(releaseRefund(f.adminToken(), rn, null).getStatusCode().value()).isEqualTo(409);
    assertThat(reversals(f.orderId())).hasSize(1);
}

@Test
void safetyNetCountsOpenClaims() {
    ReturnFixture f = deliveredOrder(null);
    String rn = receivedReturn(f, f.a1(), 1);
    // an open (unfinished) claim on another return that already covers more than the rest of the payment
    String other = receivedReturn(f, f.b1(), 1);
    jdbc.update("UPDATE return_orders SET refund_claim_key = 'open-claim', refund_amount = ? WHERE return_number = ?",
            ((BigDecimal) orderRow(f.orderId()).get("total")).subtract(new BigDecimal("1.00")), other);
    assertThat(releaseRefund(f.adminToken(), rn, null).getStatusCode().value()).isEqualTo(409);
    assertThat(returnRow(rn).get("refund_claim_key")).isNull();
}

@Test
void overrideFromApprovedNeedsAReasonAndDoesNotRestoreStock() {
    ReturnFixture f = deliveredOrder(null);
    requestReturn(f.customerToken(), f.orderId(), f.a1(), 1);
    String rn = returnNumberOf(f.orderId(), f.brandAId());
    approve(f.adminToken(), rn);
    int stock = stockOfItem(f.a1());
    assertThat(releaseRefund(f.adminToken(), rn, null).getStatusCode().value()).isEqualTo(409);
    assertThat(releaseRefund(f.adminToken(), rn, "Kunde hat Einlieferungsbeleg").getStatusCode().value()).isEqualTo(200);
    assertThat(stockOfItem(f.a1())).isEqualTo(stock);
    assertThat(returnRow(rn).get("refund_override_reason")).isEqualTo("Kunde hat Einlieferungsbeleg");
    confirmRefunds(f.orderId());
    assertThat(brandReceive(f.brandAToken(), rn).getStatusCode().value()).isEqualTo(200);   // late arrival
    assertThat(stockOfItem(f.a1())).isEqualTo(stock + 1);
    assertThat(returnRow(rn).get("status")).isEqualTo("REFUNDED");
}

@Test
void rejectedByProviderReleasesTheClaim() {
    ReturnFixture f = deliveredOrder(null);
    String rn = receivedReturn(f, f.a1(), 1);
    doThrow(new PaymentRejectedException("amount too high", null)).when(paymentProvider).refundPayment(any());
    assertThat(releaseRefund(f.adminToken(), rn, null).getStatusCode().value()).isEqualTo(502);  // match PaymentException's existing status in GlobalExceptionHandler
    assertThat(returnRow(rn).get("refund_claim_key")).isNull();
}

@Test
void rateLimitedReleasesTheClaimWith503() {
    ReturnFixture f = deliveredOrder(null);
    String rn = receivedReturn(f, f.a1(), 1);
    doThrow(new PaymentRetryLaterException("429", null)).when(paymentProvider).refundPayment(any());
    assertThat(releaseRefund(f.adminToken(), rn, null).getStatusCode().value()).isEqualTo(503);
    assertThat(returnRow(rn).get("refund_claim_key")).isNull();
}

@Test
void ambiguousKeepsTheClaim() {
    ReturnFixture f = deliveredOrder(null);
    String rn = receivedReturn(f, f.a1(), 1);
    doThrow(new PaymentException("read timed out")).when(paymentProvider).refundPayment(any());
    assertThat(releaseRefund(f.adminToken(), rn, null).getStatusCode().value()).isEqualTo(409);
    assertThat(returnRow(rn).get("refund_claim_key")).isNotNull();
    assertThat(returnRow(rn).get("refund_transaction_id")).isNull();
}

@Test
void shippingNotRefundedTwiceAfterCancellation() {
    // brand A emptied by cancellation before shipment is not possible after delivery; instead seed the
    // shipping reversal as a prior cancellation would and check the return does not add shipping again.
    ReturnFixture f = deliveredOrder(null);
    jdbc.update("INSERT INTO ledger_entries (order_id, brand_partner_id, total_amount, platform_fee, brand_payout, "
            + "commission_net, commission_vat, commission_rate, currency, entry_type, status, payout_eligible_at, "
            + "moved_to_available, external_reference_id, created_at) VALUES (?, ?, -4.99, 0, -4.99, 0, 0, 0, 'EUR', "
            + "'REFUND_REVERSAL', 'REVERSED', now(), false, 'prior:SHIPPING', now())", f.orderId(), f.brandBId());
    String rn = receivedReturn(f, f.b1(), 1);
    releaseRefund(f.adminToken(), rn, null);
    assertThat((BigDecimal) returnRow(rn).get("refund_shipping_amount")).isEqualByComparingTo("0.00");
}
```
Use `@MockitoSpyBean PaymentProvider paymentProvider` + `doCallRealMethod()` reset in `@AfterEach` exactly as `AdminItemCancellationFailureIntegrationTest` does. Check `ledger_entries` NOT NULL columns against the migrations and adjust the INSERT. Check `GlobalExceptionHandler`'s status for `PaymentException` and assert that.

- [ ] **Step 2: Run** `./mvnw test -Dtest=ReturnRefundReleaseIntegrationTest` → FAIL (404 on release endpoint).
- [ ] **Step 3: Implement** `ReturnRefundService` following `OrderItemCancellationService`'s structure (same field style, `TransactionTemplate`, `OrderLock`, private `Claim` record with `claimKey, orderId, orderNumber, returnNumber, transactionId, total, idempotencyKey, resend`). Delete `RefundPersistenceHelper`, `OrderService.processRefund`, `processRefundByOrder`, `refundableTotal` and the two admin refund endpoints. Make `OrderService.syncOrderStatus` package-private (non-`private`) so the service can call it. Migrate existing tests as listed in Files.
- [ ] **Step 4: Run the whole order/ledger/settlement/discount suites:** `./mvnw test -Dtest='*Return*,*Refund*,*Cancel*,Ledger*,Settlement*,Discount*'` → PASS.

---

### Task 8: Mollie webhook confirms or fails refunds; resend

**Files:**
- Modify: `src/main/java/com/enunas/backend/payment/MollieWebhookController.java`, `payment/mock/MockWebhookController.java` (`/refund` handler calls the service), `order/ReturnRefundService.java` (resend already in claim path — verify)
- Test: `src/test/java/com/enunas/backend/order/integration/ReturnRefundStatusIntegrationTest.java`

**Interfaces:**
- Consumes: `ReturnRefundService.onProviderRefundStatus(String paymentTransactionId)`, `MockPaymentStore.forceNextRefundStatus`, `setRefundStatus`.
- Webhook change: inject `ReturnRefundService`; at the top of `handleWebhook`, after resolving `payment`, call `returnRefundService.onProviderRefundStatus(id)` inside `try/catch (Exception e) { log.error(...) }` **before** the `PAID → no-op` early return. The mock `/webhooks/mock/refund` endpoint calls the same method with `event.getPaymentId()`.

- [ ] **Step 1: Failing tests**

```java
@Test
void webhookOnAlreadyPaidPaymentConfirmsPendingRefund() {
    ReturnFixture f = deliveredOrder(null);
    String rn = receivedReturn(f, f.a1(), 1);
    releaseRefund(f.adminToken(), rn, null);
    postMollieWebhook(transactionIdOf(f.orderId()));                 // POST /webhooks/mollie form id=...
    assertThat(returnRow(rn).get("refund_state")).isEqualTo("REFUNDED");
    assertThat(returnRow(rn).get("status")).isEqualTo("REFUNDED");
}

@Test
void confirmingTwiceSendsTheEmailOnce() {
    ReturnFixture f = deliveredOrder(null);
    String rn = receivedReturn(f, f.a1(), 1);
    releaseRefund(f.adminToken(), rn, null);
    confirmRefunds(f.orderId());
    confirmRefunds(f.orderId());
    verify(emailService, times(1)).sendRefundCompleted(any(), any(), any(), any(), any(), any()); // match the listener's actual EmailService method
}

@Test
void failedRefundIsFlaggedAndResendWorksWithoutNewLedgerRows() {
    ReturnFixture f = deliveredOrder(null);
    String rn = receivedReturn(f, f.a1(), 1);
    mockPaymentStore.forceNextRefundStatus(RefundStatusResult.Status.FAILED);
    releaseRefund(f.adminToken(), rn, null);
    confirmRefunds(f.orderId());
    assertThat(returnRow(rn).get("refund_state")).isEqualTo("FAILED");
    int ledgerRows = reversals(f.orderId()).size();
    assertThat(releaseRefund(f.adminToken(), rn, null).getStatusCode().value()).isEqualTo(200);
    assertThat(returnRow(rn).get("refund_state")).isEqualTo("PENDING");
    assertThat(returnRow(rn).get("refund_resend_count")).isEqualTo(1);
    confirmRefunds(f.orderId());
    assertThat(returnRow(rn).get("refund_state")).isEqualTo("REFUNDED");
    assertThat(reversals(f.orderId())).hasSize(ledgerRows);
}

@Test
void stillProcessingIsANoOp() {
    ReturnFixture f = deliveredOrder(null);
    String rn = receivedReturn(f, f.a1(), 1);
    mockPaymentStore.forceNextRefundStatus(RefundStatusResult.Status.PROCESSING);
    releaseRefund(f.adminToken(), rn, null);
    confirmRefunds(f.orderId());
    assertThat(returnRow(rn).get("refund_state")).isEqualTo("PENDING");
}
```
Find the actual mail method used by the `RefundCompletedEvent` listener (grep `RefundCompletedEvent`) and verify on that.
- [ ] **Step 2: Run** `./mvnw test -Dtest=ReturnRefundStatusIntegrationTest` → FAIL (first test: webhook early-returns on PAID).
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** `./mvnw test -Dtest='ReturnRefundStatusIntegrationTest,OrderMolliePaymentIdIntegrationTest,LatePaymentAutoRefundIntegrationTest'` → PASS.

---

### Task 9: Reconcile a stuck return-refund claim

**Files:**
- Create: `src/main/java/com/enunas/backend/order/dto/ReconcileReturnRefundDto.java`
- Modify: `order/ReturnRefundService.java`, `admin/AdminRefundController.java`
- Test: `src/test/java/com/enunas/backend/order/integration/ReturnRefundReconcileIntegrationTest.java`

**Interfaces:**
- Produces: `public record ReconcileReturnRefundDto(@NotNull Action action, String refundId) { public enum Action { RECORD, RELEASE } }`; `ReturnRefundService.reconcile(String returnNumber, ReconcileReturnRefundDto dto, User admin)`; `POST /admin/returns/{returnNumber}/reconcile-refund`.
- Rules: only when `refundClaimKey != null && refundTransactionId == null` and `refundClaimedAt` older than `OrderItem.STUCK_AFTER` (else 409 "still in progress"). RECORD: refund id required (400); `paymentProvider.getRefundStatus(txId, refundId)` must return `paymentId == order payment transactionId` and `amount == refundAmount` (else 409 naming the mismatch); `isRefundAlreadyRecorded` → 409; then the same finalize as Task 7 (PENDING + ledger; then status updates arrive via webhook / `onProviderRefundStatus`). RELEASE: clear all claim fields (`refundClaimKey`, amounts, `refundClaimedAt`, `refundReleasedBy`, `refundOverrideReason`). Log `RETURN_REFUND_RECONCILED`.

- [ ] **Step 1: Failing tests**: stuck claim (ambiguous error as in Task 7, then `jdbc.update("UPDATE return_orders SET refund_claimed_at = now() - interval '10 minutes' WHERE return_number = ?", rn)`):
  - RECORD with a refund id minted in the mock for this payment and the right amount (call `mockPaymentStore`/`paymentProvider` real method directly to mint one with `RefundCommand(tx, amount, "r", "manual")`) → 200, `refund_state = PENDING`, one reversal;
  - RECORD with a refund of a different amount → 409, claim untouched;
  - RECORD with a refund id already recorded → 409;
  - RELEASE → claim cleared, release again possible (200);
  - reconcile on a fresh (not yet stuck) claim → 409.
- [ ] **Step 2: Run** `./mvnw test -Dtest=ReturnRefundReconcileIntegrationTest` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** → PASS.

---

### Task 10: Refund overview

**Files:**
- Create: `src/main/java/com/enunas/backend/order/dto/RefundOverviewRowDto.java`
- Modify: `order/ReturnOrderRepository.java`, `order/ReturnRefundService.java`, `admin/AdminRefundController.java`
- Test: `src/test/java/com/enunas/backend/order/integration/RefundOverviewIntegrationTest.java`

**Interfaces:**
- Produces: `GET /admin/refunds?state=DUE|IN_PROGRESS|PENDING|FAILED|REFUNDED&page=&size=` → `Page<RefundOverviewRowDto>`; `RefundOverviewRowDto(String returnNumber, String orderNumber, Long brandId, String brandName, String state, BigDecimal itemsAmount, BigDecimal shippingAmount, BigDecimal total, String releasedBy, String overrideReason, String claimKey, String refundTransactionId, LocalDateTime receivedAt, ReceivedBy receivedBy, LocalDateTime claimedAt, LocalDateTime refundedAt)`.
- Repository queries (JPQL, `Pageable`, ordered by id desc):
  - DUE: `status = RECEIVED AND refundClaimKey IS NULL`
  - IN_PROGRESS: `refundClaimKey IS NOT NULL AND refundTransactionId IS NULL`
  - PENDING / FAILED / REFUNDED: `refundState = :state`
  - Missing/unknown `state` → 400.

- [ ] **Step 1: Failing test**: one return RECEIVED (DUE), one released-but-unconfirmed (PENDING), one confirmed (REFUNDED); assert each state lists exactly its return with correct amounts and `state` string; `state=NOPE` → 400.
- [ ] **Step 2: Run** `./mvnw test -Dtest=RefundOverviewIntegrationTest` → FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run full suite** `./mvnw test` → PASS (Docker must be running). Record any pre-existing unrelated failures (e.g. the `SettlementIntegrationTest` calendar time bomb) separately rather than "fixing" them here.

---

## Self-Review notes

- Spec coverage: flow/names (T4, T7), quantity request + merge + coverage (T3), Refund Quote incl. rounding + legacy + shipping rule (T1, T7), ledger exact + delete pro-rata (T6), claim/safety net/override/error classes (T5, T7), status tracking + webhook + resend (T5, T8), payment status rule (T7), reconcile with payment/amount verification (T9), overview (T10), schema V37 (T3), DTO fields (T3), cancellation on quote (T2).
- Out of scope per spec non-goals: second active return per brand, deductions, auto-reconcile, whole-order/late-payment claims, shared claim runner, settlement/rename/hold window.
