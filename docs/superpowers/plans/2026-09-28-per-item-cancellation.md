# Per-Item Pre-Shipment Cancellation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An admin can cancel a single brand's still-unshipped line items on a paid multi-brand order. The customer gets back exactly what they paid for those items, plus that brand's shipping if nothing of the brand remains. No other flow treats a cancelled item as still sold, and no failure or retry can refund twice.

**Architecture:** A new `OrderItemCancellationService` in the `order` package runs three steps. First it **claims** the items: a short write transaction under the Order row lock stores the whole decision on the items. Then it **refunds** at Mollie, outside any transaction. Then it **finalizes** in one transaction that reads everything back from the database. The reconcile endpoint shares finalize and release. `OrderService`, `RefundPersistenceHelper`, `LedgerService` and the §22f query each get small, focused changes so every existing read and write path respects the two new predicates, `isCancelled()` (withdrawn from fulfilment) and `isCancellationSettled()` (refund recorded).

**Tech Stack:** Java 21, Spring Boot 4.0.5, Spring Data JPA/Hibernate (ddl-auto `validate`), Flyway, PostgreSQL, Mollie Java SDK 1.5.4, JUnit 5, Testcontainers, Mockito.

**Spec:** `docs/superpowers/specs/2026-09-28-per-item-cancellation-design.md` (decisions D1–D27, facts F1–F11). Read it before starting any task; task text cites decisions by number.

## Global Constraints

- **Every implementer runs under `ponytail`** (full intensity). Build the shortest, most reused implementation that passes the task's tests. No abstraction beyond what a task names.
- **TDD per task:** write the failing test, watch it fail, implement, watch it pass.
- **No `git add` / `git commit` / `git push`.** The user stages and commits everything themselves. Every "Checkpoint" step leaves changes uncommitted and lists the touched files.
- **Never edit an applied Flyway migration.** The new migration is `V36__order_item_cancellation.sql`. First confirm `V35__return_order_version.sql` is the highest existing version. If a higher one exists, use the next free number.
- **Run tests from `backend/`:** `./mvnw -q -o test -Dtest=<ClassOrPattern>`. Don't run `mvn clean`: OneDrive file locks break it, and a sudden wave of `NoClassDefFoundError` means interference, not a regression.
- Integration tests extend `AbstractDiscountIntegrationTest` (Testcontainers Postgres, profiles `test` + `mock-payments`, JVM pinned to UTC via the surefire `-Duser.timezone=UTC`).
- **HTTP mapping** (`GlobalExceptionHandler`): `IllegalStateException` → 409, `IllegalArgumentException` → 400, `PaymentException` (and subclasses) → 400, bean validation → 400, anything else → 500. Error bodies carry `message`.
- **Exact log markers:** `ITEM_CANCEL_AMBIGUOUS`, `ITEM_CANCEL_RECORDING_FAILED`, `ITEM_CANCEL_ABORTED_AFTER_SHIPMENT`, `ITEM_CANCEL_RECONCILED`, `EMAIL_DELIVERY_FAILURE`.
- A claim is **stuck** once it is 5 minutes old or older (`Duration.ofMinutes(5)`).
- Claim key and Mollie idempotency key: `item-cancel-<orderNumber>-<UUID.randomUUID()>`, fresh per claim (D4).
- Customer- and brand-facing email copy is German, informal "du", plain text via `EmailService.sendPlainTextEmail(to, subject, body)`.

## Review Focus

Inputs the spec implies but no decision spells out. Each line has a test in the owning task:

1. **The same item id twice in one request** (`[a1, a1]`) → treated as one item, one refund of that item's amount. (Task 7, `duplicateIdsInOneRequest_countOnce`)
2. **An empty `orderItemIds` or a missing `reason`** → 400 before anything is written or refunded. (Task 7, `invalidBody_isRejectedBeforeAnything`)
3. **An item id from a different order** → 400, nothing written, no refund. (Task 7, `itemOfAnotherOrder_isRejected`)
4. **Target brand in `PROBLEM`** (it reported a shipping problem) → cancelling its items is allowed: `PROBLEM` is not `SHIPPED`. (Task 7, `brandWithShippingProblem_canBeCancelled`)
5. **Reconcile with a claim key from another order** → 409, nothing changed, no Mollie call. (Task 8, `reconcile_withForeignClaimKey_isRejected`)

---

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `src/main/resources/db/migration/V36__order_item_cancellation.sql` (create) | the seven nullable cancellation columns on `order_items` | 1 |
| `src/main/java/com/enunas/backend/order/OrderItem.java` (modify) | new fields + `isCancelled()` / `isCancellationSettled()` | 1 |
| `src/main/java/com/enunas/backend/exception/PaymentRejectedException.java` (create) | "provider answered and refused": the only failure that may release a claim | 2 |
| `src/main/java/com/enunas/backend/payment/MolliePaymentService.java` (modify) | classify Mollie 4xx as `PaymentRejectedException` | 2 |
| `src/main/java/com/enunas/backend/ledger/LedgerService.java` (modify) | exact item reversal, emptied-brand shipping reversal, D22 shared filter | 3 |
| `src/test/java/com/enunas/backend/order/integration/AbstractItemCancellationIntegrationTest.java` (create) | shared two-brand fixture and DB accessors for all new integration tests | 3 (extended in 7) |
| `src/main/java/com/enunas/backend/order/OrderService.java` (modify) | order locks, no-active-items guard, dispatch email filter, `brandsOnOrder` filter; returns filter; `ReturnCoverage`; public `syncShipmentStatus` | 4, 5, 7 |
| `src/main/java/com/enunas/backend/order/ReturnCoverage.java` (create) | the one shared "every item returned or cancellation-settled" check | 5 |
| `src/main/java/com/enunas/backend/order/RefundPersistenceHelper.java` (modify) | use `ReturnCoverage` and `DiscountService.releaseUsageOnce` | 5 |
| `src/main/java/com/enunas/backend/discount/DiscountService.java` (modify) | `releaseUsageOnce(Order)`, the one copy of "release a code's usage once" | 5 |
| `src/main/java/com/enunas/backend/order/OrderItemRepository.java` (modify) | §22f query skips cancelled items | 5 |
| `src/main/java/com/enunas/backend/order/dto/OrderItemResponseDto.java` (modify) | cancellation fields; brand-safe vs. full factory | 6 |
| `src/main/java/com/enunas/backend/order/dto/OrderResponseDto.java` (modify) | use the full item factory | 6 |
| `src/main/java/com/enunas/backend/order/OrderItemCancellationService.java` (create) | claim / refund / finalize / release / reconcile | 7, 8 |
| `src/main/java/com/enunas/backend/order/dto/CancelOrderItemsDto.java` (create) | request body of `POST /admin/orders/{id}/cancel-items` | 7 |
| `src/main/java/com/enunas/backend/order/dto/ReconcileItemCancellationDto.java` (create) | request body of the reconcile endpoint | 8 |
| `src/main/java/com/enunas/backend/order/OrderItemsCancelledEvent.java` (create) | event published by finalize | 7 |
| `src/main/java/com/enunas/backend/order/OrderItemsCancelledEmailListener.java` (create) | customer + brand mails, `AFTER_COMMIT`, best-effort | 7 |
| `src/main/java/com/enunas/backend/admin/AdminController.java` (modify) | the two new endpoints | 7, 8 |

---

### Task 1: Cancellation columns and predicates on `OrderItem`

**Files:**
- Create: `backend/src/main/resources/db/migration/V36__order_item_cancellation.sql`
- Modify: `backend/src/main/java/com/enunas/backend/order/OrderItem.java` (fields after `brandDiscountShare`, line ~152)
- Test: `backend/src/test/java/com/enunas/backend/order/OrderItemCancellationStateTest.java`

**Interfaces:**
- Produces: on `OrderItem`: `LocalDateTime cancelledAt`, `CancelReason cancellationReason`, `String cancellationNote`, `String cancelledByAdminEmail`, `Boolean cancellationIncludesShipping`, `String cancellationClaimKey`, `String refundTransactionId` (Lombok getters/setters); `boolean isCancelled()`; `boolean isCancellationSettled()`.

- [ ] **Step 1: Confirm the migration number**

Run: `ls src/main/resources/db/migration | sort -V | tail -3`
Expected: the last entry is `V35__return_order_version.sql`. If a higher version exists, use the next number and apply it everywhere this plan says `V36`.

- [ ] **Step 2: Write the failing test**

```java
package com.enunas.backend.order;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class OrderItemCancellationStateTest {

    @Test
    void activeItem_isNeitherCancelledNorSettled() {
        OrderItem item = new OrderItem();
        assertThat(item.isCancelled()).isFalse();
        assertThat(item.isCancellationSettled()).isFalse();
    }

    @Test
    void claimedItem_isCancelledButNotSettled() {
        OrderItem item = new OrderItem();
        item.setCancelledAt(LocalDateTime.now());
        assertThat(item.isCancelled()).isTrue();
        assertThat(item.isCancellationSettled()).isFalse();
    }

    @Test
    void settledItem_isBoth() {
        OrderItem item = new OrderItem();
        item.setCancelledAt(LocalDateTime.now());
        item.setRefundTransactionId("re_1");
        assertThat(item.isCancelled()).isTrue();
        assertThat(item.isCancellationSettled()).isTrue();
    }
}
```

- [ ] **Step 3: Run it to verify it fails**

Run: `./mvnw -q -o test -Dtest=OrderItemCancellationStateTest`
Expected: compilation FAILURE, `cannot find symbol setCancelledAt / isCancelled`.

- [ ] **Step 4: Write the migration**

```sql
-- Pre-shipment item cancellation (spec 2026-09-28, D1/D11/D15). A claim writes every column except
-- refund_transaction_id; finalize writes refund_transaction_id. All nullable; null on every
-- existing row. Widths and the check constraint match orders.cancellation_* (V0.0.1) and
-- orders.refund_transaction_id (V34).
ALTER TABLE order_items ADD COLUMN cancelled_at TIMESTAMP(6);
ALTER TABLE order_items ADD COLUMN cancellation_reason VARCHAR(255)
    CHECK (cancellation_reason IN ('FRAUD_SUSPICION','OUT_OF_STOCK','CUSTOMER_REQUEST','TECHNICAL_ERROR','OTHER'));
ALTER TABLE order_items ADD COLUMN cancellation_note VARCHAR(500);
ALTER TABLE order_items ADD COLUMN cancelled_by_admin_email VARCHAR(255);
ALTER TABLE order_items ADD COLUMN cancellation_includes_shipping BOOLEAN;
ALTER TABLE order_items ADD COLUMN cancellation_claim_key VARCHAR(255);
ALTER TABLE order_items ADD COLUMN refund_transaction_id VARCHAR(64);
```

- [ ] **Step 5: Add the fields and predicates to `OrderItem`**

Add `import java.time.LocalDateTime;` and, directly after the `brandDiscountShare` field:

```java
    // --- Pre-shipment item cancellation (V36). A claim sets every field below except
    // refundTransactionId; finalize sets refundTransactionId. See isCancelled / isCancellationSettled.
    private LocalDateTime cancelledAt;

    @Enumerated(EnumType.STRING)
    private CancelReason cancellationReason;

    @Column(length = 500)
    private String cancellationNote;

    private String cancelledByAdminEmail;

    private Boolean cancellationIncludesShipping;

    private String cancellationClaimKey;

    @Column(length = 64)
    private String refundTransactionId;

    /** Withdrawn from fulfilment — claimed by an in-flight or stuck cancellation, or settled. */
    public boolean isCancelled() {
        return cancelledAt != null;
    }

    /** Cancelled and its refund recorded — the only state money checks may treat as refunded. */
    public boolean isCancellationSettled() {
        return refundTransactionId != null;
    }
```

- [ ] **Step 6: Run the unit test, then prove the schema still validates**

Run: `./mvnw -q -o test -Dtest=OrderItemCancellationStateTest`
Expected: PASS (3 tests).

Run: `./mvnw -q -o test -Dtest=CancellationFieldsDtoTest`
Expected: PASS. The Spring context starts, so Flyway ran V36 and `ddl-auto: validate` accepted the new mapping.

- [ ] **Step 7: Checkpoint**

Leave changes uncommitted. Touched: the migration, `OrderItem.java`, `OrderItemCancellationStateTest.java`.

---

### Task 2: Classify Mollie refusals as definitive (D25)

**Files:**
- Create: `backend/src/main/java/com/enunas/backend/exception/PaymentRejectedException.java`
- Modify: `backend/src/main/java/com/enunas/backend/payment/MolliePaymentService.java` (`refundPayment` catch block, line ~88)
- Test: `backend/src/test/java/com/enunas/backend/payment/MolliePaymentServiceRefundClassificationTest.java`

**Interfaces:**
- Produces: `com.enunas.backend.exception.PaymentRejectedException extends PaymentException`, constructor `(String message, Throwable cause)`. `MolliePaymentService.refundPayment` throws it **only** when Mollie answered with HTTP 4xx. Every other failure stays a plain `PaymentException` (ambiguous).

- [ ] **Step 1: Write the failing test**

```java
package com.enunas.backend.payment;

import com.enunas.backend.exception.PaymentException;
import com.enunas.backend.exception.PaymentRejectedException;
import com.mollie.mollie.Client;
import com.mollie.mollie.models.errors.APIException;
import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.http.HttpTimeoutException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MolliePaymentServiceRefundClassificationTest {

    private final Client client = mock(Client.class, RETURNS_DEEP_STUBS);
    private final MolliePaymentService service = new MolliePaymentService(client);
    private final RefundCommand command = new RefundCommand("tr_1", new BigDecimal("10.00"), "test", "key-1");

    private void mollieThrows(RuntimeException e) throws Exception {
        when(client.refunds().create().paymentId(anyString()).refundRequest(any()).idempotencyKey(anyString()).call())
                .thenThrow(e);
    }

    @Test
    void a4xxAnswer_isADefinitiveRejection() throws Exception {
        mollieThrows(new APIException("amount too high", 422, new byte[0], null, null));
        assertThatThrownBy(() -> service.refundPayment(command)).isInstanceOf(PaymentRejectedException.class);
    }

    @Test
    void a5xxAnswer_isAmbiguous() throws Exception {
        mollieThrows(new APIException("unavailable", 503, new byte[0], null, null));
        assertThatThrownBy(() -> service.refundPayment(command))
                .isInstanceOf(PaymentException.class)
                .isNotInstanceOf(PaymentRejectedException.class);
    }

    @Test
    void aTimeout_isAmbiguous() throws Exception {
        mollieThrows(new UncheckedIOException(new HttpTimeoutException("timed out")));
        assertThatThrownBy(() -> service.refundPayment(command))
                .isInstanceOf(PaymentException.class)
                .isNotInstanceOf(PaymentRejectedException.class);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -q -o test -Dtest=MolliePaymentServiceRefundClassificationTest`
Expected: compilation FAILURE, `cannot find symbol PaymentRejectedException`.

- [ ] **Step 3: Create the exception**

```java
package com.enunas.backend.exception;

/**
 * The payment provider answered and refused (HTTP 4xx): nothing was created at the provider, so the
 * caller may safely undo its own state. Every other {@link PaymentException} is ambiguous — the
 * provider may have acted — and must never be treated as "nothing happened".
 */
public class PaymentRejectedException extends PaymentException {

    public PaymentRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 4: Classify inside `refundPayment`**

Add imports `com.enunas.backend.exception.PaymentRejectedException` and `com.mollie.mollie.models.errors.ClientError`. Replace the catch chain at the end of `refundPayment` with:

```java
        } catch (PaymentException e) {
            throw e;
        } catch (ClientError e) {
            if (e.code() >= 400 && e.code() < 500) {
                throw new PaymentRejectedException(
                        "Mollie rejected refundPayment (HTTP " + e.code() + "): " + e.getMessage(), e);
            }
            throw new PaymentException("Mollie refundPayment failed: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new PaymentException("Mollie refundPayment failed: " + e.getMessage(), e);
        }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -q -o test -Dtest=MolliePaymentServiceRefundClassificationTest`
Expected: PASS (3 tests).

- [ ] **Step 6: Checkpoint**

Leave changes uncommitted. Touched: `PaymentRejectedException.java`, `MolliePaymentService.java`, the new test.

---

### Task 3: Ledger — exact item reversal, emptied-brand shipping, D22 filter

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/ledger/LedgerService.java`
- Create: `backend/src/test/java/com/enunas/backend/order/integration/AbstractItemCancellationIntegrationTest.java`
- Test: `backend/src/test/java/com/enunas/backend/order/integration/LedgerItemCancellationIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1's `OrderItem.isCancellationSettled()`.
- Produces:
  - `LedgerService.recordItemCancellationReversal(Order order, List<OrderItem> items, String refundId)`, idempotent per `refundId`.
  - `LedgerService.reverseShippingForEmptiedBrand(Order order, Long brandId, String refundId)`, idempotent per `refundId + ":SHIPPING"`.
  - The test base class `AbstractItemCancellationIntegrationTest` with `record Fixture(long orderId, long brandAId, long brandBId, long a1, long a2, long a3, long b1, String adminToken, String customerToken, String brandAToken, String brandBToken)` and the helpers `paidTwoBrandOrder(String discountCode)`, `unpaidTwoBrandOrder(String discountCode)`, `itemIdOf(long orderId, long listingId)`, `settleCancelledViaJdbc(long... itemIds)`, `claimViaJdbc(long... itemIds)`, `ship(String brandToken, long orderId)`, `reportProblem(String brandToken, long orderId)`, `adminSetStatus(String adminToken, long orderId, String status)`, `itemRow(long itemId)`, `itemMoney(long itemId, String column)`, `productName(long itemId)`, `stockOfItem(long itemId)`, `reversals(long orderId)`, `orderStatus(long orderId)`, `paymentStatus(long orderId)`, `shippingOf(long orderId, long brandId)`.

- [ ] **Step 1: Create the shared test base**

```java
package com.enunas.backend.order.integration;

import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared fixture for the per-item cancellation tests: one paid order with three items from brand A
 * (a1 €119.00, a2 €59.50, a3 €29.75) and one from brand B (b1 €100.00). Seeded stock is 5 per variant,
 * so a paid item's variant reads 4 and a restored one reads 5 again. Shipping is the global
 * default, €4.99 per brand.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
abstract class AbstractItemCancellationIntegrationTest extends AbstractDiscountIntegrationTest {

    record Fixture(long orderId, long brandAId, long brandBId, long a1, long a2, long a3, long b1,
                   String adminToken, String customerToken, String brandAToken, String brandBToken) {}

    protected Fixture paidTwoBrandOrder(String discountCode) {
        Fixture f = unpaidTwoBrandOrder(discountCode);
        confirmPaid(f.orderId());
        return f;
    }

    protected Fixture unpaidTwoBrandOrder(String discountCode) {
        seedCustomer();
        seedAdmin();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        BrandFixture b = seedBrand("BrandB", "brand-b", "0.18");
        long la1 = seedListing(a.brand(), a.user(), "119.00", 5);
        long la2 = seedListing(a.brand(), a.user(), "59.50", 5);
        long la3 = seedListing(a.brand(), a.user(), "29.75", 5);
        long lb1 = seedListing(b.brand(), b.user(), "100.00", 5);
        String admin = login("admin@it.local", "Admin123!");
        if (discountCode != null) {
            assertThat(createAdminDiscount(admin, Map.of("code", discountCode, "percent", 0.10))
                    .getStatusCode().value()).isEqualTo(201);
        }
        String customer = login("customer@it.local", "Customer123!");
        long oid = orderId(postOrder(customer, discountCode,
                List.of(item(la1, 1), item(la2, 1), item(la3, 1), item(lb1, 1))));
        return new Fixture(oid, a.brand().getId(), b.brand().getId(),
                itemIdOf(oid, la1), itemIdOf(oid, la2), itemIdOf(oid, la3), itemIdOf(oid, lb1),
                admin, customer, login("brand-a@it.local", "Brand123!"), login("brand-b@it.local", "Brand123!"));
    }

    protected long itemIdOf(long orderId, long listingId) {
        return jdbc.queryForObject(
                "SELECT id FROM order_items WHERE order_id = ? AND listing_id_snapshot = ?",
                Long.class, orderId, listingId);
    }

    /** Settled cancellation written straight to the DB — for flows that only READ cancellation state. */
    protected void settleCancelledViaJdbc(long... itemIds) {
        for (long id : itemIds) {
            jdbc.update("UPDATE order_items SET cancelled_at = now(), cancellation_reason = 'OUT_OF_STOCK', "
                    + "cancellation_claim_key = 'jdbc-claim', refund_transaction_id = 're_jdbc_' || id WHERE id = ?", id);
        }
    }

    /** A claim that is in flight or stuck: cancelled_at set, no refund recorded. */
    protected void claimViaJdbc(long... itemIds) {
        for (long id : itemIds) {
            jdbc.update("UPDATE order_items SET cancelled_at = now(), cancellation_reason = 'OUT_OF_STOCK', "
                    + "cancellation_claim_key = 'jdbc-claim' WHERE id = ?", id);
        }
    }

    protected ResponseEntity<Map> ship(String brandToken, long orderId) {
        return rest.exchange("/brand/orders/" + orderId + "/ship", HttpMethod.POST,
                new HttpEntity<>(Map.of("carrier", "DHL", "trackingNumber", "T-" + orderId), auth(brandToken)),
                Map.class);
    }

    protected ResponseEntity<Map> reportProblem(String brandToken, long orderId) {
        return rest.exchange("/brand/orders/" + orderId + "/problem", HttpMethod.POST,
                new HttpEntity<>(Map.of("description", "Lost at the warehouse"), auth(brandToken)), Map.class);
    }

    protected ResponseEntity<Map> adminSetStatus(String adminToken, long orderId, String status) {
        return rest.exchange("/admin/orders/" + orderId + "/status?status=" + status, HttpMethod.PATCH,
                new HttpEntity<>(null, auth(adminToken)), Map.class);
    }

    protected Map<String, Object> itemRow(long itemId) {
        return jdbc.queryForMap("SELECT * FROM order_items WHERE id = ?", itemId);
    }

    protected BigDecimal itemMoney(long itemId, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM order_items WHERE id = ?", BigDecimal.class, itemId);
    }

    protected String productName(long itemId) {
        return jdbc.queryForObject("SELECT product_snapshot_name FROM order_items WHERE id = ?", String.class, itemId);
    }

    protected int stockOfItem(long itemId) {
        return jdbc.queryForObject("SELECT pv.stock_quantity FROM product_variants pv "
                + "JOIN order_items oi ON oi.variant_id = pv.id WHERE oi.id = ?", Integer.class, itemId);
    }

    protected List<Map<String, Object>> reversals(long orderId) {
        return jdbc.queryForList("SELECT * FROM ledger_entries WHERE order_id = ? "
                + "AND entry_type = 'REFUND_REVERSAL' ORDER BY id", orderId);
    }

    protected String orderStatus(long orderId) {
        return (String) orderRow(orderId).get("status");
    }

    protected String paymentStatus(long orderId) {
        return jdbc.queryForObject("SELECT status FROM payments WHERE order_id = ?", String.class, orderId);
    }

    protected BigDecimal shippingOf(long orderId, long brandId) {
        return jdbc.queryForObject("SELECT amount FROM order_shipping_snapshots "
                + "WHERE order_id = ? AND brand_partner_id = ?", BigDecimal.class, orderId, brandId);
    }
}
```

- [ ] **Step 2: Write the failing ledger tests**

```java
package com.enunas.backend.order.integration;

import com.enunas.backend.order.Order;
import com.enunas.backend.order.OrderItem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerItemCancellationIntegrationTest extends AbstractItemCancellationIntegrationTest {

    @Autowired TransactionTemplate transactionTemplate;

    private void inTx(long orderId, Consumer<Order> action) {
        transactionTemplate.executeWithoutResult(s -> action.accept(orderRepository.findById(orderId).orElseThrow()));
    }

    private static OrderItem itemOf(Order order, long itemId) {
        return order.getItems().stream().filter(i -> i.getId() == itemId).findFirst().orElseThrow();
    }

    @Test
    void reversesExactlyTheCancelledItems_andNothingElse() {
        Fixture f = paidTwoBrandOrder(null);
        BigDecimal pendingA = brandPending(f.brandAId());
        BigDecimal pendingB = brandPending(f.brandBId());

        inTx(f.orderId(), o -> ledgerService.recordItemCancellationReversal(o, List.of(itemOf(o, f.a1())), "re_1"));

        List<Map<String, Object>> rev = reversals(f.orderId());
        assertThat(rev).hasSize(1);
        Map<String, Object> r = rev.get(0);
        assertThat(r.get("external_reference_id")).isEqualTo("re_1");
        assertThat(((Number) r.get("brand_partner_id")).longValue()).isEqualTo(f.brandAId());
        assertThat((BigDecimal) r.get("brand_payout"))
                .isEqualByComparingTo(itemMoney(f.a1(), "brand_payout_amount").negate());
        assertThat((BigDecimal) r.get("platform_fee"))
                .isEqualByComparingTo(itemMoney(f.a1(), "commission_net").negate());
        assertThat((BigDecimal) r.get("commission_vat"))
                .isEqualByComparingTo(itemMoney(f.a1(), "commission_vat").negate());
        assertThat(brandPending(f.brandAId()))
                .isEqualByComparingTo(pendingA.subtract(itemMoney(f.a1(), "brand_payout_amount")));
        assertThat(brandPending(f.brandBId())).isEqualByComparingTo(pendingB);
    }

    @Test
    void itemReversal_isIdempotentPerRefundId() {
        Fixture f = paidTwoBrandOrder(null);
        inTx(f.orderId(), o -> ledgerService.recordItemCancellationReversal(o, List.of(itemOf(o, f.a1())), "re_1"));
        inTx(f.orderId(), o -> ledgerService.recordItemCancellationReversal(o, List.of(itemOf(o, f.a1())), "re_1"));
        assertThat(reversals(f.orderId())).hasSize(1);
    }

    @Test
    void emptiedBrandShipping_isReversedOnceInFull() {
        Fixture f = paidTwoBrandOrder(null);
        inTx(f.orderId(), o -> ledgerService.reverseShippingForEmptiedBrand(o, f.brandBId(), "re_s"));
        inTx(f.orderId(), o -> ledgerService.reverseShippingForEmptiedBrand(o, f.brandBId(), "re_s"));

        List<Map<String, Object>> rev = reversals(f.orderId());
        assertThat(rev).hasSize(1);
        assertThat(rev.get(0).get("external_reference_id")).isEqualTo("re_s:SHIPPING");
        assertThat((BigDecimal) rev.get(0).get("brand_payout"))
                .isEqualByComparingTo(shippingOf(f.orderId(), f.brandBId()).negate());
    }

    /** D22: a later pro-rata return must skip items an item-cancel already reversed exactly. */
    @Test
    void laterReturnReversal_excludesSettledCancelledItems_fromBasisAndSum() {
        Fixture f = paidTwoBrandOrder(null);
        settleCancelledViaJdbc(f.a1(), f.a3());
        // Make a1 deliberately disproportionate: with a1 still in the pro-rata basis and sum, a2's
        // reversal could not come out at exactly a2's own values.
        jdbc.update("UPDATE order_items SET brand_payout_amount = 0, commission_net = 0, commission_vat = 0 "
                + "WHERE id = ?", f.a1());
        BigDecimal a2Gross = itemMoney(f.a2(), "line_gross");

        inTx(f.orderId(), o -> ledgerService.recordRefund(o, f.brandAId(), a2Gross, "ret_a2"));

        Map<String, Object> r = reversals(f.orderId()).get(0);
        assertThat((BigDecimal) r.get("brand_payout"))
                .isEqualByComparingTo(itemMoney(f.a2(), "brand_payout_amount").negate());
        assertThat((BigDecimal) r.get("platform_fee"))
                .isEqualByComparingTo(itemMoney(f.a2(), "commission_net").negate());
    }
}
```

- [ ] **Step 3: Run them to verify they fail**

Run: `./mvnw -q -o test -Dtest=LedgerItemCancellationIntegrationTest`
Expected: compilation FAILURE, `cannot find symbol recordItemCancellationReversal`.

- [ ] **Step 4: Implement in `LedgerService`**

Add these two public methods next to the existing `recordRefund` overloads:

```java
    /**
     * Pre-shipment item cancellation: reverses exactly these items' own product entries — no
     * fraction, no proration. Contrast {@link #recordRefund(Order, Long, BigDecimal, String)}, which
     * prorates across every item of the brand. Idempotent per {@code refundId}.
     */
    @Transactional
    public void recordItemCancellationReversal(Order order, List<OrderItem> items, String refundId) {
        if (ledgerRepository.existsByExternalReferenceIdAndEntryType(refundId, LedgerEntryType.REFUND_REVERSAL)) {
            log.warn("LedgerService: REFUND_REVERSAL already recorded for externalRefundId={}; skipping", refundId);
            return;
        }
        Long brandId = items.get(0).getBrandId();
        BigDecimal fee = BigDecimal.ZERO, vat = BigDecimal.ZERO, payout = BigDecimal.ZERO;
        for (OrderItem item : items) {
            fee = fee.add(nz(item.getCommissionNet()));
            vat = vat.add(nz(item.getCommissionVat()));
            payout = payout.add(nz(item.getBrandPayoutAmount()));
        }
        List<LedgerEntry> originals = ledgerRepository.findActivePaymentEntriesByOrderAndBrand(order.getId(), brandId);

        ledgerRepository.save(LedgerEntry.builder()
                .orderId(order.getId())
                .orderItemId(items.get(0).getId())
                .brandPartnerId(brandId)
                .totalAmount(fee.add(vat).add(payout).negate())
                .platformFee(fee.negate())
                .brandPayout(payout.negate())
                .commissionNet(fee.negate())
                .commissionVat(vat.negate())
                .commissionRate(items.get(0).getCommissionRate())
                .currency(order.getCurrency())
                .entryType(LedgerEntryType.REFUND_REVERSAL)
                .status(LedgerEntryStatus.REVERSED)
                .payoutEligibleAt(LocalDateTime.now())
                .movedToAvailable(false)
                .reversalOfEntryId(originals.isEmpty() ? null : originals.get(0).getId())
                .externalReferenceId(refundId)
                .build());
        applyBrandDebit(brandId, payout);
        log.info("LedgerService: reversed {} cancelled item(s) exactly for orderId={} brandId={} payout={}",
                items.size(), order.getId(), brandId, payout);
    }

    /** A brand left with no active items never ships: reverse its whole shipping revenue. Idempotent. */
    @Transactional
    public void reverseShippingForEmptiedBrand(Order order, Long brandId, String refundId) {
        if (ledgerRepository.existsByExternalReferenceIdAndEntryType(shippingRef(refundId), LedgerEntryType.REFUND_REVERSAL)) {
            log.warn("LedgerService: shipping REFUND_REVERSAL already recorded for externalRefundId={}; skipping", refundId);
            return;
        }
        reverseShippingEntries(order, brandId, BigDecimal.ONE, refundId);
    }
```

Add the D22 shared filter and `nz` helper next to `brandProductGross`:

```java
    /**
     * A brand's items whose product entries are not yet reversed. A cancellation-settled item was
     * already reversed exactly by {@link #recordItemCancellationReversal}; the pro-rata basis
     * ({@link #brandProductGross}) and the pro-rata sum ({@link #reverseProductEntries}) must BOTH skip
     * it, or a later return on the same brand reverses the wrong amount (spec D22).
     */
    private List<OrderItem> unreversedItemsOf(Order order, Long brandId) {
        return order.getItems().stream()
                .filter(i -> brandId.equals(i.getBrandId()) && !i.isCancellationSettled())
                .toList();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
```

Rewrite `brandProductGross` to use it:

```java
    /** @return the summed lineGross of the brand's not-yet-reversed items, or {@code null} if there are none. */
    private BigDecimal brandProductGross(Order order, Long brandId) {
        List<OrderItem> items = unreversedItemsOf(order, brandId);
        if (items.isEmpty()) return null;
        BigDecimal gross = BigDecimal.ZERO;
        for (OrderItem item : items) {
            gross = gross.add(item.getLineGross() != null ? item.getLineGross() : item.getLineTotal());
        }
        return gross;
    }
```

In `reverseProductEntries`, replace the loop header and its brand filter

```java
        for (OrderItem item : order.getItems()) {
            if (!brandId.equals(item.getBrandId())) continue;
```

with

```java
        for (OrderItem item : unreversedItemsOf(order, brandId)) {
```

Leave the rest of the loop body unchanged. Add `import java.util.List;` if it is missing.

- [ ] **Step 5: Run the new tests to verify they pass**

Run: `./mvnw -q -o test -Dtest=LedgerItemCancellationIntegrationTest`
Expected: PASS (4 tests).

- [ ] **Step 6: Run existing ledger and refund tests for regressions**

Run: `./mvnw -q -o test -Dtest='*Ledger*Test,*Settlement*Test,ReturnLifecyclePhase3Test,MultiBrandReturnTest,AdminCancelRefundIntegrationTest'`
Expected: all PASS. On orders without cancellations the filter is a no-op.

- [ ] **Step 7: Checkpoint**

Leave changes uncommitted. Touched: `LedgerService.java`, `AbstractItemCancellationIntegrationTest.java`, `LedgerItemCancellationIntegrationTest.java`.

---

### Task 4: Shipping side — order locks, no-active-items guard, dispatch email, rollup (D9, D18, D23)

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/order/OrderService.java`: `confirmShipment` (~664), `reportShippingProblem` (~701), `publishShipmentConfirmed` (~736), `updateOrderStatus` (~1070), `brandsOnOrder` (~1794), `findById` (~1805)
- Test: `backend/src/test/java/com/enunas/backend/order/integration/ShippingWithCancelledItemsIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 predicates; Task 3 test base.
- Produces: `confirmShipment`, `reportShippingProblem` and `updateOrderStatus` hold the `orders` row lock for their whole transaction. An emptied brand can't ship or report a problem (409). `brandsOnOrder` ignores `isCancelled()` items.

- [ ] **Step 1: Write the failing tests**

```java
package com.enunas.backend.order.integration;

import com.enunas.backend.user.EmailService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

@SuppressWarnings("rawtypes")
class ShippingWithCancelledItemsIntegrationTest extends AbstractItemCancellationIntegrationTest {

    @MockitoBean EmailService emailService;
    @Autowired TransactionTemplate transactionTemplate;

    private int shipmentRows(long orderId, long brandId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM order_shipments WHERE order_id = ? AND brand_partner_id = ?",
                Integer.class, orderId, brandId);
    }

    @Test
    void emptiedBrand_cannotConfirmShipment() {
        Fixture f = paidTwoBrandOrder(null);
        settleCancelledViaJdbc(f.b1());

        assertThat(ship(f.brandBToken(), f.orderId()).getStatusCode().value()).isEqualTo(409);
        assertThat(shipmentRows(f.orderId(), f.brandBId())).isZero();
    }

    @Test
    void emptiedBrand_cannotReportAShippingProblem() {
        Fixture f = paidTwoBrandOrder(null);
        settleCancelledViaJdbc(f.b1());

        assertThat(reportProblem(f.brandBToken(), f.orderId()).getStatusCode().value()).isEqualTo(409);
        assertThat(shipmentRows(f.orderId(), f.brandBId())).isZero();
    }

    @Test
    void orderReachesShipped_whenEveryActiveBrandShipped() {
        Fixture f = paidTwoBrandOrder(null);
        settleCancelledViaJdbc(f.b1());

        assertThat(ship(f.brandAToken(), f.orderId()).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(orderStatus(f.orderId())).isEqualTo("SHIPPED");
    }

    @Test
    void adminBulkShip_neverForceShipsAnEmptiedBrand() {
        Fixture f = paidTwoBrandOrder(null);
        settleCancelledViaJdbc(f.b1());

        assertThat(adminSetStatus(f.adminToken(), f.orderId(), "SHIPPED").getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(shipmentRows(f.orderId(), f.brandBId())).isZero();
        assertThat(shipmentRows(f.orderId(), f.brandAId())).isEqualTo(1);
    }

    @Test
    void dispatchEmail_listsOnlyItemsStillActive() {
        Fixture f = paidTwoBrandOrder(null);
        claimViaJdbc(f.a1()); // claimed, not settled — already withdrawn from fulfilment

        assertThat(ship(f.brandAToken(), f.orderId()).getStatusCode().is2xxSuccessful()).isTrue();

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(emailService, atLeastOnce()).sendHtmlEmail(eq("customer@it.local"), anyString(), html.capture());
        List<String> mails = html.getAllValues();
        String dispatchMail = mails.get(mails.size() - 1); // sent last, after the order confirmation
        assertThat(dispatchMail).contains(productName(f.a2())).doesNotContain(productName(f.a1()));
    }

    /**
     * D23: a claim committed while confirmShipment waits for the order lock must be visible to it.
     * Without the lock, confirmShipment reads the items first and ships items being refunded.
     */
    @Test
    void confirmShipment_seesAClaimCommittedWhileItWaitedForTheOrderLock() throws Exception {
        Fixture f = paidTwoBrandOrder(null);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<Future<ResponseEntity<Map>>> shipping = new AtomicReference<>();

        transactionTemplate.executeWithoutResult(status -> {
            orderRepository.findByIdForUpdate(f.orderId()).orElseThrow();
            shipping.set(pool.submit(() -> ship(f.brandAToken(), f.orderId())));
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            claimViaJdbc(f.a1(), f.a2(), f.a3()); // commits together with this transaction
        });

        ResponseEntity<Map> resp = shipping.get().get(15, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(resp.getStatusCode().value()).as("ship: %s", resp.getBody()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_shipments WHERE order_id = ? "
                + "AND brand_partner_id = ? AND status = 'SHIPPED'", Integer.class, f.orderId(), f.brandAId())).isZero();
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw -q -o test -Dtest=ShippingWithCancelledItemsIntegrationTest`
Expected: FAIL. The emptied-brand ship and problem calls return 200, the order stays `PARTIALLY_SHIPPED`, the bulk ship creates a brand-B row, the dispatch mail lists a1, and the lock test gets 200.

- [ ] **Step 3: Add the locking finder next to `findById`**

```java
    /** Same as {@link #findById}, but holds the orders row lock until the transaction ends. Every
     *  writer that decides from item cancellation state takes this lock, so it serialises with
     *  OrderItemCancellationService's claim (spec D23). */
    private Order findByIdForUpdate(Long id) {
        return orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new OrderNotFoundException("Order not found with id: " + id));
    }
```

- [ ] **Step 4: Lock and guard in `confirmShipment` and `reportShippingProblem`**

In both methods replace `Order order = findById(orderId);` with `Order order = findByIdForUpdate(orderId);`. Directly after `BrandPartner brand = resolveOwnBrand(order, brandPartner);` add `assertBrandHasActiveItems(order, brand);`. Then add the guard:

```java
    /** A brand whose every item on this order was cancelled has nothing to ship or report on (spec D18). */
    private void assertBrandHasActiveItems(Order order, BrandPartner brand) {
        boolean anyActive = order.getItems().stream()
                .anyMatch(i -> brand.getId().equals(i.getBrandId()) && !i.isCancelled());
        if (!anyActive) {
            throw new IllegalStateException("All of " + brand.getBrandName() + "'s items on order "
                    + order.getOrderNumber() + " were cancelled — there is nothing to ship.");
        }
    }
```

- [ ] **Step 5: Lock in `updateOrderStatus`**

Replace its first line `Order order = findById(orderId);` with `Order order = findByIdForUpdate(orderId);`.

- [ ] **Step 6: Filter the dispatch email and `brandsOnOrder`**

In `publishShipmentConfirmed`, change the item filter to:

```java
                        .filter(item -> brand.getId().equals(item.getBrandId()) && !item.isCancelled())
```

In `brandsOnOrder`, skip cancelled items as the loop's first statement:

```java
        for (OrderItem item : order.getItems()) {
            if (item.isCancelled()) continue; // nothing left to ship for it (spec D9)
            BrandPartner brand = item.getVariant().getProduct().getBrand();
```

- [ ] **Step 7: Run the new tests to verify they pass**

Run: `./mvnw -q -o test -Dtest=ShippingWithCancelledItemsIntegrationTest`
Expected: PASS (6 tests).

- [ ] **Step 8: Run existing shipping and cancel tests for regressions**

Run: `./mvnw -q -o test -Dtest='*Ship*Test,AdminCancelRefundIntegrationTest,DiscountPaymentFlowIntegrationTest,ReturnLifecyclePhase3Test'`
Expected: all PASS.

- [ ] **Step 9: Checkpoint**

Leave changes uncommitted. Touched: `OrderService.java`, `ShippingWithCancelledItemsIntegrationTest.java`.

---

### Task 5: Returns side, discount release, §22f (D19, D20, D24)

**Files:**
- Create: `backend/src/main/java/com/enunas/backend/order/ReturnCoverage.java`
- Modify: `backend/src/main/java/com/enunas/backend/order/OrderService.java`: `doRequestReturn` (~548-558), `syncOrderStatus`/`allItemsReturned` (~1540-1564), `releaseDiscountUsageOnce` (~1141)
- Modify: `backend/src/main/java/com/enunas/backend/order/RefundPersistenceHelper.java` (`orderFullyRefunded`, discount block, `allItemsCovered`)
- Modify: `backend/src/main/java/com/enunas/backend/discount/DiscountService.java` (next to `releaseUsage`)
- Modify: `backend/src/main/java/com/enunas/backend/order/OrderItemRepository.java` (`findVat22fLineItems`)
- Test: `backend/src/test/java/com/enunas/backend/order/integration/ReturnsWithCancelledItemsIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 predicates; Task 3 test base; Task 4 (an order whose only other brand is emptied reaches `SHIPPED` when brand A ships).
- Produces:
  - `ReturnCoverage.allItemsCovered(Order order, List<ReturnOrder> returns)` (package-private, static).
  - `DiscountService.releaseUsageOnce(Order order)` (public; the caller persists `order`).
  - Returns never include cancelled items.
  - §22f export skips cancelled items.

- [ ] **Step 1: Write the failing tests**

```java
package com.enunas.backend.order.integration;

import com.enunas.backend.compliance.Vat22fExportService;
import com.enunas.backend.order.ReturnOrderRepository;
import com.enunas.backend.user.EmailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("rawtypes")
class ReturnsWithCancelledItemsIntegrationTest extends AbstractItemCancellationIntegrationTest {

    @MockitoBean EmailService emailService;
    @Autowired ReturnOrderRepository returnOrderRepository;
    @Autowired Vat22fExportService vat22fExportService;

    /** Brand A's a3 and brand B's only item b1 are cancelled; a1 and a2 get delivered. */
    private Fixture deliveredWithCancellations(String discountCode, boolean a3SettledNotJustClaimed) {
        Fixture f = paidTwoBrandOrder(discountCode);
        settleCancelledViaJdbc(f.b1());
        if (a3SettledNotJustClaimed) settleCancelledViaJdbc(f.a3()); else claimViaJdbc(f.a3());
        assertThat(ship(f.brandAToken(), f.orderId()).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(adminSetStatus(f.adminToken(), f.orderId(), "DELIVERED").getStatusCode().is2xxSuccessful()).isTrue();
        return f;
    }

    private ResponseEntity<Map> requestReturn(Fixture f, Long orderItemId) {
        Map<String, Object> body = new HashMap<>();
        body.put("reason", "WRONG_SIZE");
        body.put("description", "Passt nicht");
        if (orderItemId != null) body.put("orderItemId", orderItemId);
        return rest.exchange("/orders/" + f.orderId() + "/return", HttpMethod.POST,
                new HttpEntity<>(body, auth(f.customerToken())), Map.class);
    }

    private void refundTheReturn(Fixture f, String returnNumber) {
        for (String action : new String[] {"approve", "receive", "refund"}) {
            ResponseEntity<Map> r = rest.exchange("/admin/returns/" + returnNumber + "/" + action, HttpMethod.POST,
                    new HttpEntity<>(null, auth(f.adminToken())), Map.class);
            assertThat(r.getStatusCode().is2xxSuccessful()).as("%s: %s", action, r.getBody()).isTrue();
        }
    }

    @Test
    void returningACancelledItem_isRejected() {
        Fixture f = deliveredWithCancellations(null, true);
        assertThat(requestReturn(f, f.a3()).getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void wholeOrderReturn_skipsCancelledItems() {
        Fixture f = deliveredWithCancellations(null, true);
        assertThat(requestReturn(f, null).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(returnOrderRepository.findReturnedOrderItemIds(f.orderId()))
                .containsExactlyInAnyOrder(f.a1(), f.a2());
    }

    @Test
    void cancelledPlusReturnedAndRefunded_readsRefunded_andReleasesTheDiscount() {
        Fixture f = deliveredWithCancellations("RET10", true);
        ResponseEntity<Map> ret = requestReturn(f, null);
        refundTheReturn(f, (String) ret.getBody().get("returnNumber"));

        assertThat(orderStatus(f.orderId())).isEqualTo("REFUNDED");
        assertThat(usedCount("RET10")).isZero();
        assertThat(orderRow(f.orderId()).get("discount_usage_released")).isEqualTo(true);
    }

    @Test
    void aStuckClaim_keepsTheOrderFromReadingRefunded() {
        Fixture f = deliveredWithCancellations("RET10", false); // a3 only claimed — its money is not recorded
        ResponseEntity<Map> ret = requestReturn(f, null);
        refundTheReturn(f, (String) ret.getBody().get("returnNumber"));

        assertThat(orderStatus(f.orderId())).isNotEqualTo("REFUNDED");
        assertThat(usedCount("RET10")).isEqualTo(1);
    }

    @Test
    void vat22fExport_excludesCancelledItems_butKeepsReturnedOnes() {
        Fixture f = deliveredWithCancellations(null, true);
        String period = YearMonth.now(ZoneId.of("Europe/Berlin")).toString();
        assertThat(vat22fExportService.export(f.brandAId(), period)).hasSize(2); // a1, a2 — not a3

        ResponseEntity<Map> ret = requestReturn(f, f.a1());
        refundTheReturn(f, (String) ret.getBody().get("returnNumber"));
        assertThat(vat22fExportService.export(f.brandAId(), period)).hasSize(2); // returned a1 stays
    }
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./mvnw -q -o test -Dtest=ReturnsWithCancelledItemsIntegrationTest`
Expected: FAIL:
- returning a3 is accepted;
- the whole-order return includes a3;
- the order stays `RETURN_RECEIVED`;
- the §22f export has 3 rows.

`aStuckClaim_keepsTheOrderFromReadingRefunded` may already pass. It pins D24.

- [ ] **Step 3: Create `ReturnCoverage`**

```java
package com.enunas.backend.order;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Whether every item on an order is accounted for — returned, or cancelled with its refund recorded
 * — the precondition for an order reading REFUNDED. The one copy shared by OrderService and
 * RefundPersistenceHelper, which used to carry identical private versions (spec D19). Keyed on
 * isCancellationSettled, never isCancelled: a stuck claim has not recorded its money (spec D24).
 */
final class ReturnCoverage {

    private ReturnCoverage() {}

    static boolean allItemsCovered(Order order, List<ReturnOrder> returns) {
        Set<Long> returned = returns.stream()
                .flatMap(r -> r.getItems().stream())
                .map(ri -> ri.getOrderItem().getId())
                .collect(Collectors.toSet());
        return order.getItems().stream()
                .allMatch(i -> i.isCancellationSettled() || returned.contains(i.getId()));
    }
}
```

- [ ] **Step 4: Use it in `OrderService` and `RefundPersistenceHelper`; delete both old copies**

`OrderService.syncOrderStatus`: `if (allRefunded && allItemsReturned(order, returns))` → `if (allRefunded && ReturnCoverage.allItemsCovered(order, returns))`. Delete the private `allItemsReturned` method.

`RefundPersistenceHelper.persist`: `&& allItemsCovered(order, returns);` → `&& ReturnCoverage.allItemsCovered(order, returns);`. Delete its private `allItemsCovered` method and any imports that become unused (`Set`, `Collectors`).

- [ ] **Step 5: Exclude cancelled items from return requests (`doRequestReturn`)**

Replace the `requested` block with:

```java
        // Which items is the customer returning? (null orderItemId = the whole order.) Cancelled items
        // never shipped and were refunded already — never returnable (spec D19).
        List<OrderItem> requested;
        if (dto.orderItemId() == null) {
            requested = order.getItems().stream().filter(i -> !i.isCancelled()).toList();
        } else {
            OrderItem item = order.getItems().stream()
                    .filter(i -> i.getId().equals(dto.orderItemId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "OrderItem " + dto.orderItemId() + " does not belong to this order"));
            if (item.isCancelled()) {
                throw new IllegalStateException("OrderItem " + item.getId()
                        + " was cancelled before shipment — it cannot be returned.");
            }
            requested = List.of(item);
        }
```

- [ ] **Step 6: One copy of "release the discount usage once"**

In `DiscountService`, next to `releaseUsage`, add:

```java
    /**
     * Releases the order's reserved code usage exactly once, guarded by
     * {@code Order.discountUsageReleased}. The caller persists {@code order}.
     */
    public void releaseUsageOnce(Order order) {
        if (order.getDiscountCode() == null || order.isDiscountUsageReleased()) return;
        releaseUsage(order.getDiscountCode());
        order.setDiscountUsageReleased(true);
    }
```

(Import `com.enunas.backend.order.Order` if it isn't already imported.) Replace the body of `OrderService.releaseDiscountUsageOnce` with:

```java
        discountService.releaseUsageOnce(order);
        orderRepository.save(order);
```

In `RefundPersistenceHelper.persist`, replace

```java
            if (order.getDiscountCode() != null && !order.isDiscountUsageReleased()) {
                discountService.releaseUsage(order.getDiscountCode());
                order.setDiscountUsageReleased(true);
            }
```

with `discountService.releaseUsageOnce(order);` and keep its surrounding comment.

- [ ] **Step 7: §22f skips cancelled items**

In `OrderItemRepository.findVat22fLineItems`, add one line after the `CANCELLED` condition:

```
              AND oi.cancelledAt IS NULL
```

Extend the javadoc: `Cancelled items (spec D20) are excluded — nothing was delivered.`

- [ ] **Step 8: Run the new tests to verify they pass**

Run: `./mvnw -q -o test -Dtest=ReturnsWithCancelledItemsIntegrationTest`
Expected: PASS (5 tests).

- [ ] **Step 9: Run existing return, discount and §22f tests for regressions**

Run: `./mvnw -q -o test -Dtest='*Return*Test,RefundStaleReadIntegrationTest,*Vat22f*Test,*Discount*Test'`
Expected: all PASS.

- [ ] **Step 10: Checkpoint**

Leave changes uncommitted. Touched: `ReturnCoverage.java`, `OrderService.java`, `RefundPersistenceHelper.java`, `DiscountService.java`, `OrderItemRepository.java`, the new test.

---

### Task 6: Cancellation fields on the item DTO (spec DTO section)

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/order/dto/OrderItemResponseDto.java`
- Modify: `backend/src/main/java/com/enunas/backend/order/dto/OrderResponseDto.java` (line ~106)
- Test: `backend/src/test/java/com/enunas/backend/order/integration/ItemCancellationDtoIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 fields and predicates; Task 3 test base.
- Produces:
  - `OrderItemResponseDto` gains `cancelledAt`, `cancellationReason`, `cancellationState` (`"ACTIVE"`/`"PENDING"`/`"CANCELLED"`), `refundTransactionId` and `cancellationClaimKey`.
  - `OrderItemResponseDto.from(item, resolver)`, the brand-safe factory, leaves the last two null.
  - `OrderItemResponseDto.withRefundDetails(item, resolver)` fills them; customer and admin views use it.

- [ ] **Step 1: Write the failing test**

```java
package com.enunas.backend.order.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings({"rawtypes", "unchecked"})
class ItemCancellationDtoIntegrationTest extends AbstractItemCancellationIntegrationTest {

    private static Map<String, Object> itemById(List<Map<String, Object>> items, long id) {
        return items.stream().filter(i -> ((Number) i.get("id")).longValue() == id).findFirst().orElseThrow();
    }

    @Test
    void customerView_showsStateRefundAndClaim() {
        Fixture f = paidTwoBrandOrder(null);
        settleCancelledViaJdbc(f.a1());
        claimViaJdbc(f.a2());

        ResponseEntity<Map> resp = rest.exchange("/orders/" + f.orderId(), HttpMethod.GET,
                new HttpEntity<>(null, auth(f.customerToken())), Map.class);
        List<Map<String, Object>> items = (List<Map<String, Object>>) resp.getBody().get("items");

        Map<String, Object> a1 = itemById(items, f.a1());
        assertThat(a1.get("cancellationState")).isEqualTo("CANCELLED");
        assertThat(a1.get("cancellationReason")).isEqualTo("OUT_OF_STOCK");
        assertThat(a1.get("refundTransactionId")).isEqualTo("re_jdbc_" + f.a1());
        assertThat(a1.get("cancellationClaimKey")).isEqualTo("jdbc-claim");
        assertThat(itemById(items, f.a2()).get("cancellationState")).isEqualTo("PENDING");
        assertThat(itemById(items, f.a3()).get("cancellationState")).isEqualTo("ACTIVE");
    }

    @Test
    void brandView_showsStateButNeverRefundOrClaim() {
        Fixture f = paidTwoBrandOrder(null);
        settleCancelledViaJdbc(f.a1());

        ResponseEntity<Map> resp = rest.exchange("/brand/orders", HttpMethod.GET,
                new HttpEntity<>(null, auth(f.brandAToken())), Map.class);
        List<Map<String, Object>> content = (List<Map<String, Object>>) resp.getBody().get("content");
        List<Map<String, Object>> items = (List<Map<String, Object>>) content.get(0).get("items");

        Map<String, Object> a1 = itemById(items, f.a1());
        assertThat(a1.get("cancellationState")).isEqualTo("CANCELLED");
        assertThat(a1.get("cancellationReason")).isEqualTo("OUT_OF_STOCK");
        assertThat(a1.get("refundTransactionId")).isNull();
        assertThat(a1.get("cancellationClaimKey")).isNull();
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./mvnw -q -o test -Dtest=ItemCancellationDtoIntegrationTest`
Expected: FAIL. `cancellationState` is null because the key is absent.

- [ ] **Step 3: Extend `OrderItemResponseDto`**

Add imports `com.enunas.backend.order.CancelReason` and `java.time.LocalDateTime`, then add these fields after `lineTotal`:

```java
    private LocalDateTime cancelledAt;
    private CancelReason cancellationReason;
    /** ACTIVE, PENDING (claimed — refund not recorded yet) or CANCELLED. */
    private String cancellationState;
    /** Null in brand-scoped views. */
    private String refundTransactionId;
    /** Null in brand-scoped views. */
    private String cancellationClaimKey;
```

Replace the `from` method with three methods:

```java
    /** Brand-safe view: cancellation state without refund or claim references. */
    public static OrderItemResponseDto from(OrderItem item, MediaUrlResolver resolver) {
        return base(item, resolver).build();
    }

    /** Customer and admin views — adds the refund and claim references brands must never see. */
    public static OrderItemResponseDto withRefundDetails(OrderItem item, MediaUrlResolver resolver) {
        return base(item, resolver)
                .refundTransactionId(item.getRefundTransactionId())
                .cancellationClaimKey(item.getCancellationClaimKey())
                .build();
    }

    private static OrderItemResponseDtoBuilder base(OrderItem item, MediaUrlResolver resolver) {
        return OrderItemResponseDto.builder()
                .id(item.getId())
                // NOT item.getVariant().getId() — that's the variant id, a different identifier
                // the client never sent. listingIdSnapshot is null only for orders placed before
                // this field existed (V28); see OrderItem.listingIdSnapshot javadoc.
                .listingId(item.getListingIdSnapshot())
                .productName(item.getProductSnapshotName())
                .variantSku(item.getVariantSnapshotSku())
                .variantColor(item.getVariantSnapshotColor())
                .variantSize(item.getVariantSnapshotSize())
                .imageUrl(resolver.resolve(item.getVariantSnapshotImageKey()))
                .priceAtPurchase(item.getPriceAtPurchase())
                .discountPriceAtPurchase(item.getDiscountPriceAtPurchase())
                .quantity(item.getQuantity())
                .lineTotal(item.getLineTotal())
                .cancelledAt(item.getCancelledAt())
                .cancellationReason(item.getCancellationReason())
                .cancellationState(item.isCancellationSettled() ? "CANCELLED"
                        : item.isCancelled() ? "PENDING" : "ACTIVE");
    }
```

- [ ] **Step 4: Use the full factory in `OrderResponseDto`**

At line ~106, change `.map(item -> OrderItemResponseDto.from(item, resolver))` to `.map(item -> OrderItemResponseDto.withRefundDetails(item, resolver))`. Leave `OrderService.toBrandScopedDto` on `from`.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -q -o test -Dtest=ItemCancellationDtoIntegrationTest`
Expected: PASS (2 tests).

- [ ] **Step 6: Run existing DTO tests for regressions**

Run: `./mvnw -q -o test -Dtest='CancellationFieldsDtoTest,*Dto*Test'`
Expected: all PASS.

- [ ] **Step 7: Checkpoint**

Leave changes uncommitted. Touched: `OrderItemResponseDto.java`, `OrderResponseDto.java`, the new test.

---

### Task 7: The cancel-items flow — claim, refund, finalize (D2–D17, D27)

**Files:**
- Create: `backend/src/main/java/com/enunas/backend/order/OrderItemCancellationService.java`
- Create: `backend/src/main/java/com/enunas/backend/order/dto/CancelOrderItemsDto.java`
- Create: `backend/src/main/java/com/enunas/backend/order/OrderItemsCancelledEvent.java`
- Create: `backend/src/main/java/com/enunas/backend/order/OrderItemsCancelledEmailListener.java`
- Modify: `backend/src/main/java/com/enunas/backend/order/OrderService.java`: `syncShipmentStatus` (~1697) becomes public
- Modify: `backend/src/main/java/com/enunas/backend/admin/AdminController.java`: new field and endpoint
- Modify: `backend/src/test/java/com/enunas/backend/order/integration/AbstractItemCancellationIntegrationTest.java`: add `cancelItems`
- Test: `backend/src/test/java/com/enunas/backend/order/integration/AdminItemCancellationIntegrationTest.java`

**Interfaces:**
- Consumes:
  - Task 1 fields and predicates.
  - Task 2 `PaymentRejectedException`.
  - Task 3 `recordItemCancellationReversal`, `reverseShippingForEmptiedBrand`, and the test base.
  - Task 4 locks and filters.
  - Task 5 `DiscountService.releaseUsageOnce`.
  - Existing: `PaymentProvider.refundPayment(RefundCommand) → RefundResult(refundId)`, `RefundCommand(paymentId, amount, reason, idempotencyKey)`, `OrderRepository.findByIdForUpdate`, `OrderShipmentRepository.findByOrder_IdAndBrand_Id`, `OrderShippingSnapshotRepository.findByOrderIdOrderByIdAsc`, `ProductVariantRepository.restoreStock(Long, int)`, `OrderService.getOrderById(Long)`, `StaleSessionGuard.clear(EntityManager)`.
- Produces:
  - `OrderItemCancellationService.cancelItems(Long orderId, CancelOrderItemsDto dto, User admin) → OrderResponseDto`.
  - Package-private methods used by Task 8: `finalizeClaim(Long orderId, String claimKey, String refundId)`, `releaseClaim(Long orderId, String claimKey)`, `lock(Long orderId)`, `claimItems(Order order, String claimKey)`, and the constant `STUCK_AFTER`.
  - `POST /admin/orders/{orderId}/cancel-items`.
  - Test base `cancelItems(String adminToken, long orderId, List<Long> itemIds)`.

- [ ] **Step 1: Add the HTTP helper to the test base**

```java
    protected ResponseEntity<Map> cancelItems(String adminToken, long orderId, List<Long> itemIds) {
        return rest.exchange("/admin/orders/" + orderId + "/cancel-items", HttpMethod.POST,
                new HttpEntity<>(Map.of("orderItemIds", itemIds, "reason", "OUT_OF_STOCK",
                        "note", "brand cannot fulfil"), auth(adminToken)), Map.class);
    }
```

- [ ] **Step 2: Write the failing tests**

```java
package com.enunas.backend.order.integration;

import com.enunas.backend.payment.PaymentProvider;
import com.enunas.backend.payment.RefundCommand;
import com.enunas.backend.user.EmailService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@SuppressWarnings("rawtypes")
class AdminItemCancellationIntegrationTest extends AbstractItemCancellationIntegrationTest {

    @MockitoBean EmailService emailService;
    @MockitoSpyBean PaymentProvider paymentProvider;

    private List<RefundCommand> refundCalls() {
        ArgumentCaptor<RefundCommand> c = ArgumentCaptor.forClass(RefundCommand.class);
        verify(paymentProvider, atLeast(0)).refundPayment(c.capture());
        return c.getAllValues();
    }

    private long shippingReversals(long orderId, long brandId) {
        return reversals(orderId).stream()
                .filter(r -> ((String) r.get("external_reference_id")).endsWith(":SHIPPING"))
                .filter(r -> ((Number) r.get("brand_partner_id")).longValue() == brandId)
                .count();
    }

    private static String message(ResponseEntity<Map> resp) {
        return String.valueOf(resp.getBody().get("message"));
    }

    // ===== happy paths =====

    @Test
    void cancellingOneOfABrandsItems_refundsExactlyThatItem() {
        Fixture f = paidTwoBrandOrder(null);
        BigDecimal pendingB = brandPending(f.brandBId());

        ResponseEntity<Map> resp = cancelItems(f.adminToken(), f.orderId(), List.of(f.a1()));

        assertThat(resp.getStatusCode().value()).as("%s", resp.getBody()).isEqualTo(200);
        assertThat(refundCalls()).hasSize(1);
        assertThat(refundCalls().get(0).amount()).isEqualByComparingTo(itemMoney(f.a1(), "customer_gross_after_discount"));
        Map<String, Object> a1 = itemRow(f.a1());
        assertThat(a1.get("cancellation_reason")).isEqualTo("OUT_OF_STOCK");
        assertThat((String) a1.get("refund_transaction_id")).startsWith("ref_mock_");
        assertThat(stockOfItem(f.a1())).isEqualTo(5);
        assertThat(stockOfItem(f.a2())).isEqualTo(4);
        assertThat(reversals(f.orderId())).hasSize(1);
        assertThat((BigDecimal) reversals(f.orderId()).get(0).get("brand_payout"))
                .isEqualByComparingTo(itemMoney(f.a1(), "brand_payout_amount").negate());
        assertThat(brandPending(f.brandBId())).isEqualByComparingTo(pendingB);
        assertThat(orderStatus(f.orderId())).isEqualTo("PAID");
        assertThat(paymentStatus(f.orderId())).isEqualTo("REFUNDED");
        verify(emailService).sendPlainTextEmail(eq("customer@it.local"), contains("storniert"), contains("erstatten"));
        verify(emailService).sendPlainTextEmail(eq("brand-a@it.local"), contains("Stornierung"), contains("NICHT"));
    }

    @Test
    void discountedItem_refundsWhatTheCustomerPaid_notThePreDiscountPrice() {
        Fixture f = paidTwoBrandOrder("CX10");
        BigDecimal paid = itemMoney(f.a1(), "customer_gross_after_discount");
        assertThat(paid).isLessThan(itemMoney(f.a1(), "line_gross"));

        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.a1())).getStatusCode().value()).isEqualTo(200);
        assertThat(refundCalls().get(0).amount()).isEqualByComparingTo(paid);
    }

    @Test
    void partiallyShippedOrder_emptyingTheUnshippedBrand_refundsItsShipping_andCompletesTheOrder() {
        Fixture f = paidTwoBrandOrder(null);
        assertThat(ship(f.brandAToken(), f.orderId()).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(orderStatus(f.orderId())).isEqualTo("PARTIALLY_SHIPPED");

        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.b1())).getStatusCode().value()).isEqualTo(200);

        assertThat(refundCalls().get(0).amount()).isEqualByComparingTo(
                itemMoney(f.b1(), "customer_gross_after_discount").add(shippingOf(f.orderId(), f.brandBId())));
        assertThat(shippingReversals(f.orderId(), f.brandBId())).isEqualTo(1);
        assertThat(orderStatus(f.orderId())).as("D27: nothing left to wait for").isEqualTo("SHIPPED");
    }

    @Test
    void emptyingABrandBeforeAnyShipment_refundsItsShipping_andTheOrderShipsWithTheOtherBrand() {
        Fixture f = paidTwoBrandOrder(null);
        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.b1())).getStatusCode().value()).isEqualTo(200);
        assertThat(shippingReversals(f.orderId(), f.brandBId())).isEqualTo(1);
        assertThat(orderStatus(f.orderId())).isEqualTo("PAID");

        assertThat(ship(f.brandAToken(), f.orderId()).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(orderStatus(f.orderId())).isEqualTo("SHIPPED");
    }

    @Test
    void partialCancel_neverRefundsShipping() {
        Fixture f = paidTwoBrandOrder(null);
        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.a1())).getStatusCode().value()).isEqualTo(200);
        assertThat(refundCalls().get(0).amount()).isEqualByComparingTo(itemMoney(f.a1(), "customer_gross_after_discount"));
        assertThat(shippingReversals(f.orderId(), f.brandAId())).isZero();
    }

    @Test
    void cancellingEveryItem_cancelsTheOrder_andReleasesTheDiscountOnce() {
        Fixture f = paidTwoBrandOrder("CX10");
        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.a1(), f.a2(), f.a3())).getStatusCode().value())
                .isEqualTo(200);
        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.b1())).getStatusCode().value()).isEqualTo(200);

        assertThat(orderStatus(f.orderId())).isEqualTo("CANCELLED");
        assertThat(usedCount("CX10")).isZero();
        assertThat(orderRow(f.orderId()).get("discount_usage_released")).isEqualTo(true);
    }

    @Test
    void secondCancelAfterThePaymentReadsRefunded_stillRefunds_andBlocksTheWholeOrderCancel() {
        Fixture f = paidTwoBrandOrder(null);
        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.a1())).getStatusCode().value()).isEqualTo(200);
        assertThat(paymentStatus(f.orderId())).isEqualTo("REFUNDED");

        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.a2())).getStatusCode().value()).isEqualTo(200);

        List<RefundCommand> calls = refundCalls();
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).idempotencyKey()).isNotEqualTo(calls.get(1).idempotencyKey());
        assertThat(reversals(f.orderId())).hasSize(2);
        ResponseEntity<Map> wholeOrder = rest.exchange("/admin/orders/" + f.orderId() + "/cancel", HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "CUSTOMER_REQUEST"), auth(f.adminToken())), Map.class);
        assertThat(wholeOrder.getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void brandWithShippingProblem_canBeCancelled() {
        Fixture f = paidTwoBrandOrder(null);
        assertThat(reportProblem(f.brandBToken(), f.orderId()).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.b1())).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void duplicateIdsInOneRequest_countOnce() {
        Fixture f = paidTwoBrandOrder(null);
        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.a1(), f.a1())).getStatusCode().value())
                .isEqualTo(200);
        assertThat(refundCalls()).hasSize(1);
        assertThat(refundCalls().get(0).amount()).isEqualByComparingTo(itemMoney(f.a1(), "customer_gross_after_discount"));
    }

    // ===== rejections — nothing written, no Mollie call =====

    @Test
    void shippedBrand_isRejected() {
        Fixture f = paidTwoBrandOrder(null);
        assertThat(ship(f.brandAToken(), f.orderId()).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.a1())).getStatusCode().value()).isEqualTo(409);
        verify(paymentProvider, never()).refundPayment(any());
    }

    @Test
    void unpaidOrder_isRejected_andStockUntouched() {
        Fixture f = unpaidTwoBrandOrder(null);
        ResponseEntity<Map> resp = cancelItems(f.adminToken(), f.orderId(), List.of(f.a1()));
        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(message(resp)).contains("whole-order cancel");
        assertThat(stockOfItem(f.a1())).isEqualTo(5);
        verify(paymentProvider, never()).refundPayment(any());
    }

    @Test
    void legacyRowWithoutPaidAmount_isRejected() {
        Fixture f = paidTwoBrandOrder(null);
        jdbc.update("UPDATE order_items SET customer_gross_after_discount = NULL WHERE id = ?", f.a1());
        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.a1())).getStatusCode().value()).isEqualTo(409);
        verify(paymentProvider, never()).refundPayment(any());
    }

    @Test
    void mixedBrands_isRejected() {
        Fixture f = paidTwoBrandOrder(null);
        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.a1(), f.b1())).getStatusCode().value())
                .isEqualTo(400);
        assertThat(itemRow(f.a1()).get("cancelled_at")).isNull();
        verify(paymentProvider, never()).refundPayment(any());
    }

    @Test
    void itemOfAnotherOrder_isRejected() {
        Fixture f = paidTwoBrandOrder(null);
        long listing = jdbc.queryForObject("SELECT listing_id_snapshot FROM order_items WHERE id = ?", Long.class, f.a1());
        long otherOrder = orderId(postOrder(f.customerToken(), null, List.of(item(listing, 1))));
        long foreignItem = itemIdOf(otherOrder, listing);

        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(foreignItem)).getStatusCode().value()).isEqualTo(400);
        verify(paymentProvider, never()).refundPayment(any());
    }

    @Test
    void alreadyCancelledItem_isRejected() {
        Fixture f = paidTwoBrandOrder(null);
        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.a1())).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<Map> again = cancelItems(f.adminToken(), f.orderId(), List.of(f.a1()));
        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(message(again)).contains("already cancelled");
        assertThat(refundCalls()).hasSize(1);
    }

    @Test
    void invalidBody_isRejectedBeforeAnything() {
        Fixture f = paidTwoBrandOrder(null);
        Map<String, Object> noReason = new HashMap<>();
        noReason.put("orderItemIds", List.of(f.a1()));
        ResponseEntity<Map> missingReason = rest.exchange("/admin/orders/" + f.orderId() + "/cancel-items",
                HttpMethod.POST, new HttpEntity<>(noReason, auth(f.adminToken())), Map.class);
        ResponseEntity<Map> emptyList = rest.exchange("/admin/orders/" + f.orderId() + "/cancel-items",
                HttpMethod.POST, new HttpEntity<>(Map.of("orderItemIds", List.of(), "reason", "OTHER"),
                        auth(f.adminToken())), Map.class);

        assertThat(missingReason.getStatusCode().value()).isEqualTo(400);
        assertThat(emptyList.getStatusCode().value()).isEqualTo(400);
        assertThat(itemRow(f.a1()).get("cancelled_at")).isNull();
        verify(paymentProvider, never()).refundPayment(any());
    }
}
```

- [ ] **Step 3: Run them to verify they fail**

Run: `./mvnw -q -o test -Dtest=AdminItemCancellationIntegrationTest`
Expected: FAIL. `/admin/orders/{id}/cancel-items` doesn't exist yet: every call returns 404 or 500.

- [ ] **Step 4: Create `CancelOrderItemsDto`**

```java
package com.enunas.backend.order.dto;

import com.enunas.backend.order.CancelReason;
import com.enunas.backend.validation.NoHtml;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Body of POST /admin/orders/{id}/cancel-items. Separate from CancelOrderDto on purpose: a
 * @NotEmpty orderItemIds there would break the whole-order cancel endpoint's validation.
 */
@Getter
@Setter
@NoArgsConstructor
public class CancelOrderItemsDto {

    @NotEmpty
    private List<Long> orderItemIds;

    @NotNull
    private CancelReason reason;

    @Size(max = 500)
    @NoHtml
    private String note;
}
```

- [ ] **Step 5: Create the event and the listener**

```java
package com.enunas.backend.order;

import java.math.BigDecimal;
import java.util.List;

/**
 * Published by OrderItemCancellationService.finalizeClaim — only once the refund is recorded, so a
 * released claim never tells anyone its items were cancelled. Handled AFTER_COMMIT.
 */
public record OrderItemsCancelledEvent(
        String buyerEmail,
        String orderNumber,
        String brandName,
        String brandEmail,
        List<String> itemDescriptions,
        BigDecimal refundAmount,
        String currency
) {}
```

```java
package com.enunas.backend.order;

import com.enunas.backend.user.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.stream.Collectors;

/**
 * Tells the customer which items were cancelled and refunded, and tells the brand which items not
 * to ship. Best-effort: an SMTP failure is logged and swallowed. The brand mail is ADVISORY — the
 * hard guarantee that a cancelled item never ships is OrderService's shipment guard (spec D17/D18).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderItemsCancelledEmailListener {

    private final EmailService emailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onItemsCancelled(OrderItemsCancelledEvent event) {
        String lines = event.itemDescriptions().stream().map(d -> "- " + d).collect(Collectors.joining("\n"));
        try {
            emailService.sendPlainTextEmail(
                    event.buyerEmail(),
                    "Artikel aus Bestellung " + event.orderNumber() + " storniert",
                    "Folgende Artikel von " + event.brandName() + " aus deiner Bestellung " + event.orderNumber()
                            + " wurden storniert:\n" + lines
                            + "\n\nWir erstatten dir " + event.refundAmount() + " " + event.currency()
                            + " auf dein ursprüngliches Zahlungsmittel. Das dauert je nach Bank 5–10 Werktage.");
        } catch (Exception ex) {
            log.error("EMAIL_DELIVERY_FAILURE type=items-cancelled-customer order={} reason={}",
                    event.orderNumber(), ex.getMessage());
        }
        try {
            emailService.sendPlainTextEmail(
                    event.brandEmail(),
                    "Stornierung: Bestellung " + event.orderNumber(),
                    "Bitte versende folgende Artikel der Bestellung " + event.orderNumber()
                            + " NICHT – sie wurden storniert und dem Kunden erstattet:\n" + lines);
        } catch (Exception ex) {
            log.error("EMAIL_DELIVERY_FAILURE type=items-cancelled-brand order={} reason={}",
                    event.orderNumber(), ex.getMessage());
        }
    }
}
```

- [ ] **Step 6: Make `OrderService.syncShipmentStatus` public**

Change `private Order syncShipmentStatus(Order order)` to `public Order syncShipmentStatus(Order order)`. Add one line to its javadoc: `Public so OrderItemCancellationService can re-run the rollup after a cancellation empties a brand (spec D27); it only ever moves forward, so a re-run is always safe.`

- [ ] **Step 7: Create `OrderItemCancellationService`**

```java
package com.enunas.backend.order;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.discount.DiscountService;
import com.enunas.backend.exception.OrderNotFoundException;
import com.enunas.backend.exception.PaymentException;
import com.enunas.backend.exception.PaymentRejectedException;
import com.enunas.backend.ledger.LedgerService;
import com.enunas.backend.order.dto.CancelOrderItemsDto;
import com.enunas.backend.order.dto.OrderResponseDto;
import com.enunas.backend.payment.Payment;
import com.enunas.backend.payment.PaymentProvider;
import com.enunas.backend.payment.PaymentRepository;
import com.enunas.backend.payment.PaymentStatus;
import com.enunas.backend.payment.RefundCommand;
import com.enunas.backend.product.productvariant.ProductVariantRepository;
import com.enunas.backend.user.User;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Pre-shipment cancellation of one brand's line items on a paid order (spec
 * docs/superpowers/specs/2026-09-28-per-item-cancellation-design.md).
 *
 * <p>Claim → refund → finalize. The claim (a short write transaction under the orders row lock)
 * stores the whole decision on the items — everything but the refund id — so a second overlapping
 * call sees them taken and 409s before money moves (D15). The Mollie call runs outside any
 * transaction. Finalize reads everything back from the database, so the normal path and the
 * reconcile RECORD action are the same code.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderItemCancellationService {

    /** A claim this old with no refund recorded is stuck, not in flight (spec D26). */
    static final Duration STUCK_AFTER = Duration.ofMinutes(5);

    /** CANCELLABLE ∪ {PARTIALLY_SHIPPED} (spec D7). PENDING passes this gate and is refused by D14. */
    private static final Set<OrderStatus> ITEM_CANCELLABLE = EnumSet.of(
            OrderStatus.PENDING, OrderStatus.PAID, OrderStatus.PARTIALLY_SHIPPED,
            OrderStatus.SHIPPING_PROBLEM, OrderStatus.AWAITING_ADMIN, OrderStatus.MANUAL_REVIEW);

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final OrderShipmentRepository orderShipmentRepository;
    private final OrderShippingSnapshotRepository orderShippingSnapshotRepository;
    private final ProductVariantRepository productVariantRepository;
    private final LedgerService ledgerService;
    private final DiscountService discountService;
    private final PaymentProvider paymentProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final EntityManager entityManager;
    private final OrderService orderService;

    /** What the refund step needs from a committed claim. */
    private record Claim(String claimKey, String orderNumber, String brandName, int itemCount,
                         String transactionId, BigDecimal refundAmount) {}

    @PreAuthorize("hasRole('ADMIN')")
    public OrderResponseDto cancelItems(Long orderId, CancelOrderItemsDto dto, User admin) {
        Claim claim = transactionTemplate.execute(s -> claim(orderId, dto, admin));
        String refundId = refund(orderId, claim);
        try {
            transactionTemplate.executeWithoutResult(s -> finalizeClaim(orderId, claim.claimKey(), refundId));
        } catch (RuntimeException e) {
            log.error("ITEM_CANCEL_RECORDING_FAILED: order {} claim {} — refund {} succeeded at the provider but "
                            + "was not recorded; reconcile with RECORD and this refund id: {}",
                    claim.orderNumber(), claim.claimKey(), refundId, e.getMessage());
            throw e;
        }
        return orderService.getOrderById(orderId);
    }

    private Claim claim(Long orderId, CancelOrderItemsDto dto, User admin) {
        Order order = lock(orderId);
        if (!ITEM_CANCELLABLE.contains(order.getStatus())) {
            throw new IllegalStateException("Items of order " + order.getOrderNumber()
                    + " cannot be cancelled from " + order.getStatus() + ".");
        }
        Payment payment = paymentRepository.findByOrderId(orderId)
                .filter(p -> p.getPaidAt() != null)
                .orElseThrow(() -> new IllegalStateException("Order " + order.getOrderNumber()
                        + " has no captured payment — use the whole-order cancel."));

        Set<Long> ids = new LinkedHashSet<>(dto.getOrderItemIds());
        List<OrderItem> targets = order.getItems().stream().filter(i -> ids.contains(i.getId())).toList();
        if (targets.size() != ids.size()) {
            throw new IllegalArgumentException("Not all of " + ids + " are items of order " + order.getOrderNumber() + ".");
        }
        Set<Long> brandIds = targets.stream().map(OrderItem::getBrandId).collect(Collectors.toSet());
        if (brandIds.size() != 1 || brandIds.contains(null)) {
            throw new IllegalArgumentException("All items of one cancellation must belong to exactly one brand.");
        }
        Long brandId = brandIds.iterator().next();

        for (OrderItem item : targets) {
            if (item.getCustomerGrossAfterDiscount() == null) {
                throw new IllegalStateException("Item " + item.getId() + " predates per-item pricing (legacy order) "
                        + "— use the whole-order cancel.");
            }
            if (item.isCancelled()) {
                throw new IllegalStateException(alreadyCancelledMessage(item));
            }
        }
        boolean brandShipped = orderShipmentRepository.findByOrder_IdAndBrand_Id(orderId, brandId)
                .map(s -> s.getStatus() == ShipmentStatus.SHIPPED).orElse(false);
        if (brandShipped) {
            throw new IllegalStateException("This brand has already shipped its items on order "
                    + order.getOrderNumber() + " — use the return flow.");
        }

        boolean includesShipping = order.getItems().stream()
                .filter(i -> brandId.equals(i.getBrandId()) && !ids.contains(i.getId()))
                .allMatch(OrderItem::isCancelled);
        String claimKey = "item-cancel-" + order.getOrderNumber() + "-" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        for (OrderItem item : targets) {
            item.setCancelledAt(now);
            item.setCancellationReason(dto.getReason());
            item.setCancellationNote(dto.getNote());
            item.setCancelledByAdminEmail(admin.getEmail());
            item.setCancellationIncludesShipping(includesShipping);
            item.setCancellationClaimKey(claimKey);
        }
        orderRepository.save(order);

        BrandPartner brand = targets.get(0).getVariant().getProduct().getBrand();
        return new Claim(claimKey, order.getOrderNumber(), brand.getBrandName(), targets.size(),
                payment.getTransactionId(), refundAmountOf(orderId, targets));
    }

    private String refund(Long orderId, Claim claim) {
        try {
            String refundId = paymentProvider.refundPayment(new RefundCommand(
                    claim.transactionId(),
                    claim.refundAmount(),
                    "Cancelled " + claim.itemCount() + " item(s) from " + claim.brandName()
                            + " on order " + claim.orderNumber(),
                    claim.claimKey())).refundId();
            if (refundId == null || refundId.isBlank()) {
                throw new PaymentException("the provider returned no refund id");
            }
            return refundId;
        } catch (PaymentRejectedException e) {
            transactionTemplate.executeWithoutResult(s -> releaseClaim(orderId, claim.claimKey()));
            throw new PaymentException("The payment provider rejected the refund — the items were NOT cancelled: "
                    + e.getMessage(), e);
        } catch (RuntimeException e) {
            log.error("ITEM_CANCEL_AMBIGUOUS: order {} claim {} — refund outcome unknown: {}",
                    claim.orderNumber(), claim.claimKey(), e.getMessage());
            throw new IllegalStateException("The refund outcome is unknown. Check Mollie for claim "
                    + claim.claimKey() + ", then reconcile it (RECORD with the refund id, or RELEASE if no "
                    + "refund exists).");
        }
    }

    /** Settles a claim with a refund that exists at the provider. Shared with reconcile RECORD. */
    void finalizeClaim(Long orderId, String claimKey, String refundId) {
        StaleSessionGuard.clear(entityManager);
        Order order = lock(orderId);
        List<OrderItem> items = claimItems(order, claimKey);
        if (items.stream().anyMatch(OrderItem::isCancellationSettled)) {
            throw new IllegalStateException("Claim " + claimKey + " is already settled.");
        }
        Long brandId = items.get(0).getBrandId();

        for (OrderItem item : items) {
            item.setRefundTransactionId(refundId);
            productVariantRepository.restoreStock(item.getVariant().getId(), item.getQuantity());
        }
        Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
        payment.setStatus(PaymentStatus.REFUNDED);
        paymentRepository.save(payment);

        ledgerService.recordItemCancellationReversal(order, items, refundId);
        if (Boolean.TRUE.equals(items.get(0).getCancellationIncludesShipping())) {
            ledgerService.reverseShippingForEmptiedBrand(order, brandId, refundId);
        }
        orderService.syncShipmentStatus(order);
        if (order.getItems().stream().allMatch(OrderItem::isCancellationSettled)) {
            order.setStatus(OrderStatus.CANCELLED);
            discountService.releaseUsageOnce(order);
        }
        orderRepository.save(order);

        BrandPartner brand = items.get(0).getVariant().getProduct().getBrand();
        String brandEmail = brand.getContactEmail() != null ? brand.getContactEmail() : brand.getUser().getEmail();
        eventPublisher.publishEvent(new OrderItemsCancelledEvent(
                order.getBuyer().getEmail(), order.getOrderNumber(), brand.getBrandName(), brandEmail,
                items.stream().map(i -> i.getProductSnapshotName() + " (" + i.getVariantSnapshotColor() + ", "
                        + i.getVariantSnapshotSize() + ") × " + i.getQuantity()).toList(),
                refundAmountOf(orderId, items), order.getCurrency()));
        log.info("Items {} of order {} cancelled, refund {}", items.stream().map(OrderItem::getId).toList(),
                order.getOrderNumber(), refundId);
    }

    /** Undoes a claim that moved no money. Shared by the definitive-failure path and reconcile RELEASE. */
    void releaseClaim(Long orderId, String claimKey) {
        StaleSessionGuard.clear(entityManager);
        Order order = lock(orderId);
        List<OrderItem> items = claimItems(order, claimKey);
        if (items.stream().anyMatch(OrderItem::isCancellationSettled)) {
            throw new IllegalStateException("Claim " + claimKey + " is settled — it cannot be released.");
        }
        for (OrderItem item : items) {
            item.setCancelledAt(null);
            item.setCancellationReason(null);
            item.setCancellationNote(null);
            item.setCancelledByAdminEmail(null);
            item.setCancellationIncludesShipping(null);
            item.setCancellationClaimKey(null);
        }
        orderRepository.save(order);

        Long brandId = items.get(0).getBrandId();
        boolean brandShipped = orderShipmentRepository.findByOrder_IdAndBrand_Id(orderId, brandId)
                .map(s -> s.getStatus() == ShipmentStatus.SHIPPED).orElse(false);
        if (brandShipped) {
            log.error("ITEM_CANCEL_ABORTED_AFTER_SHIPMENT: order {} claim {} — released items {} now read as part "
                            + "of a SHIPPED brand although nothing left for them; resolve by hand.",
                    order.getOrderNumber(), claimKey, items.stream().map(OrderItem::getId).toList());
        }
    }

    Order lock(Long orderId) {
        return orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found with id: " + orderId));
    }

    List<OrderItem> claimItems(Order order, String claimKey) {
        List<OrderItem> items = order.getItems().stream()
                .filter(i -> claimKey.equals(i.getCancellationClaimKey()))
                .toList();
        if (items.isEmpty()) {
            throw new IllegalStateException("No cancellation claim " + claimKey + " on order " + order.getOrderNumber() + ".");
        }
        return items;
    }

    /** Σ what the customer paid for the items, plus the brand's shipping when the claim empties it (D11/D12). */
    private BigDecimal refundAmountOf(Long orderId, List<OrderItem> items) {
        BigDecimal amount = items.stream().map(OrderItem::getCustomerGrossAfterDiscount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (Boolean.TRUE.equals(items.get(0).getCancellationIncludesShipping())) {
            Long brandId = items.get(0).getBrandId();
            amount = amount.add(orderShippingSnapshotRepository.findByOrderIdOrderByIdAsc(orderId).stream()
                    .filter(s -> brandId.equals(s.getBrandPartnerId()))
                    .map(OrderShippingSnapshot::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
        }
        return amount;
    }

    private String alreadyCancelledMessage(OrderItem item) {
        if (item.isCancellationSettled()) {
            return "Item " + item.getId() + " is already cancelled (refund " + item.getRefundTransactionId() + ").";
        }
        boolean stuck = item.getCancelledAt().isBefore(LocalDateTime.now().minus(STUCK_AFTER));
        return stuck
                ? "A cancellation of item " + item.getId() + " is stuck since " + item.getCancelledAt()
                        + " (claim " + item.getCancellationClaimKey() + ") — reconcile it."
                : "A cancellation of item " + item.getId() + " by " + item.getCancelledByAdminEmail()
                        + " is in progress (claim " + item.getCancellationClaimKey() + ").";
    }
}
```

- [ ] **Step 8: Add the endpoint to `AdminController`**

Add imports `com.enunas.backend.order.OrderItemCancellationService` and `com.enunas.backend.order.dto.CancelOrderItemsDto`, and add the field `private final OrderItemCancellationService orderItemCancellationService;`. Add the endpoint next to `cancelOrder`:

```java
    @PostMapping("/orders/{orderId}/cancel-items")
    public ResponseEntity<OrderResponseDto> cancelOrderItems(
            @PathVariable Long orderId,
            @Valid @RequestBody CancelOrderItemsDto dto,
            @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(orderItemCancellationService.cancelItems(orderId, dto, admin));
    }
```

- [ ] **Step 9: Run the new tests to verify they pass**

Run: `./mvnw -q -o test -Dtest=AdminItemCancellationIntegrationTest`
Expected: PASS (16 tests).

- [ ] **Step 10: Run all cancellation-related tests for regressions**

Run: `./mvnw -q -o test -Dtest='*Cancel*Test,*ItemCancellation*Test,ShippingWithCancelledItemsIntegrationTest,ReturnsWithCancelledItemsIntegrationTest'`
Expected: all PASS.

- [ ] **Step 11: Checkpoint**

Leave changes uncommitted. Touched: the four new main files, `OrderService.java`, `AdminController.java`, the test base, the new test.

---

### Task 8: Failure handling and the reconcile endpoint (D25, D26)

**Files:**
- Create: `backend/src/main/java/com/enunas/backend/order/dto/ReconcileItemCancellationDto.java`
- Modify: `backend/src/main/java/com/enunas/backend/order/OrderItemCancellationService.java`: add `reconcile`, `assertStuck`
- Modify: `backend/src/main/java/com/enunas/backend/admin/AdminController.java`: reconcile endpoint
- Modify: `backend/src/test/java/com/enunas/backend/order/integration/AbstractItemCancellationIntegrationTest.java`: add `reconcile`, `claimKeyOf`
- Test: `backend/src/test/java/com/enunas/backend/order/integration/AdminItemCancellationFailureIntegrationTest.java`

**Interfaces:**
- Consumes: Task 7's `finalizeClaim`, `releaseClaim`, `lock`, `claimItems` and `STUCK_AFTER`; Task 2 `PaymentRejectedException`.
- Produces:
  - `OrderItemCancellationService.reconcile(Long orderId, ReconcileItemCancellationDto dto, User admin) → OrderResponseDto`.
  - `POST /admin/orders/{orderId}/cancel-items/reconcile`.
  - Test base helpers `reconcile(String adminToken, long orderId, String claimKey, String action, String refundId)` and `claimKeyOf(long itemId)`.

- [ ] **Step 1: Add the helpers to the test base**

```java
    protected ResponseEntity<Map> reconcile(String adminToken, long orderId, String claimKey, String action,
                                            String refundId) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("claimKey", claimKey);
        body.put("action", action);
        if (refundId != null) body.put("refundId", refundId);
        return rest.exchange("/admin/orders/" + orderId + "/cancel-items/reconcile", HttpMethod.POST,
                new HttpEntity<>(body, auth(adminToken)), Map.class);
    }

    protected String claimKeyOf(long itemId) {
        return jdbc.queryForObject("SELECT cancellation_claim_key FROM order_items WHERE id = ?", String.class, itemId);
    }
```

- [ ] **Step 2: Write the failing tests**

```java
package com.enunas.backend.order.integration;

import com.enunas.backend.exception.PaymentException;
import com.enunas.backend.exception.PaymentRejectedException;
import com.enunas.backend.ledger.LedgerService;
import com.enunas.backend.payment.PaymentProvider;
import com.enunas.backend.user.EmailService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@SuppressWarnings("rawtypes")
@ExtendWith(OutputCaptureExtension.class)
class AdminItemCancellationFailureIntegrationTest extends AbstractItemCancellationIntegrationTest {

    @MockitoBean EmailService emailService;
    @MockitoSpyBean PaymentProvider paymentProvider;
    // Named ledgerSpy, not ledgerService: the base class already has an @Autowired ledgerService field
    // (it receives this same spy), and reusing the name would shadow it.
    @MockitoSpyBean LedgerService ledgerSpy;

    private static String message(ResponseEntity<Map> resp) {
        return String.valueOf(resp.getBody().get("message"));
    }

    private void backdateClaim(long itemId) {
        jdbc.update("UPDATE order_items SET cancelled_at = now() - interval '6 minutes' WHERE id = ?", itemId);
    }

    /** a1 left claimed with an unknown refund outcome. */
    private Fixture ambiguousClaimOnA1() {
        Fixture f = paidTwoBrandOrder(null);
        doThrow(new PaymentException("read timed out")).when(paymentProvider).refundPayment(any());
        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.a1())).getStatusCode().value()).isEqualTo(409);
        return f;
    }

    @Test
    void definitiveRejection_releasesTheClaim_andARetrySucceeds() {
        Fixture f = paidTwoBrandOrder(null);
        doThrow(new PaymentRejectedException("amount too high", null)).when(paymentProvider).refundPayment(any());

        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.a1())).getStatusCode().value()).isEqualTo(400);
        assertThat(itemRow(f.a1()).get("cancelled_at")).isNull();
        assertThat(itemRow(f.a1()).get("cancellation_claim_key")).isNull();

        doCallRealMethod().when(paymentProvider).refundPayment(any());
        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.a1())).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void ambiguousFailure_keepsTheClaim_andARetryMakesNoSecondCall(CapturedOutput output) {
        Fixture f = paidTwoBrandOrder(null);
        doThrow(new PaymentException("read timed out")).when(paymentProvider).refundPayment(any());

        ResponseEntity<Map> first = cancelItems(f.adminToken(), f.orderId(), List.of(f.a1()));
        assertThat(first.getStatusCode().value()).isEqualTo(409);
        assertThat(message(first)).contains("reconcile");
        assertThat(output).contains("ITEM_CANCEL_AMBIGUOUS");
        assertThat(itemRow(f.a1()).get("cancelled_at")).isNotNull();
        assertThat(itemRow(f.a1()).get("refund_transaction_id")).isNull();

        ResponseEntity<Map> retry = cancelItems(f.adminToken(), f.orderId(), List.of(f.a1()));
        assertThat(retry.getStatusCode().value()).isEqualTo(409);
        assertThat(message(retry)).contains("in progress");
        verify(paymentProvider, times(1)).refundPayment(any());
    }

    @Test
    void aClaimOlderThanFiveMinutes_isReportedStuck() {
        Fixture f = ambiguousClaimOnA1();
        backdateClaim(f.a1());
        ResponseEntity<Map> again = cancelItems(f.adminToken(), f.orderId(), List.of(f.a1()));
        assertThat(again.getStatusCode().value()).isEqualTo(409);
        assertThat(message(again)).contains("stuck");
    }

    @Test
    void finalizeFailingAfterARefund_keepsTheClaim_andLogsForReconcile(CapturedOutput output) {
        Fixture f = paidTwoBrandOrder(null);
        doThrow(new RuntimeException("ledger down")).when(ledgerSpy)
                .recordItemCancellationReversal(any(), any(), anyString());

        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.a1())).getStatusCode().value()).isEqualTo(500);
        assertThat(output).contains("ITEM_CANCEL_RECORDING_FAILED");
        assertThat(itemRow(f.a1()).get("cancelled_at")).isNotNull();
        assertThat(itemRow(f.a1()).get("refund_transaction_id")).isNull();
        assertThat(stockOfItem(f.a1())).isEqualTo(4);
        verify(paymentProvider, times(1)).refundPayment(any());
    }

    @Test
    void reconcileRecord_settlesAStuckClaim_withoutCallingMollie() {
        Fixture f = ambiguousClaimOnA1();
        backdateClaim(f.a1());

        ResponseEntity<Map> resp = reconcile(f.adminToken(), f.orderId(), claimKeyOf(f.a1()), "RECORD", "re_manual");

        assertThat(resp.getStatusCode().value()).as("%s", resp.getBody()).isEqualTo(200);
        assertThat(itemRow(f.a1()).get("refund_transaction_id")).isEqualTo("re_manual");
        assertThat(stockOfItem(f.a1())).isEqualTo(5);
        assertThat(reversals(f.orderId())).extracting(r -> r.get("external_reference_id")).containsExactly("re_manual");
        verify(paymentProvider, times(1)).refundPayment(any()); // only the original, ambiguous call
    }

    @Test
    void reconcileRecord_isRefusedWhileInProgress_whenSettled_andWithoutARefundId() {
        Fixture f = ambiguousClaimOnA1();
        String key = claimKeyOf(f.a1());

        assertThat(reconcile(f.adminToken(), f.orderId(), key, "RECORD", "re_x").getStatusCode().value()).isEqualTo(409);
        backdateClaim(f.a1());
        assertThat(reconcile(f.adminToken(), f.orderId(), key, "RECORD", null).getStatusCode().value()).isEqualTo(400);
        assertThat(reconcile(f.adminToken(), f.orderId(), key, "RECORD", "re_x").getStatusCode().value()).isEqualTo(200);
        assertThat(reconcile(f.adminToken(), f.orderId(), key, "RECORD", "re_y").getStatusCode().value()).isEqualTo(409);
    }

    @Test
    void reconcileRelease_freesTheItems_withoutCallingMollie() {
        Fixture f = ambiguousClaimOnA1();
        backdateClaim(f.a1());

        assertThat(reconcile(f.adminToken(), f.orderId(), claimKeyOf(f.a1()), "RELEASE", null).getStatusCode().value())
                .isEqualTo(200);
        assertThat(itemRow(f.a1()).get("cancelled_at")).isNull();
        assertThat(itemRow(f.a1()).get("cancellation_claim_key")).isNull();
        verify(paymentProvider, times(1)).refundPayment(any());
    }

    @Test
    void reconcileRelease_afterTheBrandShippedItsOtherItems_isFlagged(CapturedOutput output) {
        Fixture f = ambiguousClaimOnA1();
        assertThat(ship(f.brandAToken(), f.orderId()).getStatusCode().is2xxSuccessful()).isTrue(); // a2, a3 active
        backdateClaim(f.a1());

        assertThat(reconcile(f.adminToken(), f.orderId(), claimKeyOf(f.a1()), "RELEASE", null).getStatusCode().value())
                .isEqualTo(200);
        assertThat(output).contains("ITEM_CANCEL_ABORTED_AFTER_SHIPMENT");
    }

    @Test
    void reconcile_withForeignClaimKey_isRejected() {
        Fixture f = ambiguousClaimOnA1();
        backdateClaim(f.a1());

        ResponseEntity<Map> resp = reconcile(f.adminToken(), f.orderId(), "item-cancel-ENS-OTHER-x", "RELEASE", null);

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(itemRow(f.a1()).get("cancelled_at")).isNotNull();
        verify(paymentProvider, times(1)).refundPayment(any());
        verify(paymentProvider, never()).getPaymentDetails(anyString());
    }
}
```

- [ ] **Step 3: Run them to verify they fail**

Run: `./mvnw -q -o test -Dtest=AdminItemCancellationFailureIntegrationTest`
Expected: the reconcile tests FAIL (endpoint missing: 404/405). The failure-path tests may already pass because Task 7 implemented them. They pin D25.

- [ ] **Step 4: Create `ReconcileItemCancellationDto`**

```java
package com.enunas.backend.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Body of POST /admin/orders/{id}/cancel-items/reconcile (spec D26). */
@Getter
@Setter
@NoArgsConstructor
public class ReconcileItemCancellationDto {

    /** RECORD: a refund exists at Mollie — settle with it. RELEASE: none exists — free the items. */
    public enum Action { RECORD, RELEASE }

    @NotBlank
    private String claimKey;

    @NotNull
    private Action action;

    /** Required for RECORD. */
    @Size(max = 64)
    private String refundId;
}
```

- [ ] **Step 5: Add `reconcile` to `OrderItemCancellationService`**

Add `import com.enunas.backend.order.dto.ReconcileItemCancellationDto;` and these methods:

```java
    /**
     * Resolves a stuck claim by an admin's explicit decision, never automatically and never by a
     * second Mollie call (spec D26). RECORD settles with a refund the admin found at Mollie; RELEASE
     * frees the items when the admin confirmed no refund exists.
     */
    @PreAuthorize("hasRole('ADMIN')")
    public OrderResponseDto reconcile(Long orderId, ReconcileItemCancellationDto dto, User admin) {
        boolean record = dto.getAction() == ReconcileItemCancellationDto.Action.RECORD;
        if (record && (dto.getRefundId() == null || dto.getRefundId().isBlank())) {
            throw new IllegalArgumentException("RECORD needs the refund id from Mollie.");
        }
        transactionTemplate.executeWithoutResult(s -> {
            assertStuck(orderId, dto.getClaimKey());
            if (record) {
                finalizeClaim(orderId, dto.getClaimKey(), dto.getRefundId());
            } else {
                releaseClaim(orderId, dto.getClaimKey());
            }
        });
        log.warn("ITEM_CANCEL_RECONCILED: admin {} {} claim {} on order {}{}", admin.getEmail(), dto.getAction(),
                dto.getClaimKey(), orderId, record ? " with refund " + dto.getRefundId() : "");
        return orderService.getOrderById(orderId);
    }

    private void assertStuck(Long orderId, String claimKey) {
        List<OrderItem> items = claimItems(lock(orderId), claimKey);
        if (items.stream().anyMatch(OrderItem::isCancellationSettled)) {
            throw new IllegalStateException("Claim " + claimKey + " is already settled — nothing to reconcile.");
        }
        if (items.get(0).getCancelledAt().isAfter(LocalDateTime.now().minus(STUCK_AFTER))) {
            throw new IllegalStateException("Claim " + claimKey + " is still in progress — reconcile only after "
                    + STUCK_AFTER.toMinutes() + " minutes.");
        }
    }
```

- [ ] **Step 6: Add the endpoint to `AdminController`**

Add `import com.enunas.backend.order.dto.ReconcileItemCancellationDto;` and:

```java
    @PostMapping("/orders/{orderId}/cancel-items/reconcile")
    public ResponseEntity<OrderResponseDto> reconcileItemCancellation(
            @PathVariable Long orderId,
            @Valid @RequestBody ReconcileItemCancellationDto dto,
            @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(orderItemCancellationService.reconcile(orderId, dto, admin));
    }
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw -q -o test -Dtest=AdminItemCancellationFailureIntegrationTest`
Expected: PASS (9 tests).

- [ ] **Step 8: Checkpoint**

Leave changes uncommitted. Touched: `ReconcileItemCancellationDto.java`, `OrderItemCancellationService.java`, `AdminController.java`, the test base, the new test.

---

### Task 9: Concurrency (D11, D15, D18, D23)

**Files:**
- Test: `backend/src/test/java/com/enunas/backend/order/integration/AdminItemCancellationConcurrencyIntegrationTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1–8. This task only adds tests. If any fails, the fix belongs in the task whose code it exercises. Make it there and rerun that task's tests.

- [ ] **Step 1: Write the tests**

```java
package com.enunas.backend.order.integration;

import com.enunas.backend.payment.PaymentProvider;
import com.enunas.backend.payment.RefundCommand;
import com.enunas.backend.user.EmailService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AdminItemCancellationConcurrencyIntegrationTest extends AbstractItemCancellationIntegrationTest {

    @MockitoBean EmailService emailService;
    @MockitoSpyBean PaymentProvider paymentProvider;

    private List<Integer> concurrently(Callable<Integer> a, Callable<Integer> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> fa = pool.submit(() -> { start.await(); return a.call(); });
        Future<Integer> fb = pool.submit(() -> { start.await(); return b.call(); });
        start.countDown();
        List<Integer> codes = List.of(fa.get(30, TimeUnit.SECONDS), fb.get(30, TimeUnit.SECONDS));
        pool.shutdown();
        return codes;
    }

    private List<RefundCommand> refundCalls() {
        ArgumentCaptor<RefundCommand> c = ArgumentCaptor.forClass(RefundCommand.class);
        verify(paymentProvider, atLeast(0)).refundPayment(c.capture());
        return c.getAllValues();
    }

    @Test
    void overlappingCalls_refundTheSharedItemExactlyOnce() throws Exception {
        Fixture f = paidTwoBrandOrder(null);

        List<Integer> codes = concurrently(
                () -> cancelItems(f.adminToken(), f.orderId(), List.of(f.a1(), f.a2())).getStatusCode().value(),
                () -> cancelItems(f.adminToken(), f.orderId(), List.of(f.a2(), f.a3())).getStatusCode().value());

        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        verify(paymentProvider, times(1)).refundPayment(any());
        assertThat(reversals(f.orderId())).hasSize(1);
        assertThat(itemRow(f.a2()).get("refund_transaction_id")).isNotNull();
    }

    @Test
    void twoCallsThatTogetherEmptyABrand_refundItsShippingExactlyOnce() throws Exception {
        Fixture f = paidTwoBrandOrder(null);

        List<Integer> codes = concurrently(
                () -> cancelItems(f.adminToken(), f.orderId(), List.of(f.a1(), f.a2())).getStatusCode().value(),
                () -> cancelItems(f.adminToken(), f.orderId(), List.of(f.a3())).getStatusCode().value());

        assertThat(codes).containsExactly(200, 200);
        long shippingRows = reversals(f.orderId()).stream()
                .filter(r -> ((String) r.get("external_reference_id")).endsWith(":SHIPPING")).count();
        assertThat(shippingRows).isEqualTo(1);
        BigDecimal refunded = refundCalls().stream().map(RefundCommand::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(refunded).isEqualByComparingTo(itemMoney(f.a1(), "customer_gross_after_discount")
                .add(itemMoney(f.a2(), "customer_gross_after_discount"))
                .add(itemMoney(f.a3(), "customer_gross_after_discount"))
                .add(shippingOf(f.orderId(), f.brandAId())));
    }

    @Test
    void brandShippingDuringTheRefund_neverShipsTheClaimedItem() {
        Fixture f = paidTwoBrandOrder(null);
        doAnswer(inv -> {
            assertThat(ship(f.brandAToken(), f.orderId()).getStatusCode().is2xxSuccessful()).isTrue(); // a2, a3 active
            return inv.callRealMethod();
        }).when(paymentProvider).refundPayment(any());

        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.a1())).getStatusCode().value()).isEqualTo(200);

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(emailService, atLeastOnce()).sendHtmlEmail(eq("customer@it.local"), anyString(), html.capture());
        List<String> mails = html.getAllValues();
        assertThat(mails.get(mails.size() - 1)).contains(productName(f.a2())).doesNotContain(productName(f.a1()));
    }
}
```

- [ ] **Step 2: Run them**

Run: `./mvnw -q -o test -Dtest=AdminItemCancellationConcurrencyIntegrationTest`
Expected: PASS (3 tests). A failure here is a real design defect. Fix it in the owning task's code (claim in Task 7, locks in Task 4), then rerun both this class and that task's class.

- [ ] **Step 3: Checkpoint**

Leave changes uncommitted. Touched: the new test.

---

### Task 10: Full verification

**Files:** none beyond fixes to regressions found here.

- [ ] **Step 1: Run the whole suite in the background**

Run (from `backend/`): `./mvnw -q -o test > "$TEMP/item-cancel-full.log" 2>&1; echo EXIT=$?`
Expected: `EXIT=0`. Surefire summaries sit in `target/surefire-reports/*.txt`. Check each new class and every class touched by Tasks 3–5 reports `Failures: 0, Errors: 0`.

- [ ] **Step 2: Fix any regression in the task that owns the code, then rerun that task's tests and this step**

- [ ] **Step 3: Invoke `superpowers:verification-before-completion`**

Show the test evidence before claiming the feature is done.

- [ ] **Step 4: Final checkpoint**

Leave everything uncommitted and list every touched file for the user. Remind them: `V36__order_item_cancellation.sql` (and the still-uncommitted `V35__return_order_version.sql`) must be staged together with the code. Without it `ddl-auto: validate` refuses to start the app.
