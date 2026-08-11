# Backend Cleanup — Pre-Shipping-Domain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the `Order.total` calculation explicitly include `shippingTotal` (defensive correctness fix, no behavior change while shipping is 0), prove it with a regression test that uses a non-zero shipping value, and verify the rest of the backend (settlement test, test infra, dormant `BrandShippingProfile`, full suite) is already in the state the production-readiness review wants — without implementing any shipping-cost domain logic.

**Architecture:** `Order` gains one small, null-safe instance method `computeTotal()` that is the single source of truth for `total = subtotal − discountAmount + shippingTotal`. `OrderService.createOrder()` stops duplicating that arithmetic inline and instead calls `order.computeTotal()` after building the order, so the entity-level unit test that exercises `computeTotal()` with a non-zero shipping value is testing the exact code path production uses — not a parallel copy of the formula.

**Tech Stack:** Java 21, Spring Boot 4.0.5, JUnit 5, AssertJ, Lombok `@Builder`.

## Global Constraints

- Do NOT implement the shipping-cost domain: no `ShippingCostService`, `FlatRateShippingCostService`, `OrderShippingSnapshot`, `SHIPPING_REVENUE`, `ShippingCalculationMethod`, V22 migration, `/orders/preview`, shipping ledger/settlement/refund logic, or shipping admin endpoint.
- `shippingTotal` stays `BigDecimal.ZERO` in `OrderService.createOrder()` — only the formula that consumes it changes.
- Do NOT delete or repurpose `BrandShippingProfile` / `BrandShippingProfileRepository` / `brand_shipping_profiles` — verify only.
- Do NOT modify `SettlementService` — the settlement calendar test already uses `YearMonth.now(ZoneId.of("Europe/Berlin"))`-relative dates (verified passing below); no change needed there.
- Never hardcode real secrets; never print `JWT_SECRET`/API keys/passwords.
- Do not commit anything — the user commits their own work (per project convention).

---

## Pre-flight findings (already verified, no task needed)

Investigated before writing this plan, with empirical verification, not assumption:

1. **Settlement test is already fixed.** `SettlementIntegrationTest.closedPeriodGuard_rejectsRunningAndFutureMonths_allowsClosedMonth` (`backend/src/test/java/com/enunas/backend/discount/integration/SettlementIntegrationTest.java:73-92`) already derives `currentMonth`/`futureMonth`/`closedMonth` from `YearMonth.now(BERLIN)` — no hardcoded 2026-06/07/05 literals remain. Ran it standalone with a clean shell (no env vars set): `Tests run: 5, Failures: 0, Errors: 0`.
2. **Test infra already works from a clean checkout.** `backend/src/test/resources/application-test.yaml` exists, uses the `jdbc:tc:postgresql:16:///enunas` Testcontainers URL + `ContainerDatabaseDriver`, and overrides every property that has no default in `application.yaml` (JWT secret, admin email/password, Mollie key/webhook, Google OAuth client id, mail credentials, frontend base URL) — except `spring.datasource.username`/`password`, which fall back to `application.yaml`'s `${DB_USERNAME:postgres}` / `${DB_PASSWORD}` (no default). Empirically this does NOT block a clean run — Spring only requires Hikari's configured username/password when they're actually used by the driver, and `ContainerDatabaseDriver` ignores them. Confirmed by running `SettlementIntegrationTest` (and will confirm again with the full suite) in a shell with `DB_PASSWORD`, `JWT_SECRET`, `MOLLIE_API_KEY`, `ADMIN_PASSWORD`, `MAIL_PASSWORD`, `GOOGLE_OAUTH_CLIENT_ID` all unset. Both `@SpringBootTest` classes in the repo (`BackendApplicationTests`, `AbstractDiscountIntegrationTest`) consistently activate `{"test", "mock-payments"}`. The only real remaining requirement is a running **Docker daemon** for Testcontainers — that's infrastructure, not a secret, and is what "clean checkout" is expected to need.
3. **`BrandShippingProfile` is genuinely dormant and harmless.** Entity + repository exist (`backend/src/main/java/com/enunas/backend/brandpartner/brandshippingprofile/`), table exists since `V0.0.1__baseline_schema.sql`. Grep for `BrandShippingProfile` across `src/` shows exactly 3 hits: the entity itself, its repository, and `SchemaGenerator` (a `@Disabled` dev-only schema-dump tool). No service, controller, or `OrderService` code path reads or writes it. It cannot cause incorrect behavior today because nothing calls it.

Because of (1) and (2), no code task is needed for spec items 2 and 3 — only re-verification as part of the final full-suite run.

---

## Task 1: Make `Order.total` explicit via `Order.computeTotal()`

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/order/Order.java` (add `computeTotal()` near the existing discount-snapshot comment block, ~line 73)
- Modify: `backend/src/main/java/com/enunas/backend/order/OrderService.java:205-229` (`createOrder()` order-building block)
- Test: `backend/src/test/java/com/enunas/backend/order/OrderTest.java` (new file)

**Interfaces:**
- Produces: `Order.computeTotal()` — instance method, no args, returns `BigDecimal`. Treats `null` `discountAmount`/`shippingTotal` as `BigDecimal.ZERO` (both are legitimately null on the free/no-discount path before this point in the codebase's history, so the method must not NPE on a freshly-built order that never got `.discountAmount(...)` called).

- [ ] **Step 1: Write the failing unit test**

Create `backend/src/test/java/com/enunas/backend/order/OrderTest.java`:

```java
package com.enunas.backend.order;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link Order#computeTotal()} — the single source of truth for
 * total = subtotal − discountAmount + shippingTotal. OrderService.createOrder() calls this exact
 * method, so a regression here (e.g. dropping the shippingTotal term) fails this test even while
 * shippingTotal is 0 in production, because these tests set it explicitly to a non-zero value.
 */
class OrderTest {

    @Test
    void computeTotal_appliesSubtotalMinusDiscountPlusShipping_withNonZeroShipping() {
        Order order = Order.builder()
                .subtotal(new BigDecimal("100.00"))
                .discountAmount(new BigDecimal("10.00"))
                .shippingTotal(new BigDecimal("4.99"))
                .build();

        // 100.00 - 10.00 + 4.99 = 94.99 — would be 90.00 if shippingTotal were dropped from the
        // formula, so this fails loudly on that regression.
        assertThat(order.computeTotal()).isEqualByComparingTo("94.99");
    }

    @Test
    void computeTotal_treatsNullDiscountAndShippingAsZero() {
        Order order = Order.builder()
                .subtotal(new BigDecimal("119.00"))
                .build(); // discountAmount and shippingTotal left null — today's no-discount path

        assertThat(order.computeTotal()).isEqualByComparingTo("119.00");
    }

    @Test
    void computeTotal_withDiscountAndZeroShipping_matchesCurrentFreeShippingBehavior() {
        Order order = Order.builder()
                .subtotal(new BigDecimal("119.00"))
                .discountAmount(new BigDecimal("11.90"))
                .shippingTotal(BigDecimal.ZERO)
                .build();

        assertThat(order.computeTotal()).isEqualByComparingTo("107.10");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=OrderTest`
Expected: FAIL to compile — `computeTotal()` does not exist on `Order` yet.

- [ ] **Step 3: Add `Order.computeTotal()`**

In `backend/src/main/java/com/enunas/backend/order/Order.java`, replace the comment block:

```java
    // ===== Discount snapshot (immutable; null/zero when no code was applied) =====
    // total = subtotal − discountAmount + shippingTotal

    private String discountCode;
```

with:

```java
    // ===== Discount snapshot (immutable; null/zero when no code was applied) =====
    // total = subtotal − discountAmount + shippingTotal — see computeTotal() below, which is the
    // single source of truth OrderService.createOrder() calls; do not duplicate this arithmetic
    // elsewhere.

    private String discountCode;
```

Then, after the `removeItem` method (after line 166, before the `equals`/`hashCode` section), add:

```java
    // ===== Derived money =====

    /**
     * total = subtotal − discountAmount + shippingTotal. Null-safe on discountAmount/shippingTotal
     * so it can be called on an order that hasn't gone through the discount branch (both stay null
     * on the no-discount path) — see {@link OrderService#createOrder}, the only caller.
     */
    public BigDecimal computeTotal() {
        BigDecimal discount = discountAmount != null ? discountAmount : BigDecimal.ZERO;
        BigDecimal shipping = shippingTotal != null ? shippingTotal : BigDecimal.ZERO;
        return subtotal.subtract(discount).add(shipping);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=OrderTest`
Expected: PASS — `Tests run: 3, Failures: 0, Errors: 0`.

- [ ] **Step 5: Wire `OrderService.createOrder()` to use `computeTotal()`**

In `backend/src/main/java/com/enunas/backend/order/OrderService.java`, in the order-building block (~line 205), remove the inline total calculation from the builder chain and compute it from the entity after build instead.

Change:

```java
        Order.OrderBuilder orderBuilder = Order.builder()
                .orderNumber(generateOrderNumber())
                .buyer(buyer)
                .status(OrderStatus.PENDING)
                .shippingAddress(address)
                .subtotal(subtotal)
                .shippingTotal(shippingTotal) // always 0 — free shipping
                // Goods-only discounted total — this is what Mollie charges and the webhook verifies.
                .total(subtotal.subtract(discountAmount))
                .currency(listings.get(0).getCurrency())
                .notes(dto.getNotes());

        if (discount != null) {
            orderBuilder
                    .discountCode(discount.code().getCode())
                    .discountType(discount.type())
                    .discountPercent(discount.percent())
                    .discountAmount(discountAmount)                              // gross reduction
                    .platformDiscountAmount(discount.platformDiscountAmount())  // net absorption share
                    .brandDiscountAmount(discount.brandDiscountAmount());       // net absorption share
        }

        Order order = orderBuilder.build();
```

to:

```java
        Order.OrderBuilder orderBuilder = Order.builder()
                .orderNumber(generateOrderNumber())
                .buyer(buyer)
                .status(OrderStatus.PENDING)
                .shippingAddress(address)
                .subtotal(subtotal)
                .shippingTotal(shippingTotal) // always 0 — free shipping
                .currency(listings.get(0).getCurrency())
                .notes(dto.getNotes());

        if (discount != null) {
            orderBuilder
                    .discountCode(discount.code().getCode())
                    .discountType(discount.type())
                    .discountPercent(discount.percent())
                    .discountAmount(discountAmount)                              // gross reduction
                    .platformDiscountAmount(discount.platformDiscountAmount())  // net absorption share
                    .brandDiscountAmount(discount.brandDiscountAmount());       // net absorption share
        }

        Order order = orderBuilder.build();
        // total = subtotal - discountAmount + shippingTotal. This is what Mollie charges and the
        // webhook verifies — see Order.computeTotal().
        order.setTotal(order.computeTotal());
```

- [ ] **Step 6: Run test to verify it still passes, plus the wider order/discount suite**

Run: `./mvnw test -Dtest=OrderTest`
Expected: PASS — `Tests run: 3, Failures: 0, Errors: 0`.

Run: `./mvnw test -Dtest=DiscountPaymentFlowIntegrationTest,LegacyOrderFallbackTest,ConcurrentWebhookIdempotencyTest,Vat22fComplianceTest,CheckoutAddressIntegrationTest,MultiBrandReturnTest,ReturnLifecyclePhase3Test`
Expected: PASS, 0 failures, 0 errors — these are the tests that assert `orders.total`/`payments.amount` in euros end-to-end; since `shippingTotal` is still always `BigDecimal.ZERO` in `createOrder()`, every one of their asserted totals is unchanged (`subtotal − discountAmount + 0` is the same value as before).

*(No commit here — the user commits their own work.)*

---

## Task 2: Full-suite verification and production-readiness report

**Files:** none modified — verification only.

- [ ] **Step 1: Run the full backend suite in a clean-env shell**

Run (with `DB_PASSWORD`, `JWT_SECRET`, `MOLLIE_API_KEY`, `ADMIN_PASSWORD`, `MAIL_PASSWORD`, `GOOGLE_OAUTH_CLIENT_ID` all unset, Docker running):

```
./mvnw test
```

Expected: `BUILD SUCCESS`, 0 failures, 0 errors. Record the actual total test count from the surefire summary — do not assume 230+.

- [ ] **Step 2: Re-confirm the settlement test specifically**

Run: `./mvnw test -Dtest=SettlementIntegrationTest`
Expected: `Tests run: 5, Failures: 0, Errors: 0` (already confirmed once during investigation; re-run after Task 1's changes to prove Task 1 didn't disturb it).

- [ ] **Step 3: Inspect the final diff**

Run: `git status` and `git diff -- backend/src/main/java/com/enunas/backend/order/Order.java backend/src/main/java/com/enunas/backend/order/OrderService.java`

Confirm: only `Order.java` (new `computeTotal()` method + comment update), `OrderService.java` (builder change), and the new `OrderTest.java` are touched. Confirm no shipping-domain files were created (grep the diff for `ShippingCost`, `OrderShippingSnapshot`, `SHIPPING_REVENUE`, `ShippingCalculationMethod`, `/orders/preview`).

- [ ] **Step 4: Write the production-readiness report**

Summarize (in the chat response, not a new file) using the exact structure requested: Changed / Verified / Not changed / Potential remaining issues, including the actual test count from Step 1 and the DB_PASSWORD/Docker caveat from the pre-flight findings above.

*(No commit — report only.)*

---

## Self-review notes

- **Spec coverage:** Item 1 (Order.total) → Task 1. Item 2 (settlement test) → pre-flight finding, re-verified in Task 2 Step 2. Item 3 (test infra) → pre-flight finding, re-verified in Task 2 Step 1. Item 4 (BrandShippingProfile) → pre-flight finding, reported in Task 2 Step 4. Item 5 (don't implement shipping domain) → Global Constraints + Task 2 Step 3 grep check. Item 6 (preserve existing behavior) → Task 1 Step 6 runs the exact integration tests that cover OAuth-adjacent checkout, address selection, discounts, and refunds. Item 7 (testing requirements) → Task 1 Step 6 + Task 2 Steps 1-2. Item 8 (final report) → Task 2 Step 4.
- **Placeholder scan:** none found — all steps carry literal code/commands.
- **Type consistency:** `computeTotal()` returns `BigDecimal`, matches `Order.total`'s type; `OrderService` calls `order.setTotal(order.computeTotal())` using the existing Lombok-generated setter — consistent with the rest of the file's `order.setX(...)` usage elsewhere (e.g. `confirmShipment`).
