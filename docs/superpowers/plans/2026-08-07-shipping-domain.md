# Shipping Cost Domain Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make shipping a real, priced, per-brand marketplace line item — calculated, snapshotted, ledgered separately from commissionable product revenue, and refunded/reported correctly — replacing today's hardcoded free shipping.

**Architecture:** A dedicated `ShippingCostService` (flat-rate V1, extensible interface) prices each brand's shipment at checkout; the amount is frozen into an immutable `OrderShippingSnapshot` per (order, brand); a new `SHIPPING_REVENUE` ledger entry type carries that money through the existing hold/release/payout pipeline without ever touching commission math; refund reversal is split into explicit product vs. shipping steps so a full order cancel reverses both while a per-brand item return reverses only product.

**Tech Stack:** Spring Boot 4.0.5, Java 21, Spring Data JPA, PostgreSQL + Flyway, JUnit 5 + Mockito + AssertJ, Testcontainers (integration tests).

## Global Constraints

- Money: `BigDecimal`, `numeric(10,2)` columns, `RoundingMode.HALF_UP`, 2dp — matches every existing money field in this codebase.
- Currency is fixed to `"EUR"` platform-wide (no multi-currency support exists anywhere in this codebase); currency columns are added for auditability, not to enable non-EUR orders.
- All migrations are additive (`ADD COLUMN IF NOT EXISTS` / `CREATE TABLE IF NOT EXISTS`) — never edit an applied Flyway script.
- Commission is computed only from `OrderItem`/product figures; shipping money must never be able to feed commission math, structurally, not just by convention.
- Never `git add`/commit/push — the user stages and commits their own work.
- Design reference: `docs/superpowers/specs/2026-08-07-shipping-domain-design.md` (approved).

---

## File Structure

```
backend/src/main/resources/db/migration/V22__shipping_domain.sql          (new)

backend/src/main/java/com/enunas/backend/
  ledger/LedgerEntryType.java                                              (modify — add SHIPPING_REVENUE)
  ledger/LedgerRepository.java                                             (modify — shipping-entry query, period aggregate)
  ledger/LedgerService.java                                                (modify — recordShippingRevenue, refund split)
  brandpartner/brandshippingprofile/BrandShippingProfile.java              (modify — currency field, javadoc fix)
  brandpartner/brandshippingprofile/BrandShippingProfileRepository.java    (modify — findByBrandPartner_Id)
  shipping/ShippingCalculationMethod.java                                  (new)
  shipping/ShippingCostResult.java                                        (new)
  shipping/ShippingCostService.java                                       (new — interface)
  shipping/FlatRateShippingCostService.java                                (new — V1 impl)
  order/OrderShippingSnapshot.java                                        (new — entity)
  order/OrderShippingSnapshotRepository.java                               (new)
  order/OrderPricingDraft.java                                            (new — shared pricing computation result)
  order/OrderService.java                                                  (modify — buildPricingDraft, shipping wiring, preview)
  order/OrderController.java                                              (modify — POST /orders/preview)
  order/dto/OrderResponseDto.java                                          (modify — shippingSnapshots field)
  order/dto/ShippingSnapshotDto.java                                      (new)
  order/dto/OrderPreviewResponseDto.java                                   (new)
  settlement/dto/SettlementRowDto.java                                    (modify — shippingRevenue field)
  settlement/SettlementService.java                                        (modify — zeroRow shippingRevenue)
  admin/dto/SetShippingProfileDto.java                                    (new)
  admin/AdminService.java                                                  (modify — setBrandShippingProfile)
  admin/AdminController.java                                              (modify — PATCH .../shipping-profile)

backend/src/main/resources/application.yaml                                (modify — enunas.shipping.default-rate)
backend/src/test/resources/application-test.yaml                          (modify — same)

backend/src/test/java/com/enunas/backend/
  shipping/FlatRateShippingCostServiceTest.java                            (new)
  ledger/LedgerServiceShippingRevenueTest.java                             (new)
  ledger/LedgerServiceRefundSplitTest.java                                 (new)
  order/integration/ShippingCheckoutIntegrationTest.java                   (new)
  order/integration/ShippingLedgerIntegrationTest.java                     (new)
  order/integration/CheckoutPreviewIntegrationTest.java                    (new)
  order/integration/AdminShippingProfileIntegrationTest.java               (new)
  discount/integration/SettlementIntegrationTest.java                      (modify — +1 test method)
```

---

### Task 1: Migration + `SHIPPING_REVENUE` entry type + activate `BrandShippingProfile`

**Files:**
- Create: `backend/src/main/resources/db/migration/V22__shipping_domain.sql`
- Modify: `backend/src/main/java/com/enunas/backend/ledger/LedgerEntryType.java`
- Modify: `backend/src/main/java/com/enunas/backend/brandpartner/brandshippingprofile/BrandShippingProfile.java`
- Modify: `backend/src/main/java/com/enunas/backend/brandpartner/brandshippingprofile/BrandShippingProfileRepository.java`

**Interfaces:**
- Produces: `LedgerEntryType.SHIPPING_REVENUE` (consumed by Task 4, 5, 9). `BrandShippingProfileRepository.findByBrandPartner_Id(Long)` (consumed by Task 2, 10). `brand_shipping_profiles.currency` column + `BrandShippingProfile.getCurrency()` (consumed by Task 2). `order_shipping_snapshots` table (consumed by Task 3).

- [ ] **Step 1: Create the migration**

```sql
-- =============================================================================
-- V22: Shipping cost domain
--
-- Activates the previously-dormant brand_shipping_profiles table (created by the
-- old Hibernate ddl-auto export, present in V0.0.1, never read or written by any
-- code) and adds the immutable per-order-per-brand shipping snapshot. Additive only.
-- =============================================================================

-- 1) order_shipping_snapshots: one immutable row per (order, brand). Financial record —
--    order_id/brand_partner_id use ON DELETE RESTRICT so neither can disappear while a
--    money snapshot referencing it exists. brand_shipping_profile_id is debugging
--    traceability only (which profile produced this amount), not a financial dependency,
--    so it uses ON DELETE SET NULL.
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

-- 2) brand_shipping_profiles: add currency to the existing (dormant, currently unwritten)
--    shipping_cost money column. Explicit three-step migration rather than relying on
--    DEFAULT alone, since this table predates this change.
ALTER TABLE brand_shipping_profiles ADD COLUMN IF NOT EXISTS currency VARCHAR(3);
UPDATE brand_shipping_profiles SET currency = 'EUR' WHERE currency IS NULL;
ALTER TABLE brand_shipping_profiles ALTER COLUMN currency SET NOT NULL;
ALTER TABLE brand_shipping_profiles ALTER COLUMN currency SET DEFAULT 'EUR';

-- 3) ledger_entries.entry_type gains SHIPPING_REVENUE as a value (Java enum
--    com.enunas.backend.ledger.LedgerEntryType) — stored in the existing varchar
--    column, no DDL change needed.
```

- [ ] **Step 2: Add `SHIPPING_REVENUE` to the entry-type enum**

Full replacement of `backend/src/main/java/com/enunas/backend/ledger/LedgerEntryType.java`:

```java
package com.enunas.backend.ledger;

public enum LedgerEntryType {
    ORDER_PAYMENT,
    PAYOUT_TRANSFER,
    REFUND_REVERSAL,
    SHIPPING_REVENUE
}
```

- [ ] **Step 3: Add `currency` and fix the ambiguous javadoc on `BrandShippingProfile`**

Full replacement of `backend/src/main/java/com/enunas/backend/brandpartner/brandshippingprofile/BrandShippingProfile.java`:

```java
package com.enunas.backend.brandpartner.brandshippingprofile;

import com.enunas.backend.brandpartner.BrandPartner;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "brand_shipping_profiles")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrandShippingProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id", nullable = false, unique = true)
    private BrandPartner brandPartner;

    private String originCountry;

    @Builder.Default
    private boolean handlesOwnShipping = false;

    private Integer avgShippingDays;

    /**
     * Flat shipping cost charged once per brand per order, in {@link #currency}. These are two
     * DIFFERENT, DELIBERATELY DISTINCT outcomes — {@code ShippingCostService} names and persists
     * which one applied on every order, rather than leaving it to be inferred from the number:
     * <ul>
     *   <li>{@code null} — not configured. The platform default rate applies.</li>
     *   <li>{@code 0.00} — explicit free shipping (a campaign, a premium-brand perk, etc.).</li>
     *   <li>{@code > 0.00} — this brand's flat rate.</li>
     * </ul>
     */
    @Column(precision = 10, scale = 2)
    private BigDecimal shippingCost;

    @Builder.Default
    @Column(nullable = false, length = 3)
    private String currency = "EUR";
}
```

- [ ] **Step 4: Add the by-id lookup to the repository**

Full replacement of `backend/src/main/java/com/enunas/backend/brandpartner/brandshippingprofile/BrandShippingProfileRepository.java`:

```java
package com.enunas.backend.brandpartner.brandshippingprofile;

import com.enunas.backend.brandpartner.BrandPartner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BrandShippingProfileRepository extends JpaRepository<BrandShippingProfile, Long> {

    Optional<BrandShippingProfile> findByBrandPartner(BrandPartner brandPartner);

    Optional<BrandShippingProfile> findByBrandPartner_Id(Long brandPartnerId);
}
```

- [ ] **Step 5: Verify the schema and enum compile and load**

Run: `cd backend && ./mvnw test -Dtest=BackendApplicationTests`
Expected: PASS — confirms the app context loads with the new migration applied and the new enum value present. (This codebase has no dedicated DDL-only migration tests — V5/V7 follow the same convention of being verified by downstream feature tests, which Tasks 2–10 provide here.)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/resources/db/migration/V22__shipping_domain.sql \
        backend/src/main/java/com/enunas/backend/ledger/LedgerEntryType.java \
        backend/src/main/java/com/enunas/backend/brandpartner/brandshippingprofile/BrandShippingProfile.java \
        backend/src/main/java/com/enunas/backend/brandpartner/brandshippingprofile/BrandShippingProfileRepository.java
git commit -m "feat(shipping): migration, SHIPPING_REVENUE entry type, activate BrandShippingProfile"
```

---

### Task 2: `ShippingCostService` calculation layer

**Files:**
- Create: `backend/src/main/java/com/enunas/backend/shipping/ShippingCalculationMethod.java`
- Create: `backend/src/main/java/com/enunas/backend/shipping/ShippingCostResult.java`
- Create: `backend/src/main/java/com/enunas/backend/shipping/ShippingCostService.java`
- Create: `backend/src/main/java/com/enunas/backend/shipping/FlatRateShippingCostService.java`
- Test: `backend/src/test/java/com/enunas/backend/shipping/FlatRateShippingCostServiceTest.java`
- Modify: `backend/src/main/resources/application.yaml`
- Modify: `backend/src/test/resources/application-test.yaml`

**Interfaces:**
- Consumes: `BrandShippingProfileRepository.findByBrandPartner_Id(Long)` (Task 1).
- Produces: `ShippingCostService.calculate(BrandPartner, ShippingAddress, List<OrderItem>) -> ShippingCostResult`; `ShippingCostResult(BigDecimal amount, String currency, ShippingCalculationMethod method, String ruleVersion, Long brandShippingProfileId)`. Consumed by Task 6.

- [ ] **Step 1: Write the failing unit test**

Create `backend/src/test/java/com/enunas/backend/shipping/FlatRateShippingCostServiceTest.java`:

```java
package com.enunas.backend.shipping;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.brandshippingprofile.BrandShippingProfile;
import com.enunas.backend.brandpartner.brandshippingprofile.BrandShippingProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlatRateShippingCostServiceTest {

    @Mock private BrandShippingProfileRepository brandShippingProfileRepository;

    private FlatRateShippingCostService service;

    @BeforeEach
    void setUp() {
        service = new FlatRateShippingCostService(brandShippingProfileRepository);
        ReflectionTestUtils.setField(service, "defaultRate", new BigDecimal("4.99"));
    }

    private BrandPartner brand(long id) {
        return BrandPartner.builder().id(id).build();
    }

    @Test
    void noProfile_fallsBackToGlobalDefault() {
        when(brandShippingProfileRepository.findByBrandPartner_Id(1L)).thenReturn(Optional.empty());

        ShippingCostResult result = service.calculate(brand(1L), null, List.of());

        assertThat(result.amount()).isEqualByComparingTo("4.99");
        assertThat(result.method()).isEqualTo(ShippingCalculationMethod.GLOBAL_DEFAULT);
        assertThat(result.brandShippingProfileId()).isNull();
        assertThat(result.ruleVersion()).isEqualTo("flat-v1");
    }

    @Test
    void profileWithNullCost_fallsBackToGlobalDefault_butKeepsProfileId() {
        BrandShippingProfile profile = BrandShippingProfile.builder().id(55L).shippingCost(null).build();
        when(brandShippingProfileRepository.findByBrandPartner_Id(2L)).thenReturn(Optional.of(profile));

        ShippingCostResult result = service.calculate(brand(2L), null, List.of());

        assertThat(result.amount()).isEqualByComparingTo("4.99");
        assertThat(result.method()).isEqualTo(ShippingCalculationMethod.GLOBAL_DEFAULT);
        assertThat(result.brandShippingProfileId()).isEqualTo(55L);
    }

    @Test
    void profileWithZeroCost_isExplicitFreeShipping() {
        BrandShippingProfile profile = BrandShippingProfile.builder()
                .id(56L).shippingCost(new BigDecimal("0.00")).currency("EUR").build();
        when(brandShippingProfileRepository.findByBrandPartner_Id(3L)).thenReturn(Optional.of(profile));

        ShippingCostResult result = service.calculate(brand(3L), null, List.of());

        assertThat(result.amount()).isEqualByComparingTo("0.00");
        assertThat(result.method()).isEqualTo(ShippingCalculationMethod.BRAND_FREE_SHIPPING);
        assertThat(result.brandShippingProfileId()).isEqualTo(56L);
    }

    @Test
    void profileWithPositiveCost_isBrandFlatRate() {
        BrandShippingProfile profile = BrandShippingProfile.builder()
                .id(57L).shippingCost(new BigDecimal("6.99")).currency("EUR").build();
        when(brandShippingProfileRepository.findByBrandPartner_Id(4L)).thenReturn(Optional.of(profile));

        ShippingCostResult result = service.calculate(brand(4L), null, List.of());

        assertThat(result.amount()).isEqualByComparingTo("6.99");
        assertThat(result.method()).isEqualTo(ShippingCalculationMethod.BRAND_FLAT_RATE);
        assertThat(result.brandShippingProfileId()).isEqualTo(57L);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails to compile (the production classes don't exist yet)**

Run: `cd backend && ./mvnw test -Dtest=FlatRateShippingCostServiceTest`
Expected: COMPILE ERROR — `ShippingCostResult`, `ShippingCalculationMethod`, `FlatRateShippingCostService` do not exist.

- [ ] **Step 3: Create the enum**

Create `backend/src/main/java/com/enunas/backend/shipping/ShippingCalculationMethod.java`:

```java
package com.enunas.backend.shipping;

/** Which rule produced a shipping amount — persisted on every {@code OrderShippingSnapshot} so
 * the reason is a first-class, auditable fact rather than something inferred from the number. */
public enum ShippingCalculationMethod {
    /** No brand-specific configuration found; the platform default rate was used. */
    GLOBAL_DEFAULT,
    /** The brand has an explicit, positive flat rate. */
    BRAND_FLAT_RATE,
    /** The brand has an explicit {@code 0.00} rate — deliberate free shipping. */
    BRAND_FREE_SHIPPING
}
```

- [ ] **Step 4: Create the result record**

Create `backend/src/main/java/com/enunas/backend/shipping/ShippingCostResult.java`:

```java
package com.enunas.backend.shipping;

import java.math.BigDecimal;

/**
 * @param brandShippingProfileId which {@code BrandShippingProfile} row produced this amount, if
 *                                any — debugging traceability only, never read back into a
 *                                calculation.
 */
public record ShippingCostResult(
        BigDecimal amount,
        String currency,
        ShippingCalculationMethod method,
        String ruleVersion,
        Long brandShippingProfileId
) {}
```

- [ ] **Step 5: Create the interface**

Create `backend/src/main/java/com/enunas/backend/shipping/ShippingCostService.java`:

```java
package com.enunas.backend.shipping;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.order.OrderItem;
import com.enunas.backend.order.ShippingAddress;

import java.util.List;

/**
 * Calculates shipping cost per brand for a cart. {@code destination} and {@code brandItems} are
 * part of the contract now so future country/weight/express implementations never need a
 * signature change — {@link FlatRateShippingCostService} (V1) ignores both and prices purely
 * from the brand.
 */
public interface ShippingCostService {

    ShippingCostResult calculate(BrandPartner brand, ShippingAddress destination, List<OrderItem> brandItems);
}
```

- [ ] **Step 6: Create the V1 implementation**

Create `backend/src/main/java/com/enunas/backend/shipping/FlatRateShippingCostService.java`:

```java
package com.enunas.backend.shipping;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.brandshippingprofile.BrandShippingProfile;
import com.enunas.backend.brandpartner.brandshippingprofile.BrandShippingProfileRepository;
import com.enunas.backend.order.OrderItem;
import com.enunas.backend.order.ShippingAddress;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * V1 shipping calculation: one flat rate per brand, resolved from {@link BrandShippingProfile}
 * with a platform-wide fallback. {@code destination}/{@code brandItems} are accepted but ignored
 * — see {@link ShippingCostService}'s javadoc for why the signature carries them anyway.
 */
@Service
@RequiredArgsConstructor
public class FlatRateShippingCostService implements ShippingCostService {

    static final String RULE_VERSION = "flat-v1";
    private static final String CURRENCY = "EUR";

    private final BrandShippingProfileRepository brandShippingProfileRepository;

    @Value("${enunas.shipping.default-rate}")
    private BigDecimal defaultRate;

    @Override
    public ShippingCostResult calculate(BrandPartner brand, ShippingAddress destination, List<OrderItem> brandItems) {
        Optional<BrandShippingProfile> profile = brand != null
                ? brandShippingProfileRepository.findByBrandPartner_Id(brand.getId())
                : Optional.empty();

        if (profile.isEmpty() || profile.get().getShippingCost() == null) {
            return new ShippingCostResult(
                    defaultRate, CURRENCY, ShippingCalculationMethod.GLOBAL_DEFAULT, RULE_VERSION,
                    profile.map(BrandShippingProfile::getId).orElse(null));
        }

        BrandShippingProfile p = profile.get();
        BigDecimal rate = p.getShippingCost();
        ShippingCalculationMethod method = rate.compareTo(BigDecimal.ZERO) == 0
                ? ShippingCalculationMethod.BRAND_FREE_SHIPPING
                : ShippingCalculationMethod.BRAND_FLAT_RATE;
        String currency = p.getCurrency() != null ? p.getCurrency() : CURRENCY;
        return new ShippingCostResult(rate, currency, method, RULE_VERSION, p.getId());
    }
}
```

- [ ] **Step 7: Add the default-rate config to both yaml files**

In `backend/src/main/resources/application.yaml`, under the existing `enunas:` block (after the `brand:` sub-block), add:

```yaml
  shipping:
    default-rate: 4.99
```

So the block reads:

```yaml
enunas:
  platform:
    commission-rate: 0.18
  payout:
    hold-days: 7
    release-cron: "0 0 2 * * ?"
  vat:
    product-rate: 0.19
    service-rate: 0.19
  brand:
    vat-id-required: false
  shipping:
    default-rate: 4.99
```

In `backend/src/test/resources/application-test.yaml`, under the existing `enunas:` block, add the same:

```yaml
enunas:
  platform:
    commission-rate: 0.18
  payout:
    hold-days: 7
    release-cron: "0 0 2 * * ?"
  shipping:
    default-rate: 4.99
```

- [ ] **Step 8: Run the test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=FlatRateShippingCostServiceTest`
Expected: PASS — 4 tests green.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/shipping \
        backend/src/test/java/com/enunas/backend/shipping \
        backend/src/main/resources/application.yaml \
        backend/src/test/resources/application-test.yaml
git commit -m "feat(shipping): add ShippingCostService flat-rate calculation layer"
```

---

### Task 3: `OrderShippingSnapshot` entity + repository

**Files:**
- Create: `backend/src/main/java/com/enunas/backend/order/OrderShippingSnapshot.java`
- Create: `backend/src/main/java/com/enunas/backend/order/OrderShippingSnapshotRepository.java`

**Interfaces:**
- Consumes: `ShippingCalculationMethod` (Task 2).
- Produces: `OrderShippingSnapshot` entity (builder: `orderId`, `brandPartnerId`, `amount`, `currency`, `calculationMethod`, `ruleVersion`, `brandShippingProfileId`), `OrderShippingSnapshotRepository.findByOrderIdOrderByIdAsc(Long)`. Consumed by Task 4, 5, 6, 7.

This is a plain data-holder entity + a single derived-query repository — following this codebase's own convention (`OrderItemRepository`, `ReturnOrderRepository` have no dedicated unit tests either); it's exercised end-to-end by Task 6's integration test, which is where "this entity persists and reads back correctly" actually gets proven against a real database.

- [ ] **Step 1: Create the entity**

Create `backend/src/main/java/com/enunas/backend/order/OrderShippingSnapshot.java`:

```java
package com.enunas.backend.order;

import com.enunas.backend.shipping.ShippingCalculationMethod;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Immutable per-(order, brand) shipping charge, frozen at order creation — never updated
 * afterward. Represents exactly what the customer paid for that brand's shipment, even if the
 * brand's {@link com.enunas.backend.brandpartner.brandshippingprofile.BrandShippingProfile} rate
 * changes later.
 */
@Entity
@Table(name = "order_shipping_snapshots")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderShippingSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "brand_partner_id", nullable = false)
    private Long brandPartnerId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "EUR";

    @Enumerated(EnumType.STRING)
    @Column(name = "calculation_method", nullable = false, length = 30)
    private ShippingCalculationMethod calculationMethod;

    @Column(name = "rule_version", nullable = false, length = 20)
    private String ruleVersion;

    /** Debugging traceability only — never read back into any calculation. */
    @Column(name = "brand_shipping_profile_id")
    private Long brandShippingProfileId;

    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 2: Create the repository**

Create `backend/src/main/java/com/enunas/backend/order/OrderShippingSnapshotRepository.java`:

```java
package com.enunas.backend.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderShippingSnapshotRepository extends JpaRepository<OrderShippingSnapshot, Long> {

    List<OrderShippingSnapshot> findByOrderIdOrderByIdAsc(Long orderId);
}
```

- [ ] **Step 3: Verify it compiles**

Run: `cd backend && ./mvnw compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/order/OrderShippingSnapshot.java \
        backend/src/main/java/com/enunas/backend/order/OrderShippingSnapshotRepository.java
git commit -m "feat(shipping): add OrderShippingSnapshot entity and repository"
```

---

### Task 4: `LedgerService.recordShippingRevenue`

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/ledger/LedgerService.java`
- Test: `backend/src/test/java/com/enunas/backend/ledger/LedgerServiceShippingRevenueTest.java`

**Interfaces:**
- Consumes: `OrderShippingSnapshot` (Task 3), `LedgerEntryType.SHIPPING_REVENUE` (Task 1).
- Produces: `LedgerService.recordShippingRevenue(Order order, List<OrderShippingSnapshot> snapshots)`. Consumed by Task 7.

- [ ] **Step 1: Write the failing unit test**

Create `backend/src/test/java/com/enunas/backend/ledger/LedgerServiceShippingRevenueTest.java`:

```java
package com.enunas.backend.ledger;

import com.enunas.backend.brandpartner.brandeconomics.BrandEconomics;
import com.enunas.backend.brandpartner.brandeconomics.BrandEconomicsRepository;
import com.enunas.backend.order.Order;
import com.enunas.backend.order.OrderShippingSnapshot;
import com.enunas.backend.shipping.ShippingCalculationMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerServiceShippingRevenueTest {

    @Mock private LedgerRepository ledgerRepository;
    @Mock private BrandEconomicsRepository brandEconomicsRepository;

    private LedgerService ledgerService;

    @BeforeEach
    void setUp() {
        ledgerService = new LedgerService(ledgerRepository, brandEconomicsRepository);
        ReflectionTestUtils.setField(ledgerService, "globalCommissionRate", new BigDecimal("0.18"));
        ReflectionTestUtils.setField(ledgerService, "holdDays", 7);
    }

    private OrderShippingSnapshot snapshot(Long brandId, String amount) {
        return OrderShippingSnapshot.builder()
                .orderId(1L).brandPartnerId(brandId).amount(new BigDecimal(amount)).currency("EUR")
                .calculationMethod(ShippingCalculationMethod.BRAND_FLAT_RATE).ruleVersion("flat-v1")
                .build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordShippingRevenue_creditsBrandAndNeverTouchesCommission() {
        Order order = mock(Order.class);
        when(order.getId()).thenReturn(1L);

        BrandEconomics eco = BrandEconomics.builder().build();
        when(brandEconomicsRepository.findByBrandPartner_Id(5L)).thenReturn(Optional.of(eco));
        when(ledgerRepository.existsByOrderIdAndEntryType(1L, LedgerEntryType.SHIPPING_REVENUE)).thenReturn(false);

        ledgerService.recordShippingRevenue(order, List.of(snapshot(5L, "4.99")));

        assertThat(eco.getPendingBalance()).isEqualByComparingTo("4.99");
        assertThat(eco.getLifetimeRevenue()).isEqualByComparingTo("4.99");

        ArgumentCaptor<List<LedgerEntry>> cap = ArgumentCaptor.forClass(List.class);
        verify(ledgerRepository).saveAll(cap.capture());
        LedgerEntry entry = cap.getValue().get(0);
        assertThat(entry.getEntryType()).isEqualTo(LedgerEntryType.SHIPPING_REVENUE);
        assertThat(entry.getPlatformFee()).isEqualByComparingTo("0");
        assertThat(entry.getCommissionNet()).isEqualByComparingTo("0");
        assertThat(entry.getBrandPayout()).isEqualByComparingTo("4.99");
    }

    @Test
    void recordShippingRevenue_idempotent_skipsWhenAlreadyRecorded() {
        Order order = mock(Order.class);
        when(order.getId()).thenReturn(1L);
        when(ledgerRepository.existsByOrderIdAndEntryType(1L, LedgerEntryType.SHIPPING_REVENUE)).thenReturn(true);

        ledgerService.recordShippingRevenue(order, List.of(snapshot(5L, "4.99")));

        verify(ledgerRepository, never()).saveAll(any());
        verifyNoInteractions(brandEconomicsRepository);
    }

    @Test
    void recordShippingRevenue_emptyList_noOp() {
        Order order = mock(Order.class);

        ledgerService.recordShippingRevenue(order, List.of());

        verifyNoInteractions(ledgerRepository, brandEconomicsRepository);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=LedgerServiceShippingRevenueTest`
Expected: COMPILE ERROR / FAIL — `recordShippingRevenue` does not exist on `LedgerService`.

- [ ] **Step 3: Add `recordShippingRevenue` to `LedgerService`**

Add this import near the top of `backend/src/main/java/com/enunas/backend/ledger/LedgerService.java` (alongside the existing `com.enunas.backend.order.Order` / `com.enunas.backend.order.OrderItem` imports):

```java
import com.enunas.backend.order.OrderShippingSnapshot;
```

Add this method to `LedgerService`, directly after `recordOrderPayment` (i.e. after the closing brace that follows the `log.info("LedgerService: recorded {} entries for orderId={}", entries.size(), order.getId());` line):

```java
    /**
     * Creates one LedgerEntry (SHIPPING_REVENUE) per {@link OrderShippingSnapshot} and increments
     * each brand's pendingBalance/lifetimeRevenue — the same hold-then-release pipeline product
     * entries use. platformFee/commissionNet/commissionVat are always ZERO here, so shipping money
     * cannot leak into commission math by construction, not just by convention. Idempotent — safe
     * to call on duplicate webhooks.
     */
    @Transactional
    public void recordShippingRevenue(Order order, List<OrderShippingSnapshot> snapshots) {
        if (snapshots.isEmpty()) return;
        if (ledgerRepository.existsByOrderIdAndEntryType(order.getId(), LedgerEntryType.SHIPPING_REVENUE)) {
            log.warn("LedgerService: SHIPPING_REVENUE already recorded for orderId={}; skipping", order.getId());
            return;
        }

        LocalDateTime eligibleAt = LocalDateTime.now().plusDays(holdDays);
        Map<Long, BigDecimal> pendingIncrement  = new HashMap<>();
        Map<Long, BigDecimal> lifetimeIncrement = new HashMap<>();
        List<LedgerEntry> entries = new ArrayList<>();

        for (OrderShippingSnapshot snapshot : snapshots) {
            entries.add(LedgerEntry.builder()
                    .orderId(order.getId())
                    .brandPartnerId(snapshot.getBrandPartnerId())
                    .totalAmount(snapshot.getAmount())
                    .platformFee(BigDecimal.ZERO)
                    .brandPayout(snapshot.getAmount())
                    .commissionNet(BigDecimal.ZERO)
                    .commissionVat(BigDecimal.ZERO)
                    .commissionRate(BigDecimal.ZERO)
                    .currency(snapshot.getCurrency())
                    .entryType(LedgerEntryType.SHIPPING_REVENUE)
                    .status(LedgerEntryStatus.PENDING_RELEASE)
                    .payoutEligibleAt(eligibleAt)
                    .movedToAvailable(false)
                    .build());

            pendingIncrement.merge(snapshot.getBrandPartnerId(), snapshot.getAmount(), BigDecimal::add);
            lifetimeIncrement.merge(snapshot.getBrandPartnerId(), snapshot.getAmount(), BigDecimal::add);
        }

        ledgerRepository.saveAll(entries);

        for (Long brandId : pendingIncrement.keySet()) {
            BrandEconomics eco = brandEconomicsRepository.findByBrandPartner_Id(brandId)
                    .orElseThrow(() -> new IllegalStateException(
                            "BrandEconomics missing for brand " + brandId + " — cannot record SHIPPING_REVENUE"));
            eco.setPendingBalance(eco.getPendingBalance().add(pendingIncrement.get(brandId)));
            eco.setLifetimeRevenue(eco.getLifetimeRevenue().add(lifetimeIncrement.get(brandId)));
            brandEconomicsRepository.save(eco);
        }

        log.info("LedgerService: recorded {} SHIPPING_REVENUE entries for orderId={}", entries.size(), order.getId());
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=LedgerServiceShippingRevenueTest`
Expected: PASS — 3 tests green.

- [ ] **Step 5: Run the full existing ledger test suite to confirm no regression**

Run: `cd backend && ./mvnw test -Dtest=LedgerServiceSnapshotTest`
Expected: PASS — unchanged, existing behavior untouched.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/ledger/LedgerService.java \
        backend/src/test/java/com/enunas/backend/ledger/LedgerServiceShippingRevenueTest.java
git commit -m "feat(shipping): LedgerService.recordShippingRevenue"
```

---

### Task 5: Ledger refund split — `reverseProductEntries` / `reverseShippingEntries`

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/ledger/LedgerRepository.java`
- Modify: `backend/src/main/java/com/enunas/backend/ledger/LedgerService.java`
- Test: `backend/src/test/java/com/enunas/backend/ledger/LedgerServiceRefundSplitTest.java`

**Interfaces:**
- Consumes: `LedgerEntryType.SHIPPING_REVENUE` (Task 1).
- Produces: `LedgerRepository.findActiveShippingEntriesByOrderAndBrand(Long, Long)`. Public method signatures of `LedgerService.recordRefund(Order, BigDecimal, String)` and `LedgerService.recordRefund(Order, Long, BigDecimal, String)` are **unchanged** — only their internals are restructured. Consumed by Task 7 (via `OrderService`, already wired) and by the two existing callers (`OrderService.updateOrderStatus`, `RefundPersistenceHelper.persist`) — neither needs to change.

This task must NOT break the existing `LedgerServiceSnapshotTest` (order-wide refund) or the existing `DiscountPaymentFlowIntegrationTest` (brand-scoped refund) — both are re-run as regression checks in this task.

- [ ] **Step 1: Add the shipping-entries query to the repository**

In `backend/src/main/java/com/enunas/backend/ledger/LedgerRepository.java`, add this method directly after `findActivePaymentEntriesByOrderAndBrand`:

```java
    @Query("""
           SELECT le FROM LedgerEntry le
           WHERE le.orderId = :orderId
             AND le.brandPartnerId = :brandPartnerId
             AND le.entryType = com.enunas.backend.ledger.LedgerEntryType.SHIPPING_REVENUE
             AND le.status <> com.enunas.backend.ledger.LedgerEntryStatus.REVERSED
           ORDER BY le.id ASC
           """)
    List<LedgerEntry> findActiveShippingEntriesByOrderAndBrand(
            @Param("orderId") Long orderId,
            @Param("brandPartnerId") Long brandPartnerId);
```

- [ ] **Step 2: Write the failing unit tests**

Create `backend/src/test/java/com/enunas/backend/ledger/LedgerServiceRefundSplitTest.java`:

```java
package com.enunas.backend.ledger;

import com.enunas.backend.brandpartner.brandeconomics.BrandEconomics;
import com.enunas.backend.brandpartner.brandeconomics.BrandEconomicsRepository;
import com.enunas.backend.order.Order;
import com.enunas.backend.order.OrderItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LedgerServiceRefundSplitTest {

    @Mock private LedgerRepository ledgerRepository;
    @Mock private BrandEconomicsRepository brandEconomicsRepository;

    private LedgerService ledgerService;

    @BeforeEach
    void setUp() {
        ledgerService = new LedgerService(ledgerRepository, brandEconomicsRepository);
        ReflectionTestUtils.setField(ledgerService, "globalCommissionRate", new BigDecimal("0.18"));
        ReflectionTestUtils.setField(ledgerService, "holdDays", 7);
    }

    private OrderItem productItem() {
        OrderItem it = mock(OrderItem.class);
        lenient().when(it.getId()).thenReturn(10L);
        lenient().when(it.getBrandId()).thenReturn(5L);
        lenient().when(it.getCommissionRate()).thenReturn(new BigDecimal("0.18"));
        lenient().when(it.getLineTotal()).thenReturn(new BigDecimal("100.00"));
        lenient().when(it.getLineGross()).thenReturn(new BigDecimal("100.00"));
        lenient().when(it.getCommissionNet()).thenReturn(new BigDecimal("18.00"));
        lenient().when(it.getCommissionVat()).thenReturn(BigDecimal.ZERO);
        lenient().when(it.getBrandPayoutAmount()).thenReturn(new BigDecimal("82.00"));
        return it;
    }

    private Order order(OrderItem item, String total) {
        Order o = mock(Order.class);
        lenient().when(o.getId()).thenReturn(1L);
        lenient().when(o.getCurrency()).thenReturn("EUR");
        lenient().when(o.getTotal()).thenReturn(new BigDecimal(total));
        lenient().when(o.getItems()).thenReturn(List.of(item));
        return o;
    }

    private LedgerEntry shippingEntry(BigDecimal amount) {
        return LedgerEntry.builder()
                .id(99L).orderId(1L).brandPartnerId(5L)
                .totalAmount(amount).platformFee(BigDecimal.ZERO).brandPayout(amount)
                .commissionNet(BigDecimal.ZERO).commissionVat(BigDecimal.ZERO).commissionRate(BigDecimal.ZERO)
                .currency("EUR").entryType(LedgerEntryType.SHIPPING_REVENUE).status(LedgerEntryStatus.PENDING_RELEASE)
                .payoutEligibleAt(LocalDateTime.now()).build();
    }

    @Test
    void orderWideCancel_reversesBothProductAndShippingEntries() {
        BrandEconomics eco = BrandEconomics.builder()
                .pendingBalance(new BigDecimal("86.99")) // 82.00 product + 4.99 shipping
                .build();
        when(brandEconomicsRepository.findByBrandPartner_Id(5L)).thenReturn(Optional.of(eco));

        Order ord = order(productItem(), "104.99"); // 100 product + 4.99 shipping

        when(ledgerRepository.existsByExternalReferenceIdAndEntryType("full-cancel", LedgerEntryType.REFUND_REVERSAL))
                .thenReturn(false);
        when(ledgerRepository.existsByExternalReferenceIdAndEntryType("full-cancel:SHIPPING", LedgerEntryType.REFUND_REVERSAL))
                .thenReturn(false);
        when(ledgerRepository.findActivePaymentEntriesByOrderAndBrand(1L, 5L)).thenReturn(List.of());
        when(ledgerRepository.findActiveShippingEntriesByOrderAndBrand(1L, 5L))
                .thenReturn(List.of(shippingEntry(new BigDecimal("4.99"))));

        ledgerService.recordRefund(ord, new BigDecimal("104.99"), "full-cancel");

        // Both product (82.00) and shipping (4.99) fully reversed -> balance back to zero.
        assertThat(eco.getPendingBalance()).isEqualByComparingTo("0.00");

        ArgumentCaptor<LedgerEntry> cap = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledgerRepository, times(2)).save(cap.capture());
        List<LedgerEntry> saved = cap.getAllValues();
        assertThat(saved).extracting(LedgerEntry::getEntryType)
                .containsExactly(LedgerEntryType.REFUND_REVERSAL, LedgerEntryType.REFUND_REVERSAL);
        assertThat(saved.get(1).getExternalReferenceId()).isEqualTo("full-cancel:SHIPPING");
        assertThat(saved.get(1).getBrandPayout()).isEqualByComparingTo("-4.99");
    }

    @Test
    void brandScopedReturn_reversesOnlyProductEntries_shippingUntouched() {
        BrandEconomics eco = BrandEconomics.builder().pendingBalance(new BigDecimal("86.99")).build();
        when(brandEconomicsRepository.findByBrandPartner_Id(5L)).thenReturn(Optional.of(eco));

        Order ord = order(productItem(), "104.99");

        when(ledgerRepository.existsByExternalReferenceIdAndEntryType("return-1", LedgerEntryType.REFUND_REVERSAL))
                .thenReturn(false);
        when(ledgerRepository.findActivePaymentEntriesByOrderAndBrand(1L, 5L)).thenReturn(List.of());

        ledgerService.recordRefund(ord, 5L, new BigDecimal("100.00"), "return-1");

        // Only the 82.00 product payout reversed; shipping's 4.99 stays untouched.
        assertThat(eco.getPendingBalance()).isEqualByComparingTo("4.99");

        verify(ledgerRepository, times(1)).save(any(LedgerEntry.class));
        verify(ledgerRepository, never()).findActiveShippingEntriesByOrderAndBrand(anyLong(), anyLong());
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=LedgerServiceRefundSplitTest`
Expected: FAIL — current `recordRefund` doesn't split product/shipping, so `save(LedgerEntry)` (singular) is never called and shipping is never reversed.

- [ ] **Step 4: Replace the two `recordRefund` methods with the split implementation**

In `backend/src/main/java/com/enunas/backend/ledger/LedgerService.java`, replace the ENTIRE block from the `recordRefund(Order order, BigDecimal refundAmount, String externalRefundId)` method through the end of `recordRefund(Order order, Long brandId, BigDecimal refundAmount, String externalRefundId)` (i.e. everything between the javadoc starting `/**\n     * Order-wide reversal...` and the closing brace right before `/**\n     * Records a completed bank transfer...`) with:

```java
    /**
     * Order-wide reversal: reverses EVERY brand's product AND shipping entries at the same
     * fraction of {@code order.getTotal()}. Correct only when the whole order is being reversed —
     * an admin pre-shipment cancel, or a chargeback against the entire payment. A full-order
     * reversal undoes everything, product and shipping alike.
     *
     * <p>For a return, use {@link #recordRefund(Order, Long, BigDecimal, String)} instead — it
     * deliberately touches product entries only. See {@link #reverseShippingEntries} for why.
     *
     * <p>Both idempotency guards (product and shipping) are checked ONCE per call, before looping
     * brands — a duplicate webhook for the same {@code externalRefundId} must skip the whole
     * reversal, not just the first brand touched.
     */
    @Transactional
    public void recordRefund(Order order, BigDecimal refundAmount, String externalRefundId) {
        boolean productAlreadyDone = externalRefundId != null &&
                ledgerRepository.existsByExternalReferenceIdAndEntryType(externalRefundId, LedgerEntryType.REFUND_REVERSAL);
        boolean shippingAlreadyDone = externalRefundId != null &&
                ledgerRepository.existsByExternalReferenceIdAndEntryType(shippingRef(externalRefundId), LedgerEntryType.REFUND_REVERSAL);
        if (productAlreadyDone && shippingAlreadyDone) {
            log.warn("LedgerService: REFUND_REVERSAL already recorded for externalRefundId={}; skipping", externalRefundId);
            return;
        }

        Set<Long> brandIds = new HashSet<>();
        for (OrderItem item : order.getItems()) {
            if (item.getBrandId() != null) brandIds.add(item.getBrandId());
        }

        BigDecimal fraction = fractionOf(refundAmount, order.getTotal());
        for (Long brandId : brandIds) {
            if (!productAlreadyDone) reverseProductEntries(order, brandId, fraction, externalRefundId);
            if (!shippingAlreadyDone) reverseShippingEntries(order, brandId, fraction, externalRefundId);
        }
    }

    /**
     * Creates REFUND_REVERSAL entries for a single BRAND's return, reversing that brand's product
     * entries only. Deduction order: pendingBalance → payoutBalance → outstandingDebt. Idempotent
     * when externalRefundId is provided — safe to call on duplicate webhooks.
     *
     * <p><b>Shipping is deliberately untouched here.</b> This version doesn't refund shipping on a
     * partial per-brand item return (standard policy: shipping is charged once per shipment, not
     * per item) — but it stays fully traceable via its own snapshot and ledger entries for when
     * that policy question needs answering. The seam for that decision is a future
     * {@code RefundPolicyService} — e.g. "the customer returned every item from this brand, should
     * its shipping refund too?" — not this method.
     *
     * @param brandId restricts the reversal to that brand and pro-rates against THAT BRAND's gross
     *                share rather than the order total — so refunding one brand's return leaves
     *                every other brand's ledger untouched.
     */
    @Transactional
    public void recordRefund(Order order, Long brandId, BigDecimal refundAmount, String externalRefundId) {
        if (externalRefundId != null &&
                ledgerRepository.existsByExternalReferenceIdAndEntryType(externalRefundId, LedgerEntryType.REFUND_REVERSAL)) {
            log.warn("LedgerService: REFUND_REVERSAL already recorded for externalRefundId={}; skipping", externalRefundId);
            return;
        }

        BigDecimal basis = brandProductGross(order, brandId);
        if (basis == null) {
            log.warn("LedgerService: no order items for brand {} on order {} — nothing to reverse",
                     brandId, order.getId());
            return;
        }

        BigDecimal fraction = fractionOf(refundAmount, basis);
        reverseProductEntries(order, brandId, fraction, externalRefundId);
    }

    /**
     * Reverses ORDER_PAYMENT entries for one brand at the given fraction — the product/commission
     * side of a refund. Never touches SHIPPING_REVENUE entries; see {@link #reverseShippingEntries}.
     */
    private void reverseProductEntries(Order order, Long brandId, BigDecimal fraction, String externalRefundId) {
        BigDecimal fee = BigDecimal.ZERO, vat = BigDecimal.ZERO, payout = BigDecimal.ZERO;
        BigDecimal rate = null;
        for (OrderItem item : order.getItems()) {
            if (!brandId.equals(item.getBrandId())) continue;
            BigDecimal itemRate = item.getCommissionRate() != null
                    ? item.getCommissionRate() : resolveBrandRate(brandId);
            BigDecimal itemFee = item.getCommissionNet() != null
                    ? item.getCommissionNet()
                    : (item.getPlatformFeeAmount() != null
                        ? item.getPlatformFeeAmount()
                        : item.getLineTotal().multiply(itemRate).setScale(2, RoundingMode.HALF_UP));
            BigDecimal itemVat = item.getCommissionVat() != null ? item.getCommissionVat() : BigDecimal.ZERO;
            BigDecimal itemPayout = item.getBrandPayoutAmount() != null
                    ? item.getBrandPayoutAmount()
                    : item.getLineTotal().subtract(itemFee);
            fee = fee.add(itemFee);
            vat = vat.add(itemVat);
            payout = payout.add(itemPayout);
            rate = itemRate;
        }
        if (rate == null) {
            log.warn("LedgerService: no order items for brand {} on order {} — nothing to reverse (product)",
                    brandId, order.getId());
            return;
        }

        BigDecimal platformPortion = fee.multiply(fraction).setScale(2, RoundingMode.HALF_UP);
        BigDecimal vatPortion      = vat.multiply(fraction).setScale(2, RoundingMode.HALF_UP);
        BigDecimal brandPortion    = payout.multiply(fraction).setScale(2, RoundingMode.HALF_UP);

        List<LedgerEntry> originals = ledgerRepository
                .findActivePaymentEntriesByOrderAndBrand(order.getId(), brandId);

        LedgerEntry reversal = LedgerEntry.builder()
                .orderId(order.getId())
                .orderItemId(originals.isEmpty() ? null : originals.get(0).getOrderItemId())
                .brandPartnerId(brandId)
                .totalAmount(platformPortion.add(vatPortion).add(brandPortion).negate())
                .platformFee(platformPortion.negate())
                .brandPayout(brandPortion.negate())
                .commissionNet(platformPortion.negate())
                .commissionVat(vatPortion.negate())
                .commissionRate(rate)
                .currency(order.getCurrency())
                .entryType(LedgerEntryType.REFUND_REVERSAL)
                .status(LedgerEntryStatus.REVERSED)
                .payoutEligibleAt(LocalDateTime.now())
                .movedToAvailable(false)
                .reversalOfEntryId(originals.isEmpty() ? null : originals.get(0).getId())
                .externalReferenceId(externalRefundId)
                .build();

        ledgerRepository.save(reversal);
        applyBrandDebit(brandId, brandPortion);
        log.info("LedgerService: reversed product entry for orderId={} brandId={} amount={}",
                order.getId(), brandId, brandPortion);
    }

    /**
     * Reverses SHIPPING_REVENUE entries for one brand at the given fraction. Called only from the
     * order-wide refund path — see this method group's javadoc for why the per-brand return path
     * never calls this.
     */
    private void reverseShippingEntries(Order order, Long brandId, BigDecimal fraction, String externalRefundId) {
        List<LedgerEntry> originals = ledgerRepository.findActiveShippingEntriesByOrderAndBrand(order.getId(), brandId);
        if (originals.isEmpty()) return;

        BigDecimal shippingAmount = originals.stream()
                .map(LedgerEntry::getBrandPayout)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal reversedPortion = shippingAmount.multiply(fraction).setScale(2, RoundingMode.HALF_UP);
        if (reversedPortion.signum() == 0) return;

        LedgerEntry reversal = LedgerEntry.builder()
                .orderId(order.getId())
                .brandPartnerId(brandId)
                .totalAmount(reversedPortion.negate())
                .platformFee(BigDecimal.ZERO)
                .brandPayout(reversedPortion.negate())
                .commissionNet(BigDecimal.ZERO)
                .commissionVat(BigDecimal.ZERO)
                .commissionRate(BigDecimal.ZERO)
                .currency(order.getCurrency())
                .entryType(LedgerEntryType.REFUND_REVERSAL)
                .status(LedgerEntryStatus.REVERSED)
                .payoutEligibleAt(LocalDateTime.now())
                .movedToAvailable(false)
                .reversalOfEntryId(originals.get(0).getId())
                .externalReferenceId(shippingRef(externalRefundId))
                .build();

        ledgerRepository.save(reversal);
        applyBrandDebit(brandId, reversedPortion);
        log.info("LedgerService: reversed shipping entry for orderId={} brandId={} amount={}",
                order.getId(), brandId, reversedPortion);
    }

    private static String shippingRef(String externalRefundId) {
        return externalRefundId + ":SHIPPING";
    }

    /** Debits {@code amount} from a brand's balances: pendingBalance → payoutBalance → outstandingDebt. */
    private void applyBrandDebit(Long brandId, BigDecimal amount) {
        BrandEconomics eco = brandEconomicsRepository.findByBrandPartner_Id(brandId)
                .orElseThrow(() -> new IllegalStateException(
                        "BrandEconomics missing for brand " + brandId + " — cannot record REFUND_REVERSAL"));
        BigDecimal remaining = amount;

        if (eco.getPendingBalance().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal fromPending = remaining.min(eco.getPendingBalance());
            eco.setPendingBalance(eco.getPendingBalance().subtract(fromPending));
            remaining = remaining.subtract(fromPending);
        }
        if (remaining.compareTo(BigDecimal.ZERO) > 0 && eco.getPayoutBalance().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal fromAvailable = remaining.min(eco.getPayoutBalance());
            eco.setPayoutBalance(eco.getPayoutBalance().subtract(fromAvailable));
            remaining = remaining.subtract(fromAvailable);
        }
        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            eco.setOutstandingDebt(eco.getOutstandingDebt().add(remaining));
            log.warn("LedgerService: brand {} incurred debt of {}", brandId, remaining);
        }
        brandEconomicsRepository.save(eco);
    }

    private BigDecimal fractionOf(BigDecimal amount, BigDecimal basis) {
        if (basis.signum() == 0) return BigDecimal.ZERO;
        BigDecimal fraction = amount.divide(basis, 6, RoundingMode.HALF_UP);
        if (fraction.compareTo(BigDecimal.ONE) > 0) {
            log.warn("LedgerService: refund {} exceeds basis {} — capping fraction at 1.0", amount, basis);
            return BigDecimal.ONE;
        }
        return fraction;
    }

    /** @return that brand's summed lineGross, or {@code null} if the order has no items for that brand. */
    private BigDecimal brandProductGross(Order order, Long brandId) {
        boolean found = false;
        BigDecimal gross = BigDecimal.ZERO;
        for (OrderItem item : order.getItems()) {
            if (!brandId.equals(item.getBrandId())) continue;
            found = true;
            BigDecimal lineGross = item.getLineGross() != null ? item.getLineGross() : item.getLineTotal();
            gross = gross.add(lineGross);
        }
        return found ? gross : null;
    }
```

Add `import java.util.HashSet;` and `import java.util.Set;` to the top of `LedgerService.java` if not already present (check the existing import block — `java.util.HashMap`, `java.util.List`, `java.util.Map` are already imported; add the two missing ones alongside them).

- [ ] **Step 5: Run the new tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=LedgerServiceRefundSplitTest`
Expected: PASS — 2 tests green.

- [ ] **Step 6: Run the full regression suite for this file**

Run: `cd backend && ./mvnw test -Dtest=LedgerServiceSnapshotTest,LedgerServiceShippingRevenueTest,LedgerServiceRefundSplitTest`
Expected: PASS — all green, including the pre-existing `LedgerServiceSnapshotTest` (its refund assertions still hold: it never stubs `findActiveShippingEntriesByOrderAndBrand`, and Mockito's default answer returns an empty list for that unstubbed call, so the new shipping-reversal step no-ops harmlessly for that test's shipping-less order).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/ledger/LedgerRepository.java \
        backend/src/main/java/com/enunas/backend/ledger/LedgerService.java \
        backend/src/test/java/com/enunas/backend/ledger/LedgerServiceRefundSplitTest.java
git commit -m "refactor(shipping): split ledger refund reversal into product vs shipping"
```

---

### Task 6: `OrderService` — shared pricing draft, checkout wiring, snapshot persistence, API exposure

**Files:**
- Create: `backend/src/main/java/com/enunas/backend/order/OrderPricingDraft.java`
- Create: `backend/src/main/java/com/enunas/backend/order/dto/ShippingSnapshotDto.java`
- Modify: `backend/src/main/java/com/enunas/backend/order/OrderService.java`
- Modify: `backend/src/main/java/com/enunas/backend/order/dto/OrderResponseDto.java`
- Test: `backend/src/test/java/com/enunas/backend/order/integration/ShippingCheckoutIntegrationTest.java`

**Interfaces:**
- Consumes: `ShippingCostService.calculate(...)` (Task 2), `OrderShippingSnapshot`/`OrderShippingSnapshotRepository` (Task 3).
- Produces: `OrderPricingDraft` (public record, fields: `orderItems`, `subtotal`, `discount`, `discountAmount`, `shippingLines`, `shippingTotal`, `total`, `resolvedAddress`, `currency`; nested public record `ShippingLine(Long brandId, String brandName, ShippingCostResult result)`) — consumed by Task 8. `OrderService.buildPricingDraft(CreateOrderDto, User, boolean applyDiscount)` (private — callable because Task 8's `previewOrder` is added to this same class) — consumed by Task 8. `OrderResponseDto.shippingSnapshots` field — consumed by brand dashboard (`BrandPartnerOrderController`, no code change needed there — it already returns `OrderResponseDto`) and admin order detail.

This is the largest task in the plan — it rewrites `OrderService.createOrder`'s body. Read the whole step before editing; it replaces one contiguous block.

- [ ] **Step 1: Write the failing integration test**

Create `backend/src/test/java/com/enunas/backend/order/integration/ShippingCheckoutIntegrationTest.java`:

```java
package com.enunas.backend.order.integration;

import com.enunas.backend.brandpartner.brandshippingprofile.BrandShippingProfile;
import com.enunas.backend.brandpartner.brandshippingprofile.BrandShippingProfileRepository;
import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shipping-domain checkout flow: per-brand snapshot creation, correct order total, global-default
 * fallback, and legacy (pre-feature) orders staying readable with no snapshot rows.
 */
@SuppressWarnings("rawtypes")
class ShippingCheckoutIntegrationTest extends AbstractDiscountIntegrationTest {

    @Autowired private BrandShippingProfileRepository brandShippingProfileRepository;

    @Test
    void multiBrandOrder_createsOnePerBrandShippingSnapshot_withCorrectAmounts() {
        seedCustomer();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        BrandFixture b = seedBrand("BrandB", "brand-b", "0.18");
        brandShippingProfileRepository.save(BrandShippingProfile.builder()
                .brandPartner(a.brand()).shippingCost(new BigDecimal("4.99")).currency("EUR").build());
        brandShippingProfileRepository.save(BrandShippingProfile.builder()
                .brandPartner(b.brand()).shippingCost(new BigDecimal("6.99")).currency("EUR").build());

        long listingA = seedListing(a.brand(), a.user(), "100.00", 10);
        long listingB = seedListing(b.brand(), b.user(), "50.00", 10);
        String token = login("customer@it.local", "Customer123!");

        long oid = orderId(postOrder(token, null, List.of(item(listingA, 1), item(listingB, 1))));

        List<Map<String, Object>> snapshots = jdbc.queryForList(
                "SELECT brand_partner_id, amount, calculation_method FROM order_shipping_snapshots " +
                "WHERE order_id = ? ORDER BY brand_partner_id", oid);
        assertThat(snapshots).hasSize(2);

        Map<Long, BigDecimal> amountByBrand = new HashMap<>();
        for (Map<String, Object> row : snapshots) {
            amountByBrand.put(((Number) row.get("brand_partner_id")).longValue(), (BigDecimal) row.get("amount"));
            assertThat(row.get("calculation_method")).isEqualTo("BRAND_FLAT_RATE");
        }
        assertThat(amountByBrand.get(a.brand().getId())).isEqualByComparingTo("4.99");
        assertThat(amountByBrand.get(b.brand().getId())).isEqualByComparingTo("6.99");

        // Order total = 100 + 50 (products) + 4.99 + 6.99 (shipping) = 161.98.
        Map<String, Object> order = orderRow(oid);
        assertThat((BigDecimal) order.get("shipping_total")).isEqualByComparingTo("11.98");
        assertThat((BigDecimal) order.get("total")).isEqualByComparingTo("161.98");
    }

    @Test
    void brandWithNoProfile_fallsBackToGlobalDefaultRate() {
        seedCustomer();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18"); // no BrandShippingProfile row
        long listingA = seedListing(a.brand(), a.user(), "100.00", 10);
        String token = login("customer@it.local", "Customer123!");

        long oid = orderId(postOrder(token, null, List.of(item(listingA, 1))));

        Map<String, Object> snapshot = jdbc.queryForMap(
                "SELECT amount, calculation_method FROM order_shipping_snapshots WHERE order_id = ?", oid);
        assertThat((BigDecimal) snapshot.get("amount")).isEqualByComparingTo("4.99"); // enunas.shipping.default-rate
        assertThat(snapshot.get("calculation_method")).isEqualTo("GLOBAL_DEFAULT");
    }

    @Test
    void legacyOrderWithNoShippingSnapshots_stillReadableViaApi() {
        seedCustomer();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        long listingA = seedListing(a.brand(), a.user(), "100.00", 10);
        String token = login("customer@it.local", "Customer123!");

        long oid = orderId(postOrder(token, null, List.of(item(listingA, 1))));

        // Simulate a pre-feature order: delete its shipping snapshot rows directly.
        jdbc.update("DELETE FROM order_shipping_snapshots WHERE order_id = ?", oid);

        var resp = rest.exchange("/orders/" + oid, HttpMethod.GET,
                new HttpEntity<>(auth(token)), Map.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat((List<?>) resp.getBody().get("shippingSnapshots")).isEmpty();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=ShippingCheckoutIntegrationTest`
Expected: FAIL — `order_shipping_snapshots` stays empty (createOrder still hardcodes shipping to zero), `shippingSnapshots` is absent from the JSON response.

- [ ] **Step 3: Create `OrderPricingDraft`**

Create `backend/src/main/java/com/enunas/backend/order/OrderPricingDraft.java`:

```java
package com.enunas.backend.order;

import com.enunas.backend.discount.DiscountApplication;
import com.enunas.backend.order.dto.ShippingAddressDto;
import com.enunas.backend.shipping.ShippingCostResult;

import java.math.BigDecimal;
import java.util.List;

/**
 * In-memory result of pricing a cart — shared by {@link OrderService#createOrder} (which
 * persists it) and {@link OrderService#previewOrder} (which doesn't). Keeping both on this single
 * computation path guarantees the checkout preview and the actual Mollie charge can never drift.
 */
public record OrderPricingDraft(
        List<OrderItem> orderItems,
        BigDecimal subtotal,
        DiscountApplication discount,
        BigDecimal discountAmount,
        List<ShippingLine> shippingLines,
        BigDecimal shippingTotal,
        BigDecimal total,
        ShippingAddressDto resolvedAddress,
        String currency
) {
    public record ShippingLine(Long brandId, String brandName, ShippingCostResult result) {}
}
```

- [ ] **Step 4: Create `ShippingSnapshotDto`**

Create `backend/src/main/java/com/enunas/backend/order/dto/ShippingSnapshotDto.java`:

```java
package com.enunas.backend.order.dto;

import com.enunas.backend.order.OrderShippingSnapshot;
import com.enunas.backend.shipping.ShippingCalculationMethod;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class ShippingSnapshotDto {

    private Long brandId;
    private String brandName;
    private BigDecimal amount;
    private String currency;
    private ShippingCalculationMethod calculationMethod;

    public static ShippingSnapshotDto from(OrderShippingSnapshot snapshot, String brandName) {
        return ShippingSnapshotDto.builder()
                .brandId(snapshot.getBrandPartnerId())
                .brandName(brandName)
                .amount(snapshot.getAmount())
                .currency(snapshot.getCurrency())
                .calculationMethod(snapshot.getCalculationMethod())
                .build();
    }
}
```

- [ ] **Step 5: Add the `shippingSnapshots` field to `OrderResponseDto`**

In `backend/src/main/java/com/enunas/backend/order/dto/OrderResponseDto.java`, add this field directly after `private BigDecimal shippingTotal;`:

```java
    /** Per-brand shipping charge for this order. Empty for orders created before this feature —
     * they carry no snapshot rows and remain fully readable; {@code shippingTotal} stays 0 for them. */
    private List<ShippingSnapshotDto> shippingSnapshots;
```

- [ ] **Step 6: Replace `OrderService.createOrder` and add `buildPricingDraft`/`previewOrder`/`toDto` support**

First, add these two fields to the field list at the top of `OrderService` (directly after `private final Validator validator;`):

```java
    private final ShippingCostService shippingCostService;
    private final OrderShippingSnapshotRepository orderShippingSnapshotRepository;
```

Add these imports to `OrderService.java`:

```java
import com.enunas.backend.order.dto.ShippingSnapshotDto;
import com.enunas.backend.shipping.ShippingCostResult;
import com.enunas.backend.shipping.ShippingCostService;
```

(Task 8 adds one more method to this class, `previewOrder`, plus its own `OrderPreviewResponseDto` import — kept out of this task so Task 6 compiles standalone.)

Now replace the ENTIRE `createOrder` method body — from `@PreAuthorize("hasRole('CUSTOMER')")` immediately above `public OrderResponseDto createOrder(CreateOrderDto dto, User buyer) {` through its closing `}` — with:

```java
    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional
    public OrderResponseDto createOrder(CreateOrderDto dto, User buyer) {
        OrderPricingDraft draft = buildPricingDraft(dto, buyer, true);

        ShippingAddressDto resolvedAddress = draft.resolvedAddress();
        ShippingAddress address = ShippingAddress.builder()
                .firstName(resolvedAddress.getFirstName())
                .lastName(resolvedAddress.getLastName())
                .street(resolvedAddress.getStreet())
                .houseNumber(resolvedAddress.getHouseNumber())
                .addressLine2(resolvedAddress.getAddressLine2())
                .city(resolvedAddress.getCity())
                .postalCode(resolvedAddress.getPostalCode())
                .country(resolvedAddress.getCountry())
                .phone(resolvedAddress.getPhone())
                .build();

        Order.OrderBuilder orderBuilder = Order.builder()
                .orderNumber(generateOrderNumber())
                .buyer(buyer)
                .status(OrderStatus.PENDING)
                .shippingAddress(address)
                .subtotal(draft.subtotal())
                .shippingTotal(draft.shippingTotal())
                .total(draft.total())
                .currency(draft.currency())
                .notes(dto.getNotes());

        if (draft.discount() != null) {
            orderBuilder
                    .discountCode(draft.discount().code().getCode())
                    .discountType(draft.discount().type())
                    .discountPercent(draft.discount().percent())
                    .discountAmount(draft.discountAmount())
                    .platformDiscountAmount(draft.discount().platformDiscountAmount())
                    .brandDiscountAmount(draft.discount().brandDiscountAmount());
        }

        Order order = orderBuilder.build();

        Order saved = orderRepository.save(order);
        draft.orderItems().forEach(saved::addItem);
        orderItemRepository.saveAll(draft.orderItems());

        List<OrderShippingSnapshot> snapshots = draft.shippingLines().stream()
                .map(line -> OrderShippingSnapshot.builder()
                        .orderId(saved.getId())
                        .brandPartnerId(line.brandId())
                        .amount(line.result().amount())
                        .currency(line.result().currency())
                        .calculationMethod(line.result().method())
                        .ruleVersion(line.result().ruleVersion())
                        .brandShippingProfileId(line.result().brandShippingProfileId())
                        .build())
                .toList();
        orderShippingSnapshotRepository.saveAll(snapshots);

        String redirectUrl = frontendBaseUrl + "/orders/" + saved.getOrderNumber() + "/confirmation";
        PaymentResult paymentResult;
        try {
            paymentResult = paymentProvider.createPayment(new CreatePaymentCommand(
                    saved.getTotal(),
                    saved.getCurrency(),
                    "Enunas order " + saved.getOrderNumber(),
                    redirectUrl));
        } catch (Exception e) {
            log.error("Payment creation failed for order {}: {}", saved.getOrderNumber(), e.getMessage());
            throw new PaymentException("Could not initiate payment. Please try again.", e);
        }

        // IMPORTANT: payment already created above. If this DB save fails and the
        // transaction rolls back, the provider-side payment is orphaned. Manual reconciliation
        // is required using the paymentId logged below.
        log.info("Payment created: paymentId={} for order={}",
                paymentResult.paymentId(), saved.getOrderNumber());
        paymentRepository.save(Payment.builder()
                .order(saved)
                .amount(saved.getTotal())
                .currency(saved.getCurrency())
                .transactionId(paymentResult.paymentId())
                .build());

        log.info("Order created: {} for buyer: {} (brands: {})",
                saved.getOrderNumber(), buyer.getEmail(), draft.shippingLines().size());

        return OrderResponseDto.from(saved, paymentResult.checkoutUrl())
                .toBuilder()
                .shippingSnapshots(mapShippingSnapshots(saved, snapshots))
                .build();
    }

    /**
     * Prices a cart end to end — listing resolution, stock check, per-item money snapshot,
     * optional discount application, and per-brand shipping — without persisting anything. Shared
     * by {@link #createOrder} and {@link #previewOrder} so the checkout preview and the actual
     * Mollie charge can never drift.
     *
     * @param applyDiscount when {@code false} (checkout preview), the discount code is never
     *                       validated or reserved — {@link com.enunas.backend.discount.DiscountService#validateAndApply}
     *                       has the side effect of reserving code usage, which a repeatable,
     *                       non-committal preview call must never trigger. The preview therefore
     *                       shows pre-discount pricing; the discount is applied and reflected only
     *                       at real order creation.
     */
    private OrderPricingDraft buildPricingDraft(CreateOrderDto dto, User buyer, boolean applyDiscount) {
        List<ProductListing> listings = resolveAndValidateListings(dto.getItems());

        for (int i = 0; i < dto.getItems().size(); i++) {
            OrderItemRequestDto itemDto = dto.getItems().get(i);
            ProductVariant variant = listings.get(i).getVariant();
            if (!variant.hasStock(itemDto.getQuantity())) {
                throw new IllegalStateException(
                        "Insufficient stock for: " + listings.get(i).getProduct().getName() +
                        " (" + variant.getColor() + " / " + variant.getSize() + ")" +
                        " — requested: " + itemDto.getQuantity() +
                        ", available: " + variant.getStockQuantity());
            }
        }

        List<OrderItem> orderItems = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        // brandId -> brand, and brandId -> that brand's OrderItems, in first-seen order — both
        // needed to make one ShippingCostService call per brand after this loop.
        Map<Long, BrandPartner> brandsById = new LinkedHashMap<>();
        Map<Long, List<OrderItem>> itemsByBrand = new LinkedHashMap<>();

        for (int i = 0; i < dto.getItems().size(); i++) {
            OrderItemRequestDto itemDto = dto.getItems().get(i);
            ProductListing pl = listings.get(i);
            ProductVariant variant = pl.getVariant();

            // lineGross is the customer-facing gross (sale gross if on sale, else regular gross).
            BigDecimal effectiveGross = pl.getEffectiveGross();
            BigDecimal lineGross = effectiveGross.multiply(BigDecimal.valueOf(itemDto.getQuantity()));
            subtotal = subtotal.add(lineGross);

            BrandPartner brand = pl.getProduct().getBrand();
            boolean domestic = (brand == null) || brand.isDomestic();
            BigDecimal rate = brand != null ? getBrandCommissionRate(brand.getId()) : BigDecimal.ZERO;

            OrderItem item = OrderItem.builder()
                    .variant(variant)
                    .productSnapshotName(pl.getProduct().getName())
                    .variantSnapshotSku(variant.getSku())
                    .variantSnapshotColor(variant.getColor())
                    .variantSnapshotSize(variant.getSize())
                    .priceAtPurchase(pl.getPrice())
                    .discountPriceAtPurchase(pl.getDiscountPrice())
                    .quantity(itemDto.getQuantity())
                    .lineGross(lineGross)
                    .lineTotal(lineGross)
                    .build();
            // Pre-discount pass: freezes lineNet/baseCommissionNet so the discount guard can read them.
            item.applyMoneySnapshot(rate, domestic, vatRateProduct, vatRateService);
            orderItems.add(item);

            if (brand != null) {
                brandsById.putIfAbsent(brand.getId(), brand);
                itemsByBrand.computeIfAbsent(brand.getId(), k -> new ArrayList<>()).add(item);
            }
        }

        // Apply optional discount code (max one per order — no stacking). Re-runs the money
        // snapshot per item with the NET discount shares folded in, so the ledger (which reads
        // commissionNet/commissionVat/brandPayout) stays correct per brand. Reserves usage — see
        // this method's javadoc for why this is skipped entirely when applyDiscount is false.
        DiscountApplication discount = null;
        if (applyDiscount && dto.getDiscountCode() != null && !dto.getDiscountCode().isBlank()) {
            discount = discountService.validateAndApply(dto.getDiscountCode(), orderItems);
            for (int i = 0; i < orderItems.size(); i++) {
                OrderItem item = orderItems.get(i);
                DiscountApplication.ItemShare share = discount.itemShares().get(i);
                BigDecimal itemPercent = share.total().signum() > 0 ? discount.percent() : BigDecimal.ZERO;
                item.applyMoneySnapshot(item.getCommissionRate(), Boolean.TRUE.equals(item.getBrandIsDomestic()),
                        vatRateProduct, vatRateService,
                        share.platformShareNet(), share.brandShareNet(), itemPercent);
            }
        }

        // Customer-facing reduction is GROSS (= Σ lineGross − Σ customerGrossAfterDiscount); the
        // platform/brand discount aggregates on the Order are the NET absorption shares.
        BigDecimal customerSubtotalAfterDiscount = orderItems.stream()
                .map(OrderItem::getCustomerGrossAfterDiscount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal discountAmount = subtotal.subtract(customerSubtotalAfterDiscount);

        ShippingAddressDto resolvedAddress = resolveShippingAddress(dto, buyer);
        ShippingAddress destination = ShippingAddress.builder()
                .firstName(resolvedAddress.getFirstName())
                .lastName(resolvedAddress.getLastName())
                .street(resolvedAddress.getStreet())
                .houseNumber(resolvedAddress.getHouseNumber())
                .addressLine2(resolvedAddress.getAddressLine2())
                .city(resolvedAddress.getCity())
                .postalCode(resolvedAddress.getPostalCode())
                .country(resolvedAddress.getCountry())
                .phone(resolvedAddress.getPhone())
                .build();

        // 5. Shipping — one ShippingCostService call per distinct brand on the cart.
        List<OrderPricingDraft.ShippingLine> shippingLines = new ArrayList<>();
        BigDecimal shippingTotal = BigDecimal.ZERO;
        for (Map.Entry<Long, BrandPartner> e : brandsById.entrySet()) {
            BrandPartner brand = e.getValue();
            List<OrderItem> brandItems = itemsByBrand.get(e.getKey());
            ShippingCostResult result = shippingCostService.calculate(brand, destination, brandItems);
            shippingLines.add(new OrderPricingDraft.ShippingLine(brand.getId(), brand.getBrandName(), result));
            shippingTotal = shippingTotal.add(result.amount());
        }

        // 6. total = subtotal − discountAmount + shippingTotal — this is what Mollie charges and
        //    the webhook trusts (see Order.total's own javadoc, which already documented this
        //    formula before shipping was wired up to stop being hardcoded zero).
        BigDecimal total = subtotal.subtract(discountAmount).add(shippingTotal);

        return new OrderPricingDraft(orderItems, subtotal, discount, discountAmount,
                shippingLines, shippingTotal, total, resolvedAddress,
                listings.get(0).getCurrency());
    }

    private List<ShippingSnapshotDto> mapShippingSnapshots(Order order, List<OrderShippingSnapshot> snapshots) {
        Map<Long, String> brandNames = new HashMap<>();
        for (OrderItem item : order.getItems()) {
            if (item.getBrandId() != null) brandNames.putIfAbsent(item.getBrandId(), item.getBrandSnapshotName());
        }
        return snapshots.stream()
                .map(s -> ShippingSnapshotDto.from(s, brandNames.get(s.getBrandPartnerId())))
                .toList();
    }
```

Note: this drops the old `distinctBrandIds`/`brandSubtotals` local variables from `createOrder` — `distinctBrandIds` was only ever read for its `.size()` in the final log line (now `draft.shippingLines().size()`, one entry per distinct brand — the same count), and `brandSubtotals` was built but never read anywhere. Removing genuinely dead code while already rewriting this method for real reasons.

- [ ] **Step 7: Update `toDto` to attach `shippingSnapshots`**

In `OrderService.java`, replace the existing `toDto` method:

```java
    private OrderResponseDto toDto(Order order) {
        if (order.getStatus() == OrderStatus.RETURN_REQUESTED
                || order.getStatus() == OrderStatus.RETURN_APPROVED
                || order.getStatus() == OrderStatus.RETURN_RECEIVED
                || order.getStatus() == OrderStatus.REFUNDED) {
            List<ReturnOrder> returns = returnOrderRepository.findByOrder_IdOrderByIdAsc(order.getId());
            if (!returns.isEmpty()) {
                return OrderResponseDto.withReturns(order, returns);
            }
        }
        return OrderResponseDto.from(order);
    }
```

with:

```java
    private OrderResponseDto toDto(Order order) {
        OrderResponseDto base;
        if (order.getStatus() == OrderStatus.RETURN_REQUESTED
                || order.getStatus() == OrderStatus.RETURN_APPROVED
                || order.getStatus() == OrderStatus.RETURN_RECEIVED
                || order.getStatus() == OrderStatus.REFUNDED) {
            List<ReturnOrder> returns = returnOrderRepository.findByOrder_IdOrderByIdAsc(order.getId());
            base = !returns.isEmpty() ? OrderResponseDto.withReturns(order, returns) : OrderResponseDto.from(order);
        } else {
            base = OrderResponseDto.from(order);
        }

        List<OrderShippingSnapshot> snapshots = orderShippingSnapshotRepository.findByOrderIdOrderByIdAsc(order.getId());
        return base.toBuilder().shippingSnapshots(mapShippingSnapshots(order, snapshots)).build();
    }
```

(This adds one extra query per order rendered through `toDto` — the same per-row cost this codebase already accepts for `returns`, just unconditional here since shipping is core order info rather than exceptional state. Not solved further in this task — a documented, accepted tradeoff, not an oversight.)

- [ ] **Step 8: Run the test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=ShippingCheckoutIntegrationTest`
Expected: PASS — 3 tests green.

- [ ] **Step 9: Run the broader existing test suite for regressions**

Run: `cd backend && ./mvnw test -Dtest=DiscountPaymentFlowIntegrationTest,LegacyOrderFallbackTest,CreateOrderDtoValidationTest,OrderDtoValidationTest`
Expected: PASS — order creation/discount/legacy-fallback behavior unaffected by the refactor.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/order/OrderPricingDraft.java \
        backend/src/main/java/com/enunas/backend/order/dto/ShippingSnapshotDto.java \
        backend/src/main/java/com/enunas/backend/order/OrderService.java \
        backend/src/main/java/com/enunas/backend/order/dto/OrderResponseDto.java \
        backend/src/test/java/com/enunas/backend/order/integration/ShippingCheckoutIntegrationTest.java
git commit -m "feat(shipping): wire ShippingCostService into checkout, persist snapshots, expose in API"
```

---

### Task 7: Ledger wiring at payment confirmation + full-cancel regression

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/order/OrderService.java`
- Test: `backend/src/test/java/com/enunas/backend/order/integration/ShippingLedgerIntegrationTest.java`

**Interfaces:**
- Consumes: `LedgerService.recordShippingRevenue` (Task 4), `LedgerService.recordRefund` split (Task 5), `OrderShippingSnapshotRepository` (Task 3, already a field on `OrderService` from Task 6).

- [ ] **Step 1: Write the failing integration test**

Create `backend/src/test/java/com/enunas/backend/order/integration/ShippingLedgerIntegrationTest.java`:

```java
package com.enunas.backend.order.integration;

import com.enunas.backend.brandpartner.brandshippingprofile.BrandShippingProfile;
import com.enunas.backend.brandpartner.brandshippingprofile.BrandShippingProfileRepository;
import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shipping-domain ledger effects: SHIPPING_REVENUE booked on payment confirmation, and a full
 * pre-shipment cancel reversing both product AND shipping money together.
 */
@SuppressWarnings("rawtypes")
class ShippingLedgerIntegrationTest extends AbstractDiscountIntegrationTest {

    @Autowired private BrandShippingProfileRepository brandShippingProfileRepository;

    @Test
    void paymentConfirmation_recordsShippingRevenue_andCreditsBrand() {
        seedCustomer();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        brandShippingProfileRepository.save(BrandShippingProfile.builder()
                .brandPartner(a.brand()).shippingCost(new BigDecimal("4.99")).currency("EUR").build());
        long listingA = seedListing(a.brand(), a.user(), "100.00", 10);
        String token = login("customer@it.local", "Customer123!");

        long oid = orderId(postOrder(token, null, List.of(item(listingA, 1))));
        confirmPaid(oid);

        BigDecimal shippingRevenue = jdbc.queryForObject(
                "SELECT brand_payout FROM ledger_entries WHERE order_id = ? AND entry_type = 'SHIPPING_REVENUE'",
                BigDecimal.class, oid);
        assertThat(shippingRevenue).isEqualByComparingTo("4.99");

        // Product 82.00 (100 gross, 18% commission on net-of-19%-VAT) + shipping 4.99 = 86.99 pending.
        assertThat(brandPending(a.brand().getId())).isEqualByComparingTo("86.99");
    }

    @Test
    void fullPreShipmentCancel_reversesBothProductAndShippingLedgerEntries() {
        seedAdmin();
        seedCustomer();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        brandShippingProfileRepository.save(BrandShippingProfile.builder()
                .brandPartner(a.brand()).shippingCost(new BigDecimal("4.99")).currency("EUR").build());
        long listingA = seedListing(a.brand(), a.user(), "100.00", 10);
        String custToken = login("customer@it.local", "Customer123!");
        String adminToken = login("admin@it.local", "Admin123!");

        long oid = orderId(postOrder(custToken, null, List.of(item(listingA, 1))));
        confirmPaid(oid);
        assertThat(brandPending(a.brand().getId())).isEqualByComparingTo("86.99");

        ResponseEntity<Map> cancelled = rest.exchange(
                "/admin/orders/" + oid + "/status?status=CANCELLED", HttpMethod.PATCH,
                new HttpEntity<>(null, auth(adminToken)), Map.class);
        assertThat(cancelled.getStatusCode().is2xxSuccessful()).as("cancel: %s", cancelled.getBody()).isTrue();

        // Both the 82.00 product payout and the 4.99 shipping payout reversed -> back to zero.
        assertThat(brandPending(a.brand().getId())).isEqualByComparingTo("0.00");
        BigDecimal shippingReversal = jdbc.queryForObject(
                "SELECT brand_payout FROM ledger_entries WHERE order_id = ? AND entry_type = 'REFUND_REVERSAL' " +
                "AND external_reference_id LIKE '%:SHIPPING'", BigDecimal.class, oid);
        assertThat(shippingReversal).isEqualByComparingTo("-4.99");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=ShippingLedgerIntegrationTest`
Expected: FAIL — no `SHIPPING_REVENUE` ledger row exists yet (payment confirmation doesn't call `recordShippingRevenue`).

- [ ] **Step 3: Wire `recordShippingRevenue` into both payment-confirmation paths**

In `backend/src/main/java/com/enunas/backend/order/OrderService.java`, in `confirmPaymentByWebhook`, find:

```java
        order.setStatus(OrderStatus.PAID);
        orderRepository.save(order);

        ledgerService.recordOrderPayment(order);

        log.info("Webhook: order {} PENDING → PAID", order.getOrderNumber());
```

Replace with:

```java
        order.setStatus(OrderStatus.PAID);
        orderRepository.save(order);

        ledgerService.recordOrderPayment(order);
        ledgerService.recordShippingRevenue(order,
                orderShippingSnapshotRepository.findByOrderIdOrderByIdAsc(order.getId()));

        log.info("Webhook: order {} PENDING → PAID", order.getOrderNumber());
```

Then, in `updateOrderStatus`, find:

```java
        // Record ledger entries for admin-forced payment confirmation.
        if (newStatus == OrderStatus.PAID && current == OrderStatus.PENDING) {
            ledgerService.recordOrderPayment(saved);
        }
```

Replace with:

```java
        // Record ledger entries for admin-forced payment confirmation.
        if (newStatus == OrderStatus.PAID && current == OrderStatus.PENDING) {
            ledgerService.recordOrderPayment(saved);
            ledgerService.recordShippingRevenue(saved,
                    orderShippingSnapshotRepository.findByOrderIdOrderByIdAsc(saved.getId()));
        }
```

No other change is needed for the full-cancel path: `postPaymentCancel` still calls `ledgerService.recordRefund(saved, saved.getTotal(), "ADMIN_CANCEL_" + saved.getId())` unchanged — Task 5's split now reverses shipping automatically as part of that same call, and `saved.getTotal()` already includes `shippingTotal` since Task 6.

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=ShippingLedgerIntegrationTest`
Expected: PASS — 2 tests green.

- [ ] **Step 5: Run the broader regression suite**

Run: `cd backend && ./mvnw test -Dtest=DiscountPaymentFlowIntegrationTest,ReturnLifecyclePhase3Test,MultiBrandReturnTest,SettlementIntegrationTest`
Expected: PASS — existing cancel/refund/return flows unaffected.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/order/OrderService.java \
        backend/src/test/java/com/enunas/backend/order/integration/ShippingLedgerIntegrationTest.java
git commit -m "feat(shipping): record SHIPPING_REVENUE on payment confirmation"
```

---

### Task 8: Checkout preview endpoint

**Files:**
- Create: `backend/src/main/java/com/enunas/backend/order/dto/OrderPreviewResponseDto.java`
- Modify: `backend/src/main/java/com/enunas/backend/order/OrderController.java`
- Test: `backend/src/test/java/com/enunas/backend/order/integration/CheckoutPreviewIntegrationTest.java`

**Interfaces:**
- Consumes: `OrderPricingDraft` (Task 6, public) and the private `OrderService.buildPricingDraft(CreateOrderDto, User, boolean)` (Task 6).
- Produces: `OrderService.previewOrder(CreateOrderDto, User)`, `POST /orders/preview` (`@PreAuthorize("hasRole('CUSTOMER')")`, request body = `CreateOrderDto`).

- [ ] **Step 1: Write the failing integration test**

Create `backend/src/test/java/com/enunas/backend/order/integration/CheckoutPreviewIntegrationTest.java`:

```java
package com.enunas.backend.order.integration;

import com.enunas.backend.brandpartner.brandshippingprofile.BrandShippingProfile;
import com.enunas.backend.brandpartner.brandshippingprofile.BrandShippingProfileRepository;
import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("rawtypes")
class CheckoutPreviewIntegrationTest extends AbstractDiscountIntegrationTest {

    @Autowired private BrandShippingProfileRepository brandShippingProfileRepository;

    @Test
    void preview_matchesWhatCreateOrderWouldCharge_andPersistsNothing() {
        seedCustomer();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        brandShippingProfileRepository.save(BrandShippingProfile.builder()
                .brandPartner(a.brand()).shippingCost(new BigDecimal("4.99")).currency("EUR").build());
        long listingA = seedListing(a.brand(), a.user(), "100.00", 10);
        String token = login("customer@it.local", "Customer123!");

        Map<String, Object> address = Map.of(
                "firstName", "John", "lastName", "Doe",
                "street", "Hauptstrasse", "houseNumber", "1",
                "city", "Berlin", "postalCode", "10115", "country", "DE");
        Map<String, Object> body = new HashMap<>();
        body.put("items", List.of(item(listingA, 1)));
        body.put("shippingAddress", address);

        long ordersBefore = jdbc.queryForObject("SELECT count(*) FROM orders", Long.class);

        ResponseEntity<Map> resp = rest.exchange("/orders/preview", HttpMethod.POST,
                new HttpEntity<>(body, auth(token)), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map respBody = resp.getBody();
        assertThat(new BigDecimal(String.valueOf(respBody.get("subtotal")))).isEqualByComparingTo("100.00");
        assertThat(new BigDecimal(String.valueOf(respBody.get("shippingTotal")))).isEqualByComparingTo("4.99");
        assertThat(new BigDecimal(String.valueOf(respBody.get("total")))).isEqualByComparingTo("104.99");

        long ordersAfter = jdbc.queryForObject("SELECT count(*) FROM orders", Long.class);
        assertThat(ordersAfter).isEqualTo(ordersBefore); // preview persists nothing

        long oid = orderId(postOrder(token, null, List.of(item(listingA, 1))));
        assertThat((BigDecimal) orderRow(oid).get("total")).isEqualByComparingTo("104.99");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=CheckoutPreviewIntegrationTest`
Expected: FAIL — `POST /orders/preview` returns 404 (route doesn't exist).

- [ ] **Step 3: Create `OrderPreviewResponseDto`**

Create `backend/src/main/java/com/enunas/backend/order/dto/OrderPreviewResponseDto.java`:

```java
package com.enunas.backend.order.dto;

import com.enunas.backend.order.OrderPricingDraft;
import com.enunas.backend.shipping.ShippingCalculationMethod;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

/**
 * Checkout summary shown before payment — products, shipping, and total broken out separately,
 * computed by the exact same {@link OrderPricingDraft} pipeline {@code createOrder} charges from.
 * Discount codes are NOT reflected here (see {@code OrderService#buildPricingDraft}'s javadoc):
 * previewing must never reserve a code's usage, so {@code discountAmount} is always zero/null on
 * this DTO — the discount is applied and shown only at real order creation.
 */
@Getter
@Builder
public class OrderPreviewResponseDto {

    private List<PreviewItem> items;
    private BigDecimal subtotal;
    private String discountCode;
    private BigDecimal discountAmount;
    private List<PreviewShippingLine> shippingBreakdown;
    private BigDecimal shippingTotal;
    private BigDecimal total;
    private String currency;

    @Getter
    @Builder
    public static class PreviewItem {
        private Long listingId;
        private String productName;
        private int quantity;
        private BigDecimal unitPrice;
        private BigDecimal lineTotal;
    }

    @Getter
    @Builder
    public static class PreviewShippingLine {
        private Long brandId;
        private String brandName;
        private BigDecimal amount;
        private String currency;
        private ShippingCalculationMethod calculationMethod;
    }

    public static OrderPreviewResponseDto from(OrderPricingDraft draft) {
        List<PreviewItem> items = draft.orderItems().stream()
                .map(i -> PreviewItem.builder()
                        .listingId(i.getVariant() != null ? i.getVariant().getId() : null)
                        .productName(i.getProductSnapshotName())
                        .quantity(i.getQuantity())
                        .unitPrice(i.getPriceAtPurchase())
                        .lineTotal(i.getLineGross())
                        .build())
                .toList();

        List<PreviewShippingLine> shippingLines = draft.shippingLines().stream()
                .map(l -> PreviewShippingLine.builder()
                        .brandId(l.brandId())
                        .brandName(l.brandName())
                        .amount(l.result().amount())
                        .currency(l.result().currency())
                        .calculationMethod(l.result().method())
                        .build())
                .toList();

        return OrderPreviewResponseDto.builder()
                .items(items)
                .subtotal(draft.subtotal())
                .discountCode(draft.discount() != null ? draft.discount().code().getCode() : null)
                .discountAmount(draft.discountAmount())
                .shippingBreakdown(shippingLines)
                .shippingTotal(draft.shippingTotal())
                .total(draft.total())
                .currency(draft.currency())
                .build();
    }
}
```

- [ ] **Step 4: Add `previewOrder` to `OrderService`**

In `backend/src/main/java/com/enunas/backend/order/OrderService.java`, add this import:

```java
import com.enunas.backend.order.dto.OrderPreviewResponseDto;
```

Add this method directly after `createOrder` (before `buildPricingDraft`):

```java
    @PreAuthorize("hasRole('CUSTOMER')")
    @Transactional(readOnly = true)
    public OrderPreviewResponseDto previewOrder(CreateOrderDto dto, User buyer) {
        return OrderPreviewResponseDto.from(buildPricingDraft(dto, buyer, false));
    }
```

- [ ] **Step 5: Add the controller route**

In `backend/src/main/java/com/enunas/backend/order/OrderController.java`, add this import:

```java
import com.enunas.backend.order.dto.OrderPreviewResponseDto;
```

Add this endpoint directly after `createOrder`:

```java
    @PostMapping("/preview")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<OrderPreviewResponseDto> previewOrder(
            @Valid @RequestBody CreateOrderDto dto,
            @AuthenticationPrincipal User buyer) {
        return ResponseEntity.ok(orderService.previewOrder(dto, buyer));
    }
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=CheckoutPreviewIntegrationTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/order/dto/OrderPreviewResponseDto.java \
        backend/src/main/java/com/enunas/backend/order/OrderController.java \
        backend/src/test/java/com/enunas/backend/order/integration/CheckoutPreviewIntegrationTest.java
git commit -m "feat(shipping): add POST /orders/preview checkout summary"
```

---

### Task 9: Settlement `shippingRevenue`

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/ledger/LedgerRepository.java`
- Modify: `backend/src/main/java/com/enunas/backend/settlement/dto/SettlementRowDto.java`
- Modify: `backend/src/main/java/com/enunas/backend/settlement/SettlementService.java`
- Test: `backend/src/test/java/com/enunas/backend/discount/integration/SettlementIntegrationTest.java` (add one method)

**Interfaces:**
- Consumes: `LedgerEntryType.SHIPPING_REVENUE` (Task 1).
- Produces: `SettlementRowDto.shippingRevenue`, `LedgerRepository.PeriodAggregate.getShippingRevenue()`.

`shippingRevenue` reports gross SHIPPING_REVENUE booked in the period; it is not netted against later shipping-specific refunds in this version (the combined `payoutAmount` figure — the one actually wired to bank transfers — already nets correctly, since shipping REFUND_REVERSAL rows carry a negative `brandPayout` and are included in that sum). This is a deliberate, documented V1 simplification, not a bug.

- [ ] **Step 1: Write the failing test**

In `backend/src/test/java/com/enunas/backend/discount/integration/SettlementIntegrationTest.java`, add this test method (anywhere among the other `@Test` methods, before the `// ===== helpers =====` marker):

```java
    // ===== 6. Shipping revenue reports separately from commission and folds into payout =====
    @Test
    void shippingRevenueAggregatesSeparately_fromCommissionAndPayout() {
        seedAdmin();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        long bid = a.brand().getId();
        String admin = login("admin@it.local", "Admin123!");

        // One product sale (net 100, 18% commission -> 18.00 net + 3.42 VAT, payout 97.58) plus
        // one shipping charge (4.99, zero commission) in the same period.
        insertLedger(bid, "ORDER_PAYMENT", "2026-05-10 12:00:00", "18.00", "3.42", "97.58", "119.00");
        insertLedger(bid, "SHIPPING_REVENUE", "2026-05-10 12:00:00", "0.00", "0.00", "4.99", "4.99");

        Map<String, Object> may = row(admin, "2026-05", bid);
        assertThat(bd(may.get("commissionNet"))).isEqualByComparingTo("18.00");    // unaffected by shipping
        assertThat(bd(may.get("commissionVat"))).isEqualByComparingTo("3.42");
        assertThat(bd(may.get("shippingRevenue"))).isEqualByComparingTo("4.99");   // reported separately
        assertThat(bd(may.get("payoutAmount"))).isEqualByComparingTo("102.57");    // 97.58 product + 4.99 shipping
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=SettlementIntegrationTest#shippingRevenueAggregatesSeparately_fromCommissionAndPayout`
Expected: FAIL — `shippingRevenue` key absent from the response (the aggregate query doesn't select it, and `SHIPPING_REVENUE` rows aren't even in the current query's `entryType IN (...)` filter, so `payoutAmount` is also short by 4.99).

- [ ] **Step 3: Update the period-aggregate query and interface**

In `backend/src/main/java/com/enunas/backend/ledger/LedgerRepository.java`, replace the `PeriodAggregate` interface:

```java
    /** Per-brand period aggregate for the monthly settlement report. */
    interface PeriodAggregate {
        Long getBrandId();
        BigDecimal getCommissionNet();
        BigDecimal getCommissionVat();
        BigDecimal getPayoutAmount();
        BigDecimal getTotalAmount();
        BigDecimal getShippingRevenue();
        Long getOrderCount();
        Long getRefundCount();
    }
```

And replace the `aggregateByBrandForPeriod` query:

```java
    /**
     * Sums each brand's ledger figures over a period (UTC bounds, end-exclusive), counting
     * ORDER_PAYMENT and REFUND_REVERSAL entries by their own created_at. REFUND_REVERSAL rows are
     * stored negative, so a plain period-filtered SUM nets refunds against sales automatically.
     * SHIPPING_REVENUE is included so payoutAmount/totalAmount correctly include shipping money;
     * shippingRevenue itself is reported as its own column, gross (not netted against a later
     * shipping-specific refund — see this method's caller-side javadoc for why that's fine).
     */
    @Query("""
           SELECT le.brandPartnerId AS brandId,
                  COALESCE(SUM(le.commissionNet), 0) AS commissionNet,
                  COALESCE(SUM(le.commissionVat), 0) AS commissionVat,
                  COALESCE(SUM(le.brandPayout), 0)   AS payoutAmount,
                  COALESCE(SUM(le.totalAmount), 0)   AS totalAmount,
                  COALESCE(SUM(CASE WHEN le.entryType = com.enunas.backend.ledger.LedgerEntryType.SHIPPING_REVENUE
                                     THEN le.brandPayout ELSE 0 END), 0) AS shippingRevenue,
                  SUM(CASE WHEN le.entryType = com.enunas.backend.ledger.LedgerEntryType.ORDER_PAYMENT   THEN 1 ELSE 0 END) AS orderCount,
                  SUM(CASE WHEN le.entryType = com.enunas.backend.ledger.LedgerEntryType.REFUND_REVERSAL THEN 1 ELSE 0 END) AS refundCount
           FROM LedgerEntry le
           WHERE le.createdAt >= :startUtc AND le.createdAt < :endUtc
             AND le.entryType IN (com.enunas.backend.ledger.LedgerEntryType.ORDER_PAYMENT,
                                  com.enunas.backend.ledger.LedgerEntryType.REFUND_REVERSAL,
                                  com.enunas.backend.ledger.LedgerEntryType.SHIPPING_REVENUE)
           GROUP BY le.brandPartnerId
           """)
    List<PeriodAggregate> aggregateByBrandForPeriod(
            @Param("startUtc") LocalDateTime startUtc,
            @Param("endUtc") LocalDateTime endUtc);
```

- [ ] **Step 4: Add `shippingRevenue` to `SettlementRowDto`**

In `backend/src/main/java/com/enunas/backend/settlement/dto/SettlementRowDto.java`, add the field directly after `private final BigDecimal payoutAmount;`:

```java
    private final BigDecimal shippingRevenue;
```

In `SettlementRowDto.from(...)`, add `.shippingRevenue(nz(a.getShippingRevenue()))` to the builder chain (directly after `.payoutAmount(nz(a.getPayoutAmount()))`).

- [ ] **Step 5: Add the zero default in `SettlementService.zeroRow`**

In `backend/src/main/java/com/enunas/backend/settlement/SettlementService.java`, in the `zeroRow` method, add `.shippingRevenue(BigDecimal.ZERO)` to the builder chain (directly after `.payoutAmount(BigDecimal.ZERO)`).

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=SettlementIntegrationTest`
Expected: PASS — all 6 tests green (5 pre-existing + the new one).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/ledger/LedgerRepository.java \
        backend/src/main/java/com/enunas/backend/settlement/dto/SettlementRowDto.java \
        backend/src/main/java/com/enunas/backend/settlement/SettlementService.java \
        backend/src/test/java/com/enunas/backend/discount/integration/SettlementIntegrationTest.java
git commit -m "feat(shipping): expose shippingRevenue in the monthly settlement report"
```

---

### Task 10: Admin shipping-profile endpoint

**Files:**
- Create: `backend/src/main/java/com/enunas/backend/admin/dto/SetShippingProfileDto.java`
- Modify: `backend/src/main/java/com/enunas/backend/admin/AdminService.java`
- Modify: `backend/src/main/java/com/enunas/backend/admin/AdminController.java`
- Test: `backend/src/test/java/com/enunas/backend/order/integration/AdminShippingProfileIntegrationTest.java`

**Interfaces:**
- Consumes: `BrandShippingProfileRepository.findByBrandPartner_Id(Long)` (Task 1).
- Produces: `PATCH /admin/brands/{brandId}/shipping-profile`.

- [ ] **Step 1: Write the failing integration test**

Create `backend/src/test/java/com/enunas/backend/order/integration/AdminShippingProfileIntegrationTest.java`:

```java
package com.enunas.backend.order.integration;

import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("rawtypes")
class AdminShippingProfileIntegrationTest extends AbstractDiscountIntegrationTest {

    @Test
    void adminSetsShippingProfile_thenCheckoutUsesIt() {
        seedAdmin();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        seedCustomer();
        long listingA = seedListing(a.brand(), a.user(), "100.00", 10);
        String adminToken = login("admin@it.local", "Admin123!");
        String custToken = login("customer@it.local", "Customer123!");

        ResponseEntity<Map> resp = rest.exchange(
                "/admin/brands/" + a.brand().getId() + "/shipping-profile",
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("shippingCost", 6.99, "originCountry", "DE"), auth(adminToken)),
                Map.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        long oid = orderId(postOrder(custToken, null, List.of(item(listingA, 1))));

        BigDecimal amount = jdbc.queryForObject(
                "SELECT amount FROM order_shipping_snapshots WHERE order_id = ?", BigDecimal.class, oid);
        assertThat(amount).isEqualByComparingTo("6.99");

        BigDecimal storedCost = jdbc.queryForObject(
                "SELECT shipping_cost FROM brand_shipping_profiles WHERE brand_id = ?",
                BigDecimal.class, a.brand().getId());
        assertThat(storedCost).isEqualByComparingTo("6.99");
    }

    @Test
    void adminSetsExplicitFreeShipping_zeroIsDistinctFromUnset() {
        seedAdmin();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        seedCustomer();
        long listingA = seedListing(a.brand(), a.user(), "100.00", 10);
        String adminToken = login("admin@it.local", "Admin123!");
        String custToken = login("customer@it.local", "Customer123!");

        rest.exchange("/admin/brands/" + a.brand().getId() + "/shipping-profile", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("shippingCost", 0.00), auth(adminToken)), Map.class);

        long oid = orderId(postOrder(custToken, null, List.of(item(listingA, 1))));

        Map<String, Object> snapshot = jdbc.queryForMap(
                "SELECT amount, calculation_method FROM order_shipping_snapshots WHERE order_id = ?", oid);
        assertThat((BigDecimal) snapshot.get("amount")).isEqualByComparingTo("0.00");
        assertThat(snapshot.get("calculation_method")).isEqualTo("BRAND_FREE_SHIPPING");
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=AdminShippingProfileIntegrationTest`
Expected: FAIL — `PATCH /admin/brands/{brandId}/shipping-profile` returns 404.

- [ ] **Step 3: Create the request DTO**

Create `backend/src/main/java/com/enunas/backend/admin/dto/SetShippingProfileDto.java`:

```java
package com.enunas.backend.admin.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
public class SetShippingProfileDto {

    /** Flat shipping cost for this brand, in EUR. {@code null} clears it back to "not configured"
     * (falls back to the platform default rate). {@code 0.00} explicitly marks the brand as
     * free-shipping — see {@code BrandShippingProfile#shippingCost}'s javadoc for why these are
     * two different, deliberately distinct outcomes. */
    @DecimalMin(value = "0.00", message = "must not be negative")
    private BigDecimal shippingCost;

    @Size(min = 2, max = 2)
    private String originCountry;

    private Integer avgShippingDays;
}
```

- [ ] **Step 4: Add `setBrandShippingProfile` to `AdminService`**

In `backend/src/main/java/com/enunas/backend/admin/AdminService.java`, add these imports:

```java
import com.enunas.backend.admin.dto.SetShippingProfileDto;
import com.enunas.backend.brandpartner.brandshippingprofile.BrandShippingProfile;
import com.enunas.backend.brandpartner.brandshippingprofile.BrandShippingProfileRepository;
```

Add this field to the field list (directly after `private final BrandPayoutProfileRepository brandPayoutProfileRepository;`):

```java
    private final BrandShippingProfileRepository brandShippingProfileRepository;
```

Add this method directly after `setBrandPayoutProfile` (before the `// ===== Payouts =====` marker):

```java
    @Transactional
    public BrandPartnerResponseDto setBrandShippingProfile(Long brandId, SetShippingProfileDto dto) {
        BrandPartner brand = findBrand(brandId);
        brandShippingProfileRepository.findByBrandPartner_Id(brandId).ifPresentOrElse(
                profile -> {
                    profile.setShippingCost(dto.getShippingCost());
                    profile.setOriginCountry(dto.getOriginCountry());
                    profile.setAvgShippingDays(dto.getAvgShippingDays());
                    brandShippingProfileRepository.save(profile);
                },
                () -> brandShippingProfileRepository.save(
                        BrandShippingProfile.builder()
                                .brandPartner(brand)
                                .shippingCost(dto.getShippingCost())
                                .originCountry(dto.getOriginCountry())
                                .avgShippingDays(dto.getAvgShippingDays())
                                .currency("EUR")
                                .build())
        );
        log.info("Admin set shipping profile for brand {}: shippingCost={}", brand.getBrandName(), dto.getShippingCost());
        return BrandPartnerResponseDto.from(brand);
    }
```

- [ ] **Step 5: Add the controller route**

In `backend/src/main/java/com/enunas/backend/admin/AdminController.java`, add this import:

```java
import com.enunas.backend.admin.dto.SetShippingProfileDto;
```

Add this endpoint directly after `setBrandPayoutProfile`:

```java
    @PatchMapping("/brands/{brandId}/shipping-profile")
    public ResponseEntity<BrandPartnerResponseDto> setBrandShippingProfile(
            @PathVariable Long brandId,
            @Valid @RequestBody SetShippingProfileDto dto) {
        return ResponseEntity.ok(adminService.setBrandShippingProfile(brandId, dto));
    }
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=AdminShippingProfileIntegrationTest`
Expected: PASS — 2 tests green.

- [ ] **Step 7: Run the full test suite as a final regression pass**

Run: `cd backend && ./mvnw test`
Expected: PASS — every test in the suite green.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/admin/dto/SetShippingProfileDto.java \
        backend/src/main/java/com/enunas/backend/admin/AdminService.java \
        backend/src/main/java/com/enunas/backend/admin/AdminController.java \
        backend/src/test/java/com/enunas/backend/order/integration/AdminShippingProfileIntegrationTest.java
git commit -m "feat(shipping): admin endpoint to configure a brand's shipping profile"
```

---

## Post-plan notes (not tasks — read before shipping to production)

- **Config value:** `enunas.shipping.default-rate: 4.99` is a placeholder default picked to match the design doc's worked example — confirm the real platform default with the user before this reaches production.
- **N+1 on order list endpoints:** `toDto` now issues one extra query per order for its shipping snapshots (Task 6, Step 7) — an accepted, documented tradeoff matching this codebase's existing `returns`-lookup pattern, not something this plan attempts to solve.
- **Discount codes are invisible to the checkout preview** (Task 6/8) — a deliberate correctness choice (previewing must never reserve discount usage), not an oversight. If product wants discount-aware previews later, it needs a non-reserving `DiscountService` read path first.
