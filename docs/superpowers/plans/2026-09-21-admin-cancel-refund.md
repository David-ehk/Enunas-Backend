# Admin Cancel Refund Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An admin cancelling a paid order refunds the customer at Mollie, records the refund id, and reverses the ledger under it — or fails cleanly, changing nothing.

**Architecture:** `OrderService.cancelOrder` becomes a non-transactional three-phase orchestrator (validate → refund outside any transaction → persist under a row lock), the same shape as the existing `processRefund` and `confirmPaymentByWebhook`. `updateOrderStatus` becomes a non-transactional dispatcher that delegates CANCELLED to `cancelOrder` for one deprecation release.

**Tech Stack:** Java 21, Spring Boot 4.0.5, Spring Data JPA, Flyway, PostgreSQL, Mollie SDK 1.5.4, JUnit 5 + AssertJ + Mockito, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-09-21-admin-cancel-refund-design.md`

## Global Constraints

- **Do NOT run `git commit`.** David stages and commits himself. Each task ends with a verification run and a STOP; the commit command is given for him to run, never for the agent.
- **Never edit an applied Flyway migration.** New `V34__` script only.
- **The Mollie call must never run inside a DB transaction.** Phases 1 and 3 have their own transactions; phase 2 has none.
- **Do not run `mvn clean`** — OneDrive file locks break it. Use `./mvnw test` directly.
- Tests: `@ActiveProfiles({"test", "mock-payments"})`, extend `AbstractDiscountIntegrationTest`, Docker must be running.
- Run a single test class with `./mvnw test -Dtest=ClassName` from `backend/`.
- Amounts: refund and ledger reversal both read `payment.getAmount()`.
- Idempotency keys: `order-cancel-<orderNumber>`, `late-refund-<orderNumber>`.

## File Structure

| File | Responsibility |
|---|---|
| `payment/RefundCommand.java` | Modify: gains `idempotencyKey` |
| `payment/MolliePaymentService.java` | Modify: passes the key to the SDK |
| `payment/mock/MockPaymentService.java` | Modify: replays the same `refundId` per key |
| `payment/mock/MockPaymentStore.java` | Modify: key→refundId map + `reset()` |
| `db/migration/V34__order_refund_transaction_id.sql` | Create: nullable column |
| `order/Order.java` | Modify: `refundTransactionId` field |
| `order/dto/OrderResponseDto.java` | Modify: `cancellationReason`, `refundTransactionId` |
| `order/OrderService.java` | Modify: three-phase `cancelOrder`, dispatcher `updateOrderStatus`, DTO mapping |
| `order/OrderCancelledEvent.java` | Modify: nullable `refundAmount`, `currency` |
| `order/OrderCancelledEmailListener.java` | Modify: refund sentence |
| `admin/AdminController.java` | Modify: pass `@AuthenticationPrincipal User admin` |
| `test/.../AdminCancelRefundIntegrationTest.java` | Create: the 10 spec scenarios |

---

### Task 1: Idempotency key plumbing

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/payment/RefundCommand.java`
- Modify: `backend/src/main/java/com/enunas/backend/payment/MolliePaymentService.java:64-90`
- Modify: `backend/src/main/java/com/enunas/backend/payment/mock/MockPaymentService.java:37-50`
- Modify: `backend/src/main/java/com/enunas/backend/payment/mock/MockPaymentStore.java`
- Modify: `backend/src/main/java/com/enunas/backend/order/OrderService.java` (two existing `new RefundCommand(...)` sites)
- Modify: `backend/src/test/java/com/enunas/backend/discount/integration/AbstractDiscountIntegrationTest.java:80-87`
- Test: `backend/src/test/java/com/enunas/backend/payment/mock/MockRefundIdempotencyTest.java`

**Interfaces:**
- Produces: `RefundCommand(String paymentId, BigDecimal amount, String reason, String idempotencyKey)`; `MockPaymentStore.reset()`.

- [ ] **Step 1: Write the failing test**

```java
package com.enunas.backend.payment.mock;

import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.payment.PaymentProvider;
import com.enunas.backend.payment.RefundCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockRefundIdempotencyTest extends AbstractDiscountIntegrationTest {

    @Autowired PaymentProvider paymentProvider;

    @Test
    void sameKeyReturnsSameRefundId() {
        seedCustomer();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        long listing = seedListing(a.brand(), a.user(), "119.00", 5);
        String token = login("customer@it.local", "Customer123!");
        long oid = orderId(postOrder(token, null, List.of(item(listing, 1))));
        String txId = (String) jdbc.queryForMap("SELECT transaction_id FROM payments WHERE order_id = ?", oid)
                .get("transaction_id");

        String first = paymentProvider.refundPayment(
                new RefundCommand(txId, new BigDecimal("123.99"), "test", "order-cancel-X")).refundId();
        String second = paymentProvider.refundPayment(
                new RefundCommand(txId, new BigDecimal("123.99"), "test", "order-cancel-X")).refundId();

        assertThat(second).isEqualTo(first);
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./mvnw test -Dtest=MockRefundIdempotencyTest`
Expected: compile error — `RefundCommand` takes 3 arguments.

- [ ] **Step 3: Add the key to `RefundCommand`**

```java
public record RefundCommand(
        String paymentId,
        BigDecimal amount,
        String reason,
        String idempotencyKey
) {}
```

- [ ] **Step 4: Pass it through `MolliePaymentService.refundPayment`**

Replace the builder chain (leave the rest of the method untouched):

```java
            var call = mollieClient.refunds().create()
                    .paymentId(command.paymentId())
                    .refundRequest(refundRequest);
            if (command.idempotencyKey() != null) {
                call = call.idempotencyKey(command.idempotencyKey());
            }
            CreateRefundResponse response = call.call();
```

- [ ] **Step 5: Make the mock replay by key**

In `MockPaymentStore`, add alongside the existing `payments` map:

```java
    private final ConcurrentHashMap<String, String> refundIdsByKey = new ConcurrentHashMap<>();

    /** Returns the refundId already issued for this key, or null. */
    public String refundIdForKey(String key) {
        return key == null ? null : refundIdsByKey.get(key);
    }

    public void rememberRefundKey(String key, String refundId) {
        if (key != null) refundIdsByKey.put(key, refundId);
    }

    /** Clears all mock provider state. Tests truncate the DB with RESTART IDENTITY, so without this
     *  an order id — and therefore an idempotency key — recurs in the next test and collides. */
    public void reset() {
        payments.clear();
        refundIdsByKey.clear();
    }
```

In `MockPaymentService.refundPayment`, before generating a new id:

```java
        String existing = store.refundIdForKey(command.idempotencyKey());
        if (existing != null) {
            log.info("MockPaymentService: replaying refund {} for idempotency key {}",
                    existing, command.idempotencyKey());
            return new RefundResult(existing);
        }
```

and after `store.markRefunded(...)`: `store.rememberRefundKey(command.idempotencyKey(), refundId);`

- [ ] **Step 6: Update the two existing call sites in `OrderService`**

In `refundPaymentForCancelledOrder`, add the key as the 4th argument:
`"late-refund-" + order.getOrderNumber()`.
In `processRefund` (return refunds), pass `null` — returns are one-shot and guarded by
`ReturnStatus`, so keying them is out of scope for this change.

- [ ] **Step 7: Call `reset()` from the test base class**

In `AbstractDiscountIntegrationTest.cleanDatabase()`, after the `TRUNCATE`, add:

```java
        mockPaymentStore.reset();
```

with `@Autowired(required = false) protected MockPaymentStore mockPaymentStore;` and a null guard
(the bean only exists under the `mock-payments` profile).

- [ ] **Step 8: Run the tests**

Run: `./mvnw test -Dtest=MockRefundIdempotencyTest,LatePaymentAutoRefundIntegrationTest`
Expected: PASS.

- [ ] **Step 9: STOP — David commits**

```bash
git add backend/src/main/java/com/enunas/backend/payment backend/src/test/java/com/enunas/backend/payment
git commit -m "feat(payment): idempotency keys on refunds"
```

---

### Task 2: Schema, entity and DTO fields

**Files:**
- Create: `backend/src/main/resources/db/migration/V34__order_refund_transaction_id.sql`
- Modify: `backend/src/main/java/com/enunas/backend/order/Order.java` (near `cancelledByAdminEmail:103`)
- Modify: `backend/src/main/java/com/enunas/backend/order/dto/OrderResponseDto.java`
- Modify: `backend/src/main/java/com/enunas/backend/order/OrderService.java` (`toDto`, `withPaymentId`, `toBrandScopedDto`)
- Test: `backend/src/test/java/com/enunas/backend/order/integration/CancellationFieldsDtoTest.java`

**Interfaces:**
- Produces: `Order.getRefundTransactionId()/setRefundTransactionId(String)`; DTO fields `cancellationReason`, `refundTransactionId`.

- [ ] **Step 1: Write the failing test**

```java
package com.enunas.backend.order.integration;

import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CancellationFieldsDtoTest extends AbstractDiscountIntegrationTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void customerSeesReasonBrandNeverSeesNote() {
        seedCustomer();
        seedAdmin();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        long listing = seedListing(a.brand(), a.user(), "119.00", 5);
        String cust = login("customer@it.local", "Customer123!");
        String admin = login("admin@it.local", "Admin123!");
        long oid = orderId(postOrder(cust, null, List.of(item(listing, 1))));

        ResponseEntity<Map> cancelled = rest.exchange("/admin/orders/" + oid + "/cancel", HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "CUSTOMER_REQUEST", "note", "internal wording"), auth(admin)),
                Map.class);
        assertThat(cancelled.getStatusCode().is2xxSuccessful()).as("cancel: %s", cancelled.getBody()).isTrue();
        assertThat(cancelled.getBody().get("cancellationReason")).isEqualTo("CUSTOMER_REQUEST");
        assertThat(cancelled.getBody()).containsKey("refundTransactionId");

        String brandToken = login("brand-a@it.local", "Brand123!");
        ResponseEntity<Map> brandView = rest.exchange("/brand/orders", HttpMethod.GET,
                new HttpEntity<>(null, auth(brandToken)), Map.class);
        List<Map<String, Object>> content = (List<Map<String, Object>>) brandView.getBody().get("content");
        assertThat(content).isNotEmpty();
        assertThat(content.get(0)).containsKey("cancellationReason");
        assertThat(content.get(0).get("cancellationNote")).isNull();
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `./mvnw test -Dtest=CancellationFieldsDtoTest`
Expected: FAIL — `cancellationReason` missing from the response body.

- [ ] **Step 3: Write the migration**

`V34__order_refund_transaction_id.sql`:

```sql
-- Mollie refund id for an admin cancellation of a paid order. Denormalised from the ledger's
-- external_reference_id so the admin dashboard need not join ledger_entries. Null for orders that
-- were never paid, and for every order predating this column.
ALTER TABLE orders ADD COLUMN refund_transaction_id VARCHAR(64);
```

- [ ] **Step 4: Add the entity field**

In `Order.java`, next to `cancelledByAdminEmail`:

```java
    /** Mollie refund id when this cancellation refunded a captured payment. Null otherwise. */
    @Column(length = 64)
    private String refundTransactionId;
```

- [ ] **Step 5: Add the DTO fields and map them**

In `OrderResponseDto`, beside `molliePaymentId`:

```java
    /** Why an admin cancelled. Null unless the order is CANCELLED. */
    private CancelReason cancellationReason;

    /** Mollie refund id if the cancellation refunded a captured payment. */
    private String refundTransactionId;
```

(import `com.enunas.backend.order.CancelReason`), and in `OrderResponseDto.from`, add
`.cancellationReason(order.getCancellationReason())` and
`.refundTransactionId(order.getRefundTransactionId())`.

In `OrderService.toBrandScopedDto`'s builder add **only**:

```java
                .cancellationReason(order.getCancellationReason())
```

`cancellationNote` stays off every DTO — it carries the internal `REFUND_REQUIRED` /
`AUTO_REFUNDED` markers.

- [ ] **Step 6: Run the tests**

Run: `./mvnw test -Dtest=CancellationFieldsDtoTest,BrandOrderIsolationIntegrationTest`
Expected: PASS.

- [ ] **Step 7: STOP — David commits**

```bash
git add backend/src/main/resources/db/migration/V34__order_refund_transaction_id.sql backend/src/main/java/com/enunas/backend/order backend/src/test/java/com/enunas/backend/order
git commit -m "feat(order): record and expose the cancellation refund id"
```

---

### Task 3: Three-phase cancelOrder with refund

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/order/OrderService.java:1169` (`cancelOrder`)
- Test: `backend/src/test/java/com/enunas/backend/order/integration/AdminCancelRefundIntegrationTest.java`

**Interfaces:**
- Consumes: `RefundCommand(.., idempotencyKey)` (Task 1), `Order.setRefundTransactionId` (Task 2).
- Produces: `cancelOrder(Long, CancelOrderDto, User)` — non-transactional, refunds paid orders.

- [ ] **Step 1: Write the failing tests**

```java
package com.enunas.backend.order.integration;

import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.payment.PaymentProvider;
import com.enunas.backend.payment.RefundCommand;
import com.enunas.backend.user.EmailService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AdminCancelRefundIntegrationTest extends AbstractDiscountIntegrationTest {

    @MockitoBean EmailService emailService;
    @MockitoSpyBean PaymentProvider paymentProvider;

    @Test
    void paidCancel_refundsReversesAndRecords() {
        Fixture f = paidOrder();

        ResponseEntity<Map> resp = cancel(f.adminToken(), f.orderId(), "CUSTOMER_REQUEST", "mailed us");

        assertThat(resp.getStatusCode().is2xxSuccessful()).as("cancel: %s", resp.getBody()).isTrue();
        Map<String, Object> order = orderRow(f.orderId());
        assertThat(order.get("status")).isEqualTo("CANCELLED");
        assertThat(order.get("cancellation_reason")).isEqualTo("CUSTOMER_REQUEST");
        assertThat(order.get("cancelled_by_admin_email")).isEqualTo("admin@it.local");
        String refundId = (String) order.get("refund_transaction_id");
        assertThat(refundId).startsWith("ref_mock_");

        assertThat(paymentRow(f.orderId()).get("status")).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE order_id = ? AND entry_type = 'REFUND_REVERSAL'"
                        + " AND external_reference_id LIKE ?", Integer.class, f.orderId(), refundId + "%"))
                .isPositive();
        assertThat(brandPending(f.brandId())).isEqualByComparingTo("0.00");
        assertThat(stock(f.orderId())).isEqualTo(5);
        verify(paymentProvider, times(1)).refundPayment(any(RefundCommand.class));
        // Subject only: the refund sentence in the body is added by Task 5, which tightens this assertion.
        verify(emailService).sendPlainTextEmail(anyString(), contains("storniert"), anyString());
    }

    @Test
    void refundFailure_changesNothing() {
        Fixture f = paidOrder();
        doThrow(new RuntimeException("Mollie down")).when(paymentProvider).refundPayment(any());

        ResponseEntity<Map> resp = cancel(f.adminToken(), f.orderId(), "CUSTOMER_REQUEST", null);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        Map<String, Object> order = orderRow(f.orderId());
        assertThat(order.get("status")).isEqualTo("PAID");
        assertThat(order.get("refund_transaction_id")).isNull();
        assertThat(paymentRow(f.orderId()).get("status")).isEqualTo("PAID");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE order_id = ? AND entry_type = 'REFUND_REVERSAL'",
                Integer.class, f.orderId())).isZero();
    }

    @Test
    void unpaidCancel_doesNotCallMollie() {
        Fixture f = pendingOrder();

        ResponseEntity<Map> resp = cancel(f.adminToken(), f.orderId(), "CUSTOMER_REQUEST", null);

        assertThat(resp.getStatusCode().is2xxSuccessful()).as("cancel: %s", resp.getBody()).isTrue();
        assertThat(orderRow(f.orderId()).get("status")).isEqualTo("CANCELLED");
        assertThat(orderRow(f.orderId()).get("refund_transaction_id")).isNull();
        verify(paymentProvider, times(0)).refundPayment(any());
    }

    // ===== helpers =====

    record Fixture(long orderId, long brandId, String adminToken, String customerToken) {}

    private Fixture pendingOrder() {
        seedCustomer();
        seedAdmin();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        long listing = seedListing(a.brand(), a.user(), "119.00", 5);
        String cust = login("customer@it.local", "Customer123!");
        String admin = login("admin@it.local", "Admin123!");
        long oid = orderId(postOrder(cust, null, List.of(item(listing, 1))));
        return new Fixture(oid, a.brand().getId(), admin, cust);
    }

    private Fixture paidOrder() {
        Fixture f = pendingOrder();
        confirmPaid(f.orderId());
        return f;
    }

    @SuppressWarnings("rawtypes")
    ResponseEntity<Map> cancel(String adminToken, long oid, String reason, String note) {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("reason", reason);
        if (note != null) body.put("note", note);
        return rest.exchange("/admin/orders/" + oid + "/cancel", HttpMethod.POST,
                new HttpEntity<>(body, auth(adminToken)), Map.class);
    }

    Map<String, Object> paymentRow(long oid) {
        return jdbc.queryForMap("SELECT * FROM payments WHERE order_id = ?", oid);
    }

    int stock(long oid) {
        return jdbc.queryForObject(
                "SELECT pv.stock_quantity FROM product_variants pv JOIN order_items oi ON oi.variant_id = pv.id"
                        + " WHERE oi.order_id = ?", Integer.class, oid);
    }
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./mvnw test -Dtest=AdminCancelRefundIntegrationTest`
Expected: FAIL — `paidCancel_refundsReversesAndRecords` gets 409 ("Only PENDING orders can be cancelled").

- [ ] **Step 3: Rewrite `cancelOrder` as the three-phase orchestrator**

Replace the existing method (keep `@PreAuthorize`, drop `@Transactional`):

```java
    /**
     * Cancels an order and, when money was captured, refunds it at the provider first.
     *
     * <p>Deliberately NOT {@code @Transactional}: phase 2 is an outbound Mollie call and must not
     * hold a pooled DB connection (same rule as {@link #processRefund}). Nothing is written until
     * the refund succeeds, so a provider failure leaves no half-state.
     */
    @PreAuthorize("hasRole('ADMIN')")
    public OrderResponseDto cancelOrder(Long orderId, CancelOrderDto dto, User admin) {
        CancelPreflight pre = transactionTemplate.execute(s -> validateCancellable(orderId));

        String refundId = null;
        if (pre.wasPaid()) {
            try {
                refundId = paymentProvider.refundPayment(new RefundCommand(
                        pre.transactionId(),
                        pre.amount(),
                        "Admin cancellation of order " + pre.orderNumber(),
                        "order-cancel-" + pre.orderNumber())).refundId();
            } catch (Exception e) {
                log.error("Admin cancel: refund FAILED for order {} — order NOT cancelled: {}",
                        pre.orderNumber(), e.getMessage());
                throw new PaymentException("Refund failed at the payment provider — the order was NOT "
                        + "cancelled. Please retry: " + e.getMessage(), e);
            }
        }
        final String finalRefundId = refundId;
        CancelOutcome outcome = transactionTemplate.execute(s -> persistCancellation(orderId, dto, admin, finalRefundId));
        if (outcome.shippedDuringRefund()) {
            // Thrown only AFTER the transaction has committed. Throwing inside it would roll back the
            // very refund record persistCancellation just wrote — forgetting money already returned.
            throw new IllegalStateException("Order " + outcome.orderNumber() + " shipped while the refund was in "
                    + "flight. The refund was issued and recorded; the order was NOT cancelled.");
        }
        return outcome.response();
    }

    /** Phase 3's result: either the cancelled order, or "shipped mid-refund" (nothing cancelled). */
    private record CancelOutcome(OrderResponseDto response, boolean shippedDuringRefund, String orderNumber) {
        static CancelOutcome done(OrderResponseDto response) { return new CancelOutcome(response, false, null); }
        static CancelOutcome shipped(String orderNumber) { return new CancelOutcome(null, true, orderNumber); }
    }

    /** What phase 1 establishes, carried into phases 2 and 3. */
    private record CancelPreflight(String orderNumber, boolean wasPaid, String transactionId, BigDecimal amount) {}

    private static final Set<OrderStatus> CANCELLABLE = EnumSet.of(
            OrderStatus.PENDING, OrderStatus.PAID, OrderStatus.SHIPPING_PROBLEM,
            OrderStatus.AWAITING_ADMIN, OrderStatus.MANUAL_REVIEW);

    private CancelPreflight validateCancellable(Long orderId) {
        Order order = findById(orderId);
        if (!CANCELLABLE.contains(order.getStatus())) {
            throw new IllegalStateException("Order " + order.getOrderNumber() + " cannot be cancelled from "
                    + order.getStatus() + ".");
        }
        if (anyBrandHasShipped(order)) {
            throw new IllegalStateException("Order " + order.getOrderNumber() + " cannot be cancelled — at least "
                    + "one brand has already shipped. Use a return/refund instead.");
        }
        Payment payment = paymentRepository.findByOrderId(orderId).orElse(null);
        boolean wasPaid = payment != null && payment.getStatus() == PaymentStatus.PAID && payment.getPaidAt() != null;
        if (payment != null && payment.getStatus() == PaymentStatus.REFUNDED) {
            throw new IllegalStateException("Order " + order.getOrderNumber() + " already has a refund — use the "
                    + "return/refund flow instead of cancelling.");
        }
        if (wasPaid && payment.getAmount().compareTo(order.getTotal()) != 0) {
            log.error("CANCEL_AMOUNT_MISMATCH: order {} total {} != captured payment {}",
                    order.getOrderNumber(), order.getTotal(), payment.getAmount());
            throw new IllegalStateException("Order " + order.getOrderNumber() + " has a captured amount that "
                    + "differs from its total — refusing to cancel; this needs a manual check.");
        }
        return new CancelPreflight(order.getOrderNumber(), wasPaid,
                wasPaid ? payment.getTransactionId() : null,
                wasPaid ? payment.getAmount() : null);
    }

    private CancelOutcome persistCancellation(Long orderId, CancelOrderDto dto, User admin, String refundId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found with id: " + orderId));

        if (order.getStatus() == OrderStatus.CANCELLED) {
            throw new IllegalStateException("Order " + order.getOrderNumber() + " was already cancelled"
                    + (order.getRefundTransactionId() != null
                        ? " (refund " + order.getRefundTransactionId() + ")" : "") + ".");
        }
        if (!CANCELLABLE.contains(order.getStatus())) {
            // A brand confirmed shipment while the refund was in flight (confirmShipment →
            // syncShipmentStatus rewrites the order status). The goods are gone, so do NOT cancel:
            // that would restore stock for a parcel in transit and claw back a delivered payout.
            // The money HAS gone back, so record only that and leave the rest to a human.
            if (refundId != null) {
                paymentRepository.findByOrderId(orderId).ifPresent(p -> {
                    p.setStatus(PaymentStatus.REFUNDED);
                    paymentRepository.save(p);
                });
                order.setRefundTransactionId(refundId);
                orderRepository.save(order);
                log.error("CANCEL_RACE_SHIPPED: order {} shipped during its refund; refund {} recorded, order NOT "
                        + "cancelled. Customer holds goods and money — resolve manually.",
                        order.getOrderNumber(), refundId);
            }
            return CancelOutcome.shipped(order.getOrderNumber());
        }

        order.setCancellationReason(dto.getReason());
        order.setCancellationNote(dto.getNote());
        order.setCancelledByAdminEmail(admin.getEmail());
        order.setRefundTransactionId(refundId);
        order.setStatus(OrderStatus.CANCELLED);

        BigDecimal refundedAmount = null;
        if (refundId != null) {
            Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
            refundedAmount = payment.getAmount();
            restoreVariantStock(order);
            ledgerService.recordRefund(order, refundedAmount, refundId);
            payment.setStatus(PaymentStatus.REFUNDED);
            paymentRepository.save(payment);
        }
        orderRepository.save(order);
        releaseDiscountUsageOnce(order);

        eventPublisher.publishEvent(new OrderCancelledEvent(
                order.getBuyer().getEmail(), order.getOrderNumber(), dto.getReason(), dto.getNote(),
                refundedAmount, order.getCurrency()));

        log.info("Order {} cancelled by admin {} — reason: {}{}", order.getOrderNumber(), admin.getEmail(),
                dto.getReason(), refundId != null ? ", refund " + refundId : "");
        return CancelOutcome.done(withPaymentId(OrderResponseDto.from(order, mediaUrlResolver), order.getId()));
    }
```

Add imports as needed (`EnumSet`, `Set`, `PaymentException`, `RefundCommand`, `PaymentStatus`).

- [ ] **Step 4: Extend `OrderCancelledEvent` (this task owns the record change)**

`persistCancellation` publishes the 6-component event, so the record must change here for the project to compile. Replace the file contents with:

```java
package com.enunas.backend.order;

import java.math.BigDecimal;

/**
 * @param refundAmount the amount refunded to the customer, or null when the order was never paid
 */
public record OrderCancelledEvent(String buyerEmail, String orderNumber, CancelReason reason, String note,
                                  BigDecimal refundAmount, String currency) {}
```

`OrderCancelledEmailListener` still compiles unchanged (it only reads the accessors it already used). Task 5 adds the refund sentence.

- [ ] **Step 5: Run the tests**

Run: `./mvnw test -Dtest=AdminCancelRefundIntegrationTest,OrderEmailResilienceTest`
Expected: the three tests in this task PASS.

- [ ] **Step 6: STOP — David commits**

```bash
git add backend/src/main/java/com/enunas/backend/order backend/src/test/java/com/enunas/backend/order/integration/AdminCancelRefundIntegrationTest.java
git commit -m "feat(order): refund the customer when an admin cancels a paid order"
```

---

### Task 4: Guard tests

**Files:**
- Modify: `backend/src/test/java/com/enunas/backend/order/integration/AdminCancelRefundIntegrationTest.java`

**Interfaces:**
- Consumes: everything from Task 3.

- [ ] **Step 1: Add the guard tests**

```java
    @Test
    void alreadyRefundedPayment_isRejected() {
        Fixture f = paidOrder();
        jdbc.update("UPDATE payments SET status = 'REFUNDED' WHERE order_id = ?", f.orderId());

        assertThat(cancel(f.adminToken(), f.orderId(), "CUSTOMER_REQUEST", null).getStatusCode().value())
                .isEqualTo(409);
        assertThat(orderRow(f.orderId()).get("status")).isEqualTo("PAID");
        verify(paymentProvider, times(0)).refundPayment(any());
    }

    @Test
    void amountMismatch_isRejected() {
        Fixture f = paidOrder();
        jdbc.update("UPDATE payments SET amount = amount + 1 WHERE order_id = ?", f.orderId());

        assertThat(cancel(f.adminToken(), f.orderId(), "CUSTOMER_REQUEST", null).getStatusCode().value())
                .isEqualTo(409);
        assertThat(orderRow(f.orderId()).get("status")).isEqualTo("PAID");
        verify(paymentProvider, times(0)).refundPayment(any());
    }

    @Test
    void shippedOrder_isRejected() {
        Fixture f = paidOrder();
        String brandToken = login("brand-a@it.local", "Brand123!");
        ResponseEntity<Map> shipped = rest.exchange("/brand/orders/" + f.orderId() + "/ship", HttpMethod.POST,
                new HttpEntity<>(Map.of("carrier", "DHL", "trackingNumber", "T1"), auth(brandToken)), Map.class);
        assertThat(shipped.getStatusCode().is2xxSuccessful()).as("ship: %s", shipped.getBody()).isTrue();

        assertThat(cancel(f.adminToken(), f.orderId(), "CUSTOMER_REQUEST", null).getStatusCode().value())
                .isEqualTo(409);
        verify(paymentProvider, times(0)).refundPayment(any());
    }

    @Test
    void brandShipsDuringRefund_recordsRefundButDoesNotCancel() {
        Fixture f = paidOrder();
        String brandToken = login("brand-a@it.local", "Brand123!");
        // Ship the item *while* the refund call is in flight — the race the spec documents.
        org.mockito.Mockito.doAnswer(inv -> {
            rest.exchange("/brand/orders/" + f.orderId() + "/ship", HttpMethod.POST,
                    new HttpEntity<>(Map.of("carrier", "DHL", "trackingNumber", "T1"), auth(brandToken)), Map.class);
            return new com.enunas.backend.payment.RefundResult("ref_mock_race");
        }).when(paymentProvider).refundPayment(any());

        assertThat(cancel(f.adminToken(), f.orderId(), "CUSTOMER_REQUEST", null).getStatusCode().value())
                .isEqualTo(409);

        Map<String, Object> order = orderRow(f.orderId());
        assertThat(order.get("status")).isNotEqualTo("CANCELLED");
        assertThat(order.get("refund_transaction_id")).isEqualTo("ref_mock_race");
        assertThat(paymentRow(f.orderId()).get("status")).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE order_id = ? AND entry_type = 'REFUND_REVERSAL'",
                Integer.class, f.orderId())).isZero();
        assertThat(stock(f.orderId())).isEqualTo(4); // NOT restored — the parcel is in transit
    }
```

- [ ] **Step 2: Run them**

Run: `./mvnw test -Dtest=AdminCancelRefundIntegrationTest`
Expected: all PASS. If `brandShipsDuringRefund...` fails because the ship call needs the order PAID and unshipped, confirm the fixture ships only inside the stub.

- [ ] **Step 3: STOP — David commits**

```bash
git add backend/src/test/java/com/enunas/backend/order/integration/AdminCancelRefundIntegrationTest.java
git commit -m "test(order): guards for admin cancel refunds"
```

---

### Task 5: Refund wording in the cancellation email

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/order/OrderCancelledEmailListener.java`
- Test: `backend/src/test/java/com/enunas/backend/order/integration/AdminCancelRefundIntegrationTest.java`

**Interfaces:**
- Consumes: `OrderCancelledEvent(String buyerEmail, String orderNumber, CancelReason reason, String note, BigDecimal refundAmount, String currency)` — the record change was made in Task 3.

- [ ] **Step 1: Write the failing assertion**

In `paidCancel_refundsReversesAndRecords`, replace the subject-only email assertion with one that also requires the refund sentence in the body, and add an unpaid counterpart to the existing `unpaidCancel_doesNotCallMollie` test:

```java
        verify(emailService).sendPlainTextEmail(anyString(), contains("storniert"), contains("erstattet"));
```

and in `unpaidCancel_doesNotCallMollie`:

```java
        org.mockito.ArgumentCaptor<String> body = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(emailService).sendPlainTextEmail(anyString(), contains("storniert"), body.capture());
        assertThat(body.getValue()).doesNotContain("erstattet");
```

- [ ] **Step 2: Run and confirm it fails**

Run: `./mvnw test -Dtest=AdminCancelRefundIntegrationTest#paidCancel_refundsReversesAndRecords`
Expected: FAIL — the email body has no refund sentence yet. State what you observed.

- [ ] **Step 3: Add the refund sentence to the listener**

In `OrderCancelledEmailListener.onOrderCancelled`, make `body` a reassignable local `String` and, after building it:

```java
            if (event.refundAmount() != null) {
                body += "

Wir erstatten dir " + event.refundAmount() + " " + event.currency()
                        + " auf dein ursprüngliches Zahlungsmittel. Das dauert je nach Bank 5–10 Werktage.";
            }
```

- [ ] **Step 4: Run the tests**

Run: `./mvnw test -Dtest=AdminCancelRefundIntegrationTest,OrderEmailResilienceTest`
Expected: PASS.

- [ ] **Step 5: STOP — David commits**

```bash
git add backend/src/main/java/com/enunas/backend/order backend/src/test/java/com/enunas/backend/order/integration/AdminCancelRefundIntegrationTest.java
git commit -m "feat(email): tell the customer about the refund on an admin cancellation"
```

---

### Task 6: PATCH delegates to the cancel path

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/order/OrderService.java:1059` (`updateOrderStatus`)
- Modify: `backend/src/main/java/com/enunas/backend/admin/AdminController.java:251-256`
- Modify: `backend/src/test/java/com/enunas/backend/discount/integration/DiscountPaymentFlowIntegrationTest.java:92,145`
- Modify: `backend/src/test/java/com/enunas/backend/order/integration/ReturnLifecyclePhase3Test.java:310` (stale comment)
- Test: `backend/src/test/java/com/enunas/backend/order/integration/AdminCancelRefundIntegrationTest.java`

**Interfaces:**
- Produces: `updateOrderStatus(Long orderId, OrderStatus newStatus, User admin)`.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void deprecatedPatchPath_stillRefunds() {
        Fixture f = paidOrder();

        ResponseEntity<Map> resp = rest.exchange("/admin/orders/" + f.orderId() + "/status?status=CANCELLED",
                HttpMethod.PATCH, new HttpEntity<>(null, auth(f.adminToken())), Map.class);

        assertThat(resp.getStatusCode().is2xxSuccessful()).as("patch: %s", resp.getBody()).isTrue();
        Map<String, Object> order = orderRow(f.orderId());
        assertThat(order.get("status")).isEqualTo("CANCELLED");
        assertThat(order.get("cancellation_reason")).isEqualTo("OTHER");
        assertThat(order.get("cancellation_note")).isEqualTo("via deprecated PATCH");
        assertThat((String) order.get("refund_transaction_id")).startsWith("ref_mock_");
        verify(paymentProvider, times(1)).refundPayment(any(RefundCommand.class));
    }
```

- [ ] **Step 2: Run and confirm failure**

Run: `./mvnw test -Dtest=AdminCancelRefundIntegrationTest#deprecatedPatchPath_stillRefunds`
Expected: FAIL — `refund_transaction_id` is null; no refund was issued.

- [ ] **Step 3: Split `updateOrderStatus` into dispatcher + transactional body**

```java
    @PreAuthorize("hasRole('ADMIN')")
    public OrderResponseDto updateOrderStatus(Long orderId, OrderStatus newStatus, User admin) {
        if (newStatus == OrderStatus.CANCELLED) {
            // DEPRECATED: one release only. Cancelling needs a refund, an admin identity and a
            // reason, none of which this endpoint carries — delegate so the money is right whichever
            // endpoint the caller uses, then remove this branch once callers have migrated.
            log.warn("DEPRECATED_CANCEL_PATH: order {} cancelled via PATCH status — use POST /admin/orders/{}/cancel",
                    orderId, orderId);
            CancelOrderDto dto = new CancelOrderDto();
            dto.setReason(CancelReason.OTHER);
            dto.setNote("via deprecated PATCH");
            return cancelOrder(orderId, dto, admin);
        }
        return transactionTemplate.execute(s -> applyStatusTransition(orderId, newStatus));
    }

    private OrderResponseDto applyStatusTransition(Long orderId, OrderStatus newStatus) {
        // ... existing body of updateOrderStatus, verbatim, MINUS the postPaymentCancel branch and
        // its restoreVariantStock / recordRefund / releaseDiscountUsageOnce calls (CANCELLED can no
        // longer reach here) ...
    }
```

Keep `validateForwardTransition`, the DELIVERED stamp, the bulk-ship backfill and the escalation
re-sync exactly as they are.

- [ ] **Step 4: Pass the admin through the controller**

`AdminController`:

```java
    @PatchMapping("/orders/{orderId}/status")
    public ResponseEntity<OrderResponseDto> updateOrderStatus(
            @PathVariable Long orderId,
            @RequestParam OrderStatus status,
            @AuthenticationPrincipal User admin) {
        return ResponseEntity.ok(orderService.updateOrderStatus(orderId, status, admin));
    }
```

- [ ] **Step 5: Fix the two direct test calls**

In `DiscountPaymentFlowIntegrationTest:92` and `:145`, pass the admin already in scope:
`asAdmin(admin, () -> orderService.updateOrderStatus(oid, OrderStatus.CANCELLED, admin));`

Update the stale comment at `ReturnLifecyclePhase3Test:310` — it says "via the postPaymentCancel
path", which no longer exists; it now goes through the delegated cancel path.

- [ ] **Step 6: Run the affected suites**

Run: `./mvnw test -Dtest=AdminCancelRefundIntegrationTest,DiscountPaymentFlowIntegrationTest,ReturnLifecyclePhase3Test,ShippingLedgerIntegrationTest,ReconciliationServiceShippingTest,SettlementAccountingReportIntegrationTest,AccountErasureIntegrationTest,LatePaymentAutoRefundIntegrationTest`
Expected: PASS. These are the seven pre-existing `PATCH status=CANCELLED` call sites; each now
exercises delegation.

- [ ] **Step 7: STOP — David commits**

```bash
git add backend/src/main/java/com/enunas/backend backend/src/test/java/com/enunas/backend
git commit -m "feat(order): PATCH status=CANCELLED delegates to the refunding cancel path"
```

---

### Task 7: Concurrency proof and full suite

**Files:**
- Modify: `backend/src/test/java/com/enunas/backend/order/integration/AdminCancelRefundIntegrationTest.java`

- [ ] **Step 1: Add the double-cancel test**

Modelled on `ConcurrentWebhookIdempotencyTest` — two threads released together:

```java
    @Test
    void concurrentCancels_refundOnceAndOneWins() throws Exception {
        Fixture f = paidOrder();

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<Integer> attempt = () -> {
            start.await();
            return cancel(f.adminToken(), f.orderId(), "CUSTOMER_REQUEST", null).getStatusCode().value();
        };
        var a = pool.submit(attempt);
        var b = pool.submit(attempt);
        start.countDown();
        List<Integer> codes = List.of(a.get(), b.get());
        pool.shutdown();

        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        assertThat(orderRow(f.orderId()).get("status")).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE order_id = ? AND entry_type = 'REFUND_REVERSAL'",
                Integer.class, f.orderId())).isEqualTo(2); // one product + one shipping entry, single reversal
        assertThat(brandPending(f.brandId())).isEqualByComparingTo("0.00");
    }
```

- [ ] **Step 2: Run it**

Run: `./mvnw test -Dtest=AdminCancelRefundIntegrationTest`
Expected: PASS. The mock replays one `refundId` for the shared key, so only one refund exists; the
loser 409s on the phase-3 status re-check.

- [ ] **Step 3: Run the whole suite**

Run: `./mvnw test`
Expected: no failures. Ignore `Scratch*` classes if they were already failing before this work
(`ScratchDeprecatedReturnReproTest` was red on 2026-09-15).

- [ ] **Step 4: STOP — David commits**

```bash
git add backend/src/test/java/com/enunas/backend/order/integration/AdminCancelRefundIntegrationTest.java
git commit -m "test(order): concurrent admin cancels refund exactly once"
```

---

## Self-review

**Spec coverage:** D1 → Task 1; D2/D3/D7 → Tasks 3–4; D4 → Task 6; D5 → Task 2 (brand DTO gets the
reason only); D6 → Task 2; phase-3 races → Tasks 3, 4, 7; email → Task 5. Spec test scenarios 1–10
map to Tasks 3, 4, 6, 7 and (scenario 9) Task 2.

**Type consistency:** `CancelPreflight(orderNumber, wasPaid, transactionId, amount)` is produced in
Task 3 Step 3 and used only there. `OrderCancelledEvent` gains two components in Task 5 and is
constructed in Task 3 — noted in Task 3 Step 4 that Task 5's Step 1 may need to land first for the
project to compile. `updateOrderStatus`'s third parameter (Task 6) matches all three call sites.

**Known ordering constraint:** Task 3 publishes the 6-component `OrderCancelledEvent`. If a worker
runs Task 3 standalone, apply Task 5 Step 1 first.
