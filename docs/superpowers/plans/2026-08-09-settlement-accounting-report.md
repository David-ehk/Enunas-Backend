# Settlement Accounting Report & EÜR Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a platform-level, period-scoped Settlement Accounting Report — a neutral, provider-agnostic export (JSON + flat CSV) that aggregates the existing ledger/payout data into EÜR-ready figures and booking lines, without introducing a second money-calculation model.

**Architecture:** A new read-mostly `settlement.accounting` package sits *beside* the existing `settlement` package (which stays untouched). It (a) extends `LedgerRepository`/`PayoutRepository` with two purely-additive aggregate queries, (b) adds one small admin-editable table for the two facts the system genuinely does not have (Mollie fees, a confirmed payout reference), and (c) computes everything else live from the ledger on every GET — same pattern as `Vat22fExportService` (no snapshot table for the derived numbers, so there is no second copy that can drift from the ledger).

**Tech Stack:** Spring Boot 4 / JPA / Flyway (existing stack, no new dependencies).

## Global Constraints

- No new business logic for payments, commission, discounts, or refunds (spec item 16) — every money figure is a SUM over existing `LedgerEntry`/`Payout` rows, never recomputed from `Order`/`OrderItem`.
- All persisted/reported money uses `BigDecimal`, HALF_UP, 2 decimals — reuse `com.enunas.backend.common.MoneyMath` (existing convention, see `[[reference_money_model]]`).
- Admin-only (`@PreAuthorize("hasRole('ADMIN')")`), same as `SettlementController`/`Vat22fExportController`.
- No silent reconciliation: `reconciliationStatus` is `RECONCILED` only when every relevant figure is present AND diff is exactly `0.00` — never inferred, never defaulted to green.
- No fabricated numbers where no authoritative source exists (Mollie fees, actual payout): fields stay `null` until an admin enters them or a matching `PAID` `Payout` row exists — see "Design Decisions" below.

## Design Decisions (read before implementing)

These resolve gaps between the spec and what the codebase actually has. Two were confirmed with the user; the rest are documented choices made during this analysis pass — flag to the user if a task's numbers don't reconcile against these before proceeding.

1. **Report grain = platform-level per period** (user-confirmed). `settlementId = "SET-" + period` (e.g. `SET-2026-08`), reusing the existing `YYYY-MM` period convention from `SettlementRun` rather than inventing a parallel sequence number. The existing per-brand `SettlementRun`/`SettlementService` is untouched; the new report's `brands[]` array reuses the same underlying ledger aggregation, extended with two more columns (see Task 2).
2. **`mollieFees` / `actualPayoutAmount` = manual/best-effort, not live-integrated** (user-confirmed). `mollieFees`, `mollieFeesIncludedInActualPayout`, `payoutReference` are admin-entered per period (new `settlement_accounting_inputs` table). `actualPayoutAmount` is best-effort: `SUM(Payout.amount)` where `status = PAID` and `paidAt` falls inside the period — if no such `Payout` exists, the field stays `null` and `reconciliationStatus` is forced to `UNRECONCILED` (never silently treated as zero).
3. **`reconciliationDifference`/`reconciliationStatus`** is the ledger-internal invariant: `totalCustomerPayments − (enunasCommissionGross + brandPayoutAmount)`. This is always computable from ledger data alone and should always be `0.00` in a healthy system — a nonzero value means the ledger itself is inconsistent (see Task 10's `Reconciliation Failure` test). `reconciliationStatus = RECONCILED` additionally requires `actualPayoutAmount` to be present AND equal to `brandPayoutAmount` (`payoutDifference == 0`). Because `Payout` timing rarely lines up exactly with a calendar period (7-day hold, batched transfers), most periods will legitimately show `UNRECONCILED` until an admin confirms the payout — that is intentional, not a bug (see the "Refund after Brand Payout" test, Task 9).
4. **`brandProductAmount`/`brandShippingAmount`/`brandPayoutAmount`** use `LedgerEntry.brandPayout` (net-to-brand, what's actually owed), not `totalAmount` (gross). `brandTotalAmount` and `totalCustomerPayments` both use `totalAmount` (gross) — in this single-sided marketplace (Enunas sells nothing of its own) they are numerically identical by construction; both are kept because the spec names them in different report sections.
5. **Skipped fields**: `settlementDifference`/`ledgerDifference` from spec item 12 are not added as separate fields — they would be redundant aliases of `reconciliationDifference` (there is no second calculation path to diff them against, and inventing one would violate the "no second money model" constraint). Only `payoutDifference` is added as a genuinely distinct, nullable control value.
6. **Signed vs. positive amounts**: `refundAmount` is always reported as a positive absolute value (unambiguous — a refund is always a reduction, so its sign is redundant). `enunasCommissionNet`/`Vat`/`Gross` are reported as their true signed value (can be negative in a net-refund month) — same convention the existing `SettlementRowDto.isCreditNote` already relies on. Forcing these positive would hide a real credit-note month, which contradicts the spec's own "no hidden numbers" principle.
7. **CSV export** covers the flat settlement-level row from spec section 4 (one row per period, exact column order from the spec's example). The nested booking lines and brand breakdown are available via the JSON GET (spec doesn't require a separate booking-lines CSV endpoint, only that they be "ableitbar" from the report — JSON already satisfies that).
8. **Closed-period guard**: reuses `SettlementService`'s exact rule (period only reportable once the 1st of the *following* month, Berlin, has passed) via `PeriodNotClosedException` — small (~5 line) duplication rather than extracting a shared utility, to keep this task's blast radius inside the new package only.

## File Structure

```
backend/src/main/resources/db/migration/
  V23__settlement_accounting_input.sql          [create]

backend/src/main/java/com/enunas/backend/settlement/accounting/
  SettlementAccountingInput.java                [create] admin-entered per-period facts (entity)
  SettlementAccountingInputRepository.java      [create]
  BookingType.java                              [create] enum: INCOME_COMMISSION, EXPENSE_MOLLIE_FEE
  ReconciliationStatus.java                     [create] enum: RECONCILED, UNRECONCILED
  SettlementAccountingReportService.java        [create] core aggregation + reconciliation
  SettlementAccountingReportController.java     [create] GET report, GET export, PUT admin input
  dto/
    AccountingBookingLineDto.java               [create] Ebene B row
    BrandAccountingBreakdownDto.java             [create] brands[] entry
    SettlementAccountingReportDto.java           [create] top-level response
    UpsertAccountingInputDto.java                [create] admin input request body

backend/src/main/java/com/enunas/backend/ledger/
  LedgerRepository.java                         [modify] + AccountingPeriodAggregate projection, + query

backend/src/main/java/com/enunas/backend/payout/
  PayoutRepository.java                         [modify] + sumPaidAmountInRange, + countPaidPayoutsInRange

backend/src/test/java/com/enunas/backend/discount/integration/
  AbstractDiscountIntegrationTest.java          [modify] TRUNCATE list + releaseAllPending() helper

backend/src/test/java/com/enunas/backend/ledger/
  LedgerRepositoryAccountingAggregateTest.java  [create] @DataJpaTest

backend/src/test/java/com/enunas/backend/payout/
  PayoutRepositoryPaidInRangeTest.java          [create] @DataJpaTest

backend/src/test/java/com/enunas/backend/settlement/accounting/
  SettlementAccountingReportServiceTest.java    [create] unit (mocked repos)
  SettlementAccountingReportIntegrationTest.java [create] full HTTP integration, all 8 spec scenarios
```

---

### Task 1: `settlement_accounting_inputs` table + entity + repository

**Files:**
- Create: `backend/src/main/resources/db/migration/V23__settlement_accounting_input.sql`
- Create: `backend/src/main/java/com/enunas/backend/settlement/accounting/SettlementAccountingInput.java`
- Create: `backend/src/main/java/com/enunas/backend/settlement/accounting/SettlementAccountingInputRepository.java`
- Test: `backend/src/test/java/com/enunas/backend/settlement/accounting/SettlementAccountingInputRepositoryTest.java`

**Interfaces:**
- Produces: `SettlementAccountingInput` (getters: `getPeriod()`, `getMollieFees()`, `getMollieFeesIncludedInActualPayout()`, `getPayoutReference()`, `getMollieSettlementDate()`, `getNotes()`, `getEnteredByAdminEmail()`, `getEnteredAt()`, `getUpdatedAt()`); `SettlementAccountingInputRepository.findByPeriod(String period): Optional<SettlementAccountingInput>`.

- [ ] **Step 1: Write the failing test**

```java
package com.enunas.backend.settlement.accounting;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class SettlementAccountingInputRepositoryTest {

    @Autowired private SettlementAccountingInputRepository repository;

    @Test
    void savesAndFindsByPeriod() {
        repository.save(SettlementAccountingInput.builder()
                .period("2026-08")
                .mollieFees(new BigDecimal("35.70"))
                .mollieFeesIncludedInActualPayout(false)
                .payoutReference("stl_test123")
                .mollieSettlementDate(LocalDate.of(2026, 8, 8))
                .enteredByAdminEmail("admin@it.local")
                .enteredAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build());

        SettlementAccountingInput found = repository.findByPeriod("2026-08").orElseThrow();
        assertThat(found.getMollieFees()).isEqualByComparingTo("35.70");
        assertThat(found.getMollieFeesIncludedInActualPayout()).isFalse();
        assertThat(found.getPayoutReference()).isEqualTo("stl_test123");

        assertThat(repository.findByPeriod("2026-09")).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=SettlementAccountingInputRepositoryTest -q`
Expected: FAIL — compile error, `SettlementAccountingInput` does not exist.

- [ ] **Step 3: Write the migration**

```sql
-- =============================================================================
-- V23: Settlement Accounting Report — admin-entered per-period facts
--
-- Additive only. Holds the two facts the system has no authoritative source for
-- (Mollie fees, confirmed payout reference) so SettlementAccountingReportService
-- can merge them into the live-computed ledger figures without fabricating data.
-- One row per calendar period ('YYYY-MM'); everything else in the report is
-- derived fresh from ledger_entries/payouts on every request.
-- =============================================================================

CREATE TABLE IF NOT EXISTS settlement_accounting_inputs (
    id                                     BIGSERIAL PRIMARY KEY,
    period                                 VARCHAR(7)    NOT NULL,   -- 'YYYY-MM'
    mollie_fees                            NUMERIC(10,2),
    mollie_fees_included_in_actual_payout  BOOLEAN,
    payout_reference                       VARCHAR(100),
    mollie_settlement_date                 DATE,
    notes                                  VARCHAR(1000),
    entered_by_admin_email                 VARCHAR(255)  NOT NULL,
    entered_at                             TIMESTAMP     NOT NULL,
    updated_at                             TIMESTAMP     NOT NULL,
    CONSTRAINT uq_settlement_accounting_input_period UNIQUE (period)
);
```

- [ ] **Step 4: Write the entity**

```java
package com.enunas.backend.settlement.accounting;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Admin-entered facts for one calendar period that the system has no authoritative source for:
 * what Mollie actually deducted in fees, and the confirmed external settlement/payout reference.
 * Everything else in {@link SettlementAccountingReportService}'s report is derived live from
 * ledger_entries/payouts — this table exists ONLY for the two genuinely-external facts, so there
 * is no second, potentially-stale copy of anything the ledger already knows.
 */
@Entity
@Table(name = "settlement_accounting_inputs",
        uniqueConstraints = @UniqueConstraint(name = "uq_settlement_accounting_input_period", columnNames = "period"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SettlementAccountingInput {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Calendar month, 'YYYY-MM'. */
    @Column(nullable = false, length = 7)
    private String period;

    @Column(name = "mollie_fees", precision = 10, scale = 2)
    private BigDecimal mollieFees;

    @Column(name = "mollie_fees_included_in_actual_payout")
    private Boolean mollieFeesIncludedInActualPayout;

    @Column(name = "payout_reference", length = 100)
    private String payoutReference;

    @Column(name = "mollie_settlement_date")
    private LocalDate mollieSettlementDate;

    @Column(length = 1000)
    private String notes;

    @Column(name = "entered_by_admin_email", nullable = false)
    private String enteredByAdminEmail;

    @Column(name = "entered_at", nullable = false)
    private LocalDateTime enteredAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 5: Write the repository**

```java
package com.enunas.backend.settlement.accounting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SettlementAccountingInputRepository extends JpaRepository<SettlementAccountingInput, Long> {
    Optional<SettlementAccountingInput> findByPeriod(String period);
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=SettlementAccountingInputRepositoryTest -q`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/resources/db/migration/V23__settlement_accounting_input.sql \
        backend/src/main/java/com/enunas/backend/settlement/accounting/SettlementAccountingInput.java \
        backend/src/main/java/com/enunas/backend/settlement/accounting/SettlementAccountingInputRepository.java \
        backend/src/test/java/com/enunas/backend/settlement/accounting/SettlementAccountingInputRepositoryTest.java
git commit -m "feat(accounting): add settlement_accounting_inputs table for admin-entered facts"
```

---

### Task 2: `LedgerRepository` accounting aggregate (product/shipping split, refunds)

The existing `aggregateByBrandForPeriod`/`PeriodAggregate` (used by `SettlementService`) reports `shippingRevenue` gross, not netted against shipping refunds — its own javadoc flags this. Rather than propagate that gap into an accounting document, add a new, additive projection with a clean split. `reverseShippingEntries` already tags shipping refunds with an `:SHIPPING` suffix on `externalReferenceId` (see `LedgerService.shippingRef`) — reuse that existing signal instead of adding new columns.

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/ledger/LedgerRepository.java`
- Test: `backend/src/test/java/com/enunas/backend/ledger/LedgerRepositoryAccountingAggregateTest.java`

**Interfaces:**
- Produces: `LedgerRepository.AccountingPeriodAggregate` projection (`getBrandId()`, `getCommissionNet()`, `getCommissionVat()`, `getProductRevenueNet()`, `getShippingRevenueNet()`, `getRefundAmount()`, `getTotalAmount()`, `getOrderCount()`, `getRefundCount()`); `LedgerRepository.aggregateAccountingByBrandForPeriod(LocalDateTime startUtc, LocalDateTime endUtc): List<AccountingPeriodAggregate>`.

- [ ] **Step 1: Write the failing test**

```java
package com.enunas.backend.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class LedgerRepositoryAccountingAggregateTest {

    @Autowired private LedgerRepository ledgerRepository;

    private LedgerEntry entry(LedgerEntryType type, BigDecimal total, BigDecimal commissionNet,
                              BigDecimal commissionVat, BigDecimal brandPayout, String externalRef) {
        return LedgerEntry.builder()
                .brandPartnerId(1L)
                .totalAmount(total)
                .platformFee(commissionNet)
                .brandPayout(brandPayout)
                .commissionNet(commissionNet)
                .commissionVat(commissionVat)
                .commissionRate(new BigDecimal("0.1800"))
                .currency("EUR")
                .entryType(type)
                .status(LedgerEntryStatus.PENDING_RELEASE)
                .payoutEligibleAt(LocalDateTime.now())
                .externalReferenceId(externalRef)
                .build();
    }

    @Test
    void splitsProductAndShippingNetOfTheirOwnRefunds() {
        LocalDateTime start = LocalDateTime.of(2026, 8, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 1, 0, 0);

        // Product sale: 119 gross, 18.00 net commission, 3.42 VAT, 97.58 to brand.
        ledgerRepository.save(entry(LedgerEntryType.ORDER_PAYMENT,
                new BigDecimal("119.00"), new BigDecimal("18.00"), new BigDecimal("3.42"),
                new BigDecimal("97.58"), null));
        // Shipping: 10.00, zero commission.
        ledgerRepository.save(entry(LedgerEntryType.SHIPPING_REVENUE,
                new BigDecimal("10.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("10.00"), null));
        // Product refund: half the product entry reversed.
        ledgerRepository.save(entry(LedgerEntryType.REFUND_REVERSAL,
                new BigDecimal("-59.50"), new BigDecimal("-9.00"), new BigDecimal("-1.71"),
                new BigDecimal("-48.79"), "re_product1"));
        // Shipping refund: the whole shipping entry reversed (tagged :SHIPPING).
        ledgerRepository.save(entry(LedgerEntryType.REFUND_REVERSAL,
                new BigDecimal("-10.00"), BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("-10.00"), "re_product1:SHIPPING"));

        List<LedgerRepository.AccountingPeriodAggregate> rows =
                ledgerRepository.aggregateAccountingByBrandForPeriod(start, end);

        assertThat(rows).hasSize(1);
        LedgerRepository.AccountingPeriodAggregate row = rows.get(0);
        assertThat(row.getBrandId()).isEqualTo(1L);
        assertThat(row.getCommissionNet()).isEqualByComparingTo("9.00");     // 18.00 - 9.00
        assertThat(row.getCommissionVat()).isEqualByComparingTo("1.71");    // 3.42 - 1.71
        assertThat(row.getProductRevenueNet()).isEqualByComparingTo("48.79"); // 97.58 - 48.79
        assertThat(row.getShippingRevenueNet()).isEqualByComparingTo("0.00"); // 10.00 - 10.00, fully refunded
        assertThat(row.getRefundAmount()).isEqualByComparingTo("69.50");    // 59.50 + 10.00, reported positive
        assertThat(row.getOrderCount()).isEqualTo(1L);
        assertThat(row.getRefundCount()).isEqualTo(2L);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=LedgerRepositoryAccountingAggregateTest -q`
Expected: FAIL — compile error, `AccountingPeriodAggregate`/`aggregateAccountingByBrandForPeriod` do not exist.

- [ ] **Step 3: Add the projection and query to `LedgerRepository`**

Add inside the `LedgerRepository` interface, after the existing `PeriodAggregate` interface (do not modify `PeriodAggregate` or `aggregateByBrandForPeriod` — both stay exactly as-is, used by the existing `SettlementService`):

```java
    /** Per-brand period aggregate for the accounting report — product/shipping split net of their
     *  own refunds (unlike PeriodAggregate.shippingRevenue, which is gross-only; see its javadoc). */
    interface AccountingPeriodAggregate {
        Long getBrandId();
        BigDecimal getCommissionNet();
        BigDecimal getCommissionVat();
        BigDecimal getProductRevenueNet();
        BigDecimal getShippingRevenueNet();
        BigDecimal getRefundAmount();
        BigDecimal getTotalAmount();
        Long getOrderCount();
        Long getRefundCount();
    }

    /**
     * Same period/entry-type scope as {@link #aggregateByBrandForPeriod}, but splits brandPayout
     * into product vs. shipping, each already netted against its own refunds. Shipping refunds are
     * identified by the {@code :SHIPPING} suffix {@link LedgerService#recordRefund(com.enunas.backend.order.Order, java.math.BigDecimal, String)}
     * already tags them with — no new column, reuses the existing signal. refundAmount is reported
     * positive (sum of both product and shipping REFUND_REVERSAL rows, which are stored negative).
     */
    @Query("""
           SELECT le.brandPartnerId AS brandId,
                  COALESCE(SUM(le.commissionNet), 0) AS commissionNet,
                  COALESCE(SUM(le.commissionVat), 0) AS commissionVat,
                  COALESCE(SUM(CASE
                      WHEN le.entryType = com.enunas.backend.ledger.LedgerEntryType.ORDER_PAYMENT THEN le.brandPayout
                      WHEN le.entryType = com.enunas.backend.ledger.LedgerEntryType.REFUND_REVERSAL
                           AND (le.externalReferenceId IS NULL OR le.externalReferenceId NOT LIKE '%:SHIPPING')
                           THEN le.brandPayout
                      ELSE 0 END), 0) AS productRevenueNet,
                  COALESCE(SUM(CASE
                      WHEN le.entryType = com.enunas.backend.ledger.LedgerEntryType.SHIPPING_REVENUE THEN le.brandPayout
                      WHEN le.entryType = com.enunas.backend.ledger.LedgerEntryType.REFUND_REVERSAL
                           AND le.externalReferenceId LIKE '%:SHIPPING'
                           THEN le.brandPayout
                      ELSE 0 END), 0) AS shippingRevenueNet,
                  COALESCE(SUM(CASE WHEN le.entryType = com.enunas.backend.ledger.LedgerEntryType.REFUND_REVERSAL
                                     THEN -le.totalAmount ELSE 0 END), 0) AS refundAmount,
                  COALESCE(SUM(le.totalAmount), 0) AS totalAmount,
                  SUM(CASE WHEN le.entryType = com.enunas.backend.ledger.LedgerEntryType.ORDER_PAYMENT   THEN 1 ELSE 0 END) AS orderCount,
                  SUM(CASE WHEN le.entryType = com.enunas.backend.ledger.LedgerEntryType.REFUND_REVERSAL THEN 1 ELSE 0 END) AS refundCount
           FROM LedgerEntry le
           WHERE le.createdAt >= :startUtc AND le.createdAt < :endUtc
             AND le.entryType IN (com.enunas.backend.ledger.LedgerEntryType.ORDER_PAYMENT,
                                  com.enunas.backend.ledger.LedgerEntryType.REFUND_REVERSAL,
                                  com.enunas.backend.ledger.LedgerEntryType.SHIPPING_REVENUE)
           GROUP BY le.brandPartnerId
           """)
    List<AccountingPeriodAggregate> aggregateAccountingByBrandForPeriod(
            @Param("startUtc") LocalDateTime startUtc,
            @Param("endUtc") LocalDateTime endUtc);
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=LedgerRepositoryAccountingAggregateTest -q`
Expected: PASS

- [ ] **Step 5: Run the full existing ledger test suite to confirm no regression**

Run: `cd backend && ./mvnw test -Dtest=com.enunas.backend.ledger.* -q`
Expected: PASS (all pre-existing tests unaffected — `PeriodAggregate`/`aggregateByBrandForPeriod` untouched)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/ledger/LedgerRepository.java \
        backend/src/test/java/com/enunas/backend/ledger/LedgerRepositoryAccountingAggregateTest.java
git commit -m "feat(accounting): add product/shipping-split ledger aggregate, net of own refunds"
```

---

### Task 3: `PayoutRepository` — actual payout in a period

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/payout/PayoutRepository.java`
- Test: `backend/src/test/java/com/enunas/backend/payout/PayoutRepositoryPaidInRangeTest.java`

**Interfaces:**
- Produces: `PayoutRepository.sumPaidAmountInRange(LocalDateTime startUtc, LocalDateTime endUtc): BigDecimal`, `PayoutRepository.countPaidPayoutsInRange(LocalDateTime startUtc, LocalDateTime endUtc): long`.

- [ ] **Step 1: Write the failing test**

```java
package com.enunas.backend.payout;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
class PayoutRepositoryPaidInRangeTest {

    @Autowired private PayoutRepository payoutRepository;

    private Payout payout(BigDecimal amount, PayoutStatus status, LocalDateTime paidAt) {
        return Payout.builder()
                .brandPartnerId(1L)
                .amount(amount)
                .debtAbsorbed(BigDecimal.ZERO)
                .status(status)
                .iban("DE89370400440532013000")
                .bankAccountHolder("BrandA GmbH")
                .currency("EUR")
                .paidAt(paidAt)
                .externalReference(status == PayoutStatus.PAID ? "QONTO-" + amount : null)
                .build();
    }

    @Test
    void sumsOnlyPaidPayoutsWithinRange() {
        LocalDateTime start = LocalDateTime.of(2026, 8, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 9, 1, 0, 0);

        payoutRepository.save(payout(new BigDecimal("97.58"), PayoutStatus.PAID,
                LocalDateTime.of(2026, 8, 15, 10, 0)));       // in range
        payoutRepository.save(payout(new BigDecimal("50.00"), PayoutStatus.PENDING,
                LocalDateTime.of(2026, 8, 16, 10, 0)));       // not PAID, excluded
        payoutRepository.save(payout(new BigDecimal("30.00"), PayoutStatus.PAID,
                LocalDateTime.of(2026, 9, 2, 10, 0)));        // out of range

        assertThat(payoutRepository.sumPaidAmountInRange(start, end)).isEqualByComparingTo("97.58");
        assertThat(payoutRepository.countPaidPayoutsInRange(start, end)).isEqualTo(1L);

        LocalDateTime emptyStart = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime emptyEnd = LocalDateTime.of(2026, 2, 1, 0, 0);
        assertThat(payoutRepository.sumPaidAmountInRange(emptyStart, emptyEnd)).isEqualByComparingTo("0");
        assertThat(payoutRepository.countPaidPayoutsInRange(emptyStart, emptyEnd)).isEqualTo(0L);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=PayoutRepositoryPaidInRangeTest -q`
Expected: FAIL — compile error, methods do not exist.

- [ ] **Step 3: Add the two queries to `PayoutRepository`**

```java
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payout p " +
           "WHERE p.status = com.enunas.backend.payout.PayoutStatus.PAID " +
           "AND p.paidAt >= :startUtc AND p.paidAt < :endUtc")
    BigDecimal sumPaidAmountInRange(@Param("startUtc") java.time.LocalDateTime startUtc,
                                     @Param("endUtc") java.time.LocalDateTime endUtc);

    @Query("SELECT COUNT(p) FROM Payout p " +
           "WHERE p.status = com.enunas.backend.payout.PayoutStatus.PAID " +
           "AND p.paidAt >= :startUtc AND p.paidAt < :endUtc")
    long countPaidPayoutsInRange(@Param("startUtc") java.time.LocalDateTime startUtc,
                                  @Param("endUtc") java.time.LocalDateTime endUtc);
```

(Add `import java.time.LocalDateTime;` at the top instead of the fully-qualified references if preferred — match the existing file's import style.)

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=PayoutRepositoryPaidInRangeTest -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/payout/PayoutRepository.java \
        backend/src/test/java/com/enunas/backend/payout/PayoutRepositoryPaidInRangeTest.java
git commit -m "feat(accounting): add paid-payout-in-range query to PayoutRepository"
```

---

### Task 4: DTOs and enums

**Files:**
- Create: `backend/src/main/java/com/enunas/backend/settlement/accounting/BookingType.java`
- Create: `backend/src/main/java/com/enunas/backend/settlement/accounting/ReconciliationStatus.java`
- Create: `backend/src/main/java/com/enunas/backend/settlement/accounting/dto/AccountingBookingLineDto.java`
- Create: `backend/src/main/java/com/enunas/backend/settlement/accounting/dto/BrandAccountingBreakdownDto.java`
- Create: `backend/src/main/java/com/enunas/backend/settlement/accounting/dto/SettlementAccountingReportDto.java`
- Create: `backend/src/main/java/com/enunas/backend/settlement/accounting/dto/UpsertAccountingInputDto.java`

**Interfaces:**
- Produces: all DTO field names below, consumed by Task 5's service and Task 6's controller verbatim.

This task has no independent behavior to unit-test (pure data holders) — its deliverable is verified by Task 5's service test compiling against these exact types. Per the "Task Right-Sizing" rule, this is folded as a prerequisite step rather than a separately-tested task.

- [ ] **Step 1: `BookingType`**

```java
package com.enunas.backend.settlement.accounting;

/** EÜR booking positions derivable from a settlement (spec §3). More may be added later — these
 *  are the minimum the spec requires: platform commission income, and Mollie's fee expense. */
public enum BookingType {
    INCOME_COMMISSION,
    EXPENSE_MOLLIE_FEE
}
```

- [ ] **Step 2: `ReconciliationStatus`**

```java
package com.enunas.backend.settlement.accounting;

public enum ReconciliationStatus {
    RECONCILED,
    UNRECONCILED
}
```

- [ ] **Step 3: `AccountingBookingLineDto`**

```java
package com.enunas.backend.settlement.accounting.dto;

import com.enunas.backend.settlement.accounting.BookingType;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One EÜR-relevant booking line (spec §6), fully pre-computed — no calculation performed by the
 *  consumer (Norman or otherwise) is required. */
@Getter
@Builder
public class AccountingBookingLineDto {
    private final String settlementId;
    private final LocalDate bookingDate;
    private final BookingType bookingType;
    private final BigDecimal amount;
    private final String currency;
    /** Percentage, e.g. 19 for 19% — not a fraction. */
    private final BigDecimal vatRate;
    private final String description;
    private final String externalReference;
}
```

- [ ] **Step 4: `BrandAccountingBreakdownDto`**

```java
package com.enunas.backend.settlement.accounting.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/** One brand's slice of the platform-level settlement (spec §7's optional brands[] breakdown).
 *  All figures net-to-brand (LedgerEntry.brandPayout), consistent with the platform totals. */
@Getter
@Builder
public class BrandAccountingBreakdownDto {
    private final Long brandId;
    private final String brandName;
    private final BigDecimal productAmount;
    private final BigDecimal shippingAmount;
    private final BigDecimal commissionNet;
    private final BigDecimal commissionVat;
    private final BigDecimal commissionGross;
    private final BigDecimal refundAmount;
    private final BigDecimal payoutAmount;
}
```

- [ ] **Step 5: `SettlementAccountingReportDto`**

```java
package com.enunas.backend.settlement.accounting.dto;

import com.enunas.backend.settlement.accounting.ReconciliationStatus;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Getter
@Builder
public class SettlementAccountingReportDto {

    // ===== Identity =====
    private final String settlementId;      // "SET-YYYY-MM"
    private final String period;            // "YYYY-MM"
    private final LocalDate periodStart;
    private final LocalDate periodEnd;
    private final String currency;
    private final LocalDate settlementDate; // report-generation date (today, Berlin)

    // ===== Ebene A: full settlement proof =====
    private final String payoutReference;               // admin-entered, nullable
    private final BigDecimal totalCustomerPayments;
    private final BigDecimal enunasCommissionNet;
    private final BigDecimal enunasVatRate;              // percentage, e.g. 19
    private final BigDecimal enunasVatAmount;
    private final BigDecimal enunasCommissionGross;
    private final boolean isCreditNote;
    private final BigDecimal brandProductAmount;
    private final BigDecimal brandShippingAmount;
    private final BigDecimal brandPayoutAmount;
    private final BigDecimal brandTotalAmount;
    private final BigDecimal mollieFees;                 // admin-entered, nullable
    private final Boolean mollieFeesIncludedInActualPayout; // admin-entered, nullable
    private final BigDecimal refundAmount;
    private final BigDecimal actualPayoutAmount;         // best-effort from PAID Payouts, nullable

    // ===== Reconciliation =====
    private final BigDecimal reconciliationDifference;   // totalCustomerPayments - (commissionGross + brandPayoutAmount)
    private final BigDecimal payoutDifference;            // actualPayoutAmount - brandPayoutAmount, nullable
    private final ReconciliationStatus reconciliationStatus;
    private final String reconciliationNote;

    // ===== Ebene B: EÜR booking lines =====
    private final List<AccountingBookingLineDto> bookingLines;

    // ===== Brand breakdown =====
    private final List<BrandAccountingBreakdownDto> brands;
}
```

- [ ] **Step 6: `UpsertAccountingInputDto`**

```java
package com.enunas.backend.settlement.accounting.dto;

import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
public class UpsertAccountingInputDto {
    private BigDecimal mollieFees;
    private Boolean mollieFeesIncludedInActualPayout;
    private String payoutReference;
    private LocalDate mollieSettlementDate;
    private String notes;
}
```

- [ ] **Step 7: Compile check**

Run: `cd backend && ./mvnw compile -q`
Expected: BUILD SUCCESS (new files compile; nothing references them yet).

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/settlement/accounting/
git commit -m "feat(accounting): add settlement accounting report DTOs and enums"
```

---

### Task 5: `SettlementAccountingReportService`

**Files:**
- Create: `backend/src/main/java/com/enunas/backend/settlement/accounting/SettlementAccountingReportService.java`
- Test: `backend/src/test/java/com/enunas/backend/settlement/accounting/SettlementAccountingReportServiceTest.java`

**Interfaces:**
- Consumes: `LedgerRepository.aggregateAccountingByBrandForPeriod` (Task 2), `PayoutRepository.sumPaidAmountInRange`/`countPaidPayoutsInRange` (Task 3), `SettlementAccountingInputRepository.findByPeriod` (Task 1), `BrandPartnerRepository.findById(Long): Optional<BrandPartner>` (existing), all Task 4 DTOs.
- Produces: `SettlementAccountingReportService.generateReport(String settlementId): SettlementAccountingReportDto`, `SettlementAccountingReportService.upsertInput(String period, UpsertAccountingInputDto dto, String adminEmail): void`.

- [ ] **Step 1: Write the failing test**

```java
package com.enunas.backend.settlement.accounting;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.BrandPartnerRepository;
import com.enunas.backend.exception.PeriodNotClosedException;
import com.enunas.backend.ledger.LedgerRepository;
import com.enunas.backend.payout.PayoutRepository;
import com.enunas.backend.settlement.accounting.dto.SettlementAccountingReportDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SettlementAccountingReportServiceTest {

    @Mock private LedgerRepository ledgerRepository;
    @Mock private PayoutRepository payoutRepository;
    @Mock private BrandPartnerRepository brandPartnerRepository;
    @Mock private SettlementAccountingInputRepository accountingInputRepository;

    private SettlementAccountingReportService service;

    @BeforeEach
    void setUp() {
        service = new SettlementAccountingReportService(
                ledgerRepository, payoutRepository, brandPartnerRepository, accountingInputRepository);
        ReflectionTestUtils.setField(service, "vatServiceRate", new BigDecimal("0.19"));
    }

    private LedgerRepository.AccountingPeriodAggregate agg(Long brandId, String commissionNet, String commissionVat,
                                                            String productNet, String shippingNet, String refund,
                                                            String total, long orderCount, long refundCount) {
        LedgerRepository.AccountingPeriodAggregate a = mock(LedgerRepository.AccountingPeriodAggregate.class);
        when(a.getBrandId()).thenReturn(brandId);
        when(a.getCommissionNet()).thenReturn(new BigDecimal(commissionNet));
        when(a.getCommissionVat()).thenReturn(new BigDecimal(commissionVat));
        when(a.getProductRevenueNet()).thenReturn(new BigDecimal(productNet));
        when(a.getShippingRevenueNet()).thenReturn(new BigDecimal(shippingNet));
        when(a.getRefundAmount()).thenReturn(new BigDecimal(refund));
        when(a.getTotalAmount()).thenReturn(new BigDecimal(total));
        when(a.getOrderCount()).thenReturn(orderCount);
        when(a.getRefundCount()).thenReturn(refundCount);
        return a;
    }

    @Test
    void closedPeriodGuard_rejectsCurrentMonth() {
        YearMonth current = YearMonth.now(ZoneId.of("Europe/Berlin"));
        assertThatThrownBy(() -> service.generateReport("SET-" + current))
                .isInstanceOf(PeriodNotClosedException.class);
    }

    @Test
    void malformedSettlementId_rejected() {
        assertThatThrownBy(() -> service.generateReport("2026-08"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noActualPayout_forcesUnreconciled_evenWhenLedgerInvariantHolds() {
        when(ledgerRepository.aggregateAccountingByBrandForPeriod(any(), any())).thenReturn(List.of(
                agg(1L, "18.00", "3.42", "97.58", "10.00", "0.00", "129.00", 1, 0)));
        when(brandPartnerRepository.findById(1L)).thenReturn(Optional.of(
                BrandPartner.builder().brandName("BrandA").build()));
        when(payoutRepository.sumPaidAmountInRange(any(), any())).thenReturn(BigDecimal.ZERO);
        when(payoutRepository.countPaidPayoutsInRange(any(), any())).thenReturn(0L);
        when(accountingInputRepository.findByPeriod("2026-05")).thenReturn(Optional.empty());

        SettlementAccountingReportDto report = service.generateReport("SET-2026-05");

        assertThat(report.getEnunasCommissionGross()).isEqualByComparingTo("21.42");
        assertThat(report.getBrandPayoutAmount()).isEqualByComparingTo("107.58");
        assertThat(report.getTotalCustomerPayments()).isEqualByComparingTo("129.00");
        assertThat(report.getReconciliationDifference()).isEqualByComparingTo("0.00"); // ledger-internal: balanced
        assertThat(report.getActualPayoutAmount()).isNull();                          // no PAID payout found
        assertThat(report.getPayoutDifference()).isNull();
        assertThat(report.getReconciliationStatus()).isEqualTo(ReconciliationStatus.UNRECONCILED);
        assertThat(report.getBookingLines()).hasSize(1); // INCOME_COMMISSION only, no mollieFees entered
        assertThat(report.getBookingLines().get(0).getBookingType()).isEqualTo(BookingType.INCOME_COMMISSION);
        assertThat(report.getBookingLines().get(0).getAmount()).isEqualByComparingTo("21.42");
        assertThat(report.getBookingLines().get(0).getVatRate()).isEqualByComparingTo("19");
    }

    @Test
    void matchingActualPayout_andNoDrift_reconciles() {
        when(ledgerRepository.aggregateAccountingByBrandForPeriod(any(), any())).thenReturn(List.of(
                agg(1L, "18.00", "3.42", "97.58", "0.00", "0.00", "119.00", 1, 0)));
        when(brandPartnerRepository.findById(1L)).thenReturn(Optional.of(
                BrandPartner.builder().brandName("BrandA").build()));
        when(payoutRepository.sumPaidAmountInRange(any(), any())).thenReturn(new BigDecimal("97.58"));
        when(payoutRepository.countPaidPayoutsInRange(any(), any())).thenReturn(1L);
        when(accountingInputRepository.findByPeriod("2026-05")).thenReturn(Optional.empty());

        SettlementAccountingReportDto report = service.generateReport("SET-2026-05");

        assertThat(report.getActualPayoutAmount()).isEqualByComparingTo("97.58");
        assertThat(report.getPayoutDifference()).isEqualByComparingTo("0.00");
        assertThat(report.getReconciliationStatus()).isEqualTo(ReconciliationStatus.RECONCILED);
    }

    @Test
    void mismatchedActualPayout_forcesUnreconciled() {
        when(ledgerRepository.aggregateAccountingByBrandForPeriod(any(), any())).thenReturn(List.of(
                agg(1L, "18.00", "3.42", "97.58", "0.00", "0.00", "119.00", 1, 0)));
        when(brandPartnerRepository.findById(1L)).thenReturn(Optional.of(
                BrandPartner.builder().brandName("BrandA").build()));
        when(payoutRepository.sumPaidAmountInRange(any(), any())).thenReturn(BigDecimal.ZERO);
        when(payoutRepository.countPaidPayoutsInRange(any(), any())).thenReturn(1L); // paid, but zero — e.g. fully clawed back
        when(accountingInputRepository.findByPeriod("2026-05")).thenReturn(Optional.empty());

        SettlementAccountingReportDto report = service.generateReport("SET-2026-05");

        assertThat(report.getPayoutDifference()).isEqualByComparingTo("-97.58");
        assertThat(report.getReconciliationStatus()).isEqualTo(ReconciliationStatus.UNRECONCILED);
    }

    @Test
    void mollieFeesEntered_addsExpenseLine_withoutTouchingCommission() {
        when(ledgerRepository.aggregateAccountingByBrandForPeriod(any(), any())).thenReturn(List.of(
                agg(1L, "18.00", "3.42", "97.58", "0.00", "0.00", "119.00", 1, 0)));
        when(brandPartnerRepository.findById(1L)).thenReturn(Optional.of(
                BrandPartner.builder().brandName("BrandA").build()));
        when(payoutRepository.sumPaidAmountInRange(any(), any())).thenReturn(BigDecimal.ZERO);
        when(payoutRepository.countPaidPayoutsInRange(any(), any())).thenReturn(0L);
        when(accountingInputRepository.findByPeriod("2026-05")).thenReturn(Optional.of(
                SettlementAccountingInput.builder()
                        .period("2026-05").mollieFees(new BigDecimal("3.57"))
                        .mollieFeesIncludedInActualPayout(false).payoutReference("stl_test123")
                        .enteredByAdminEmail("admin@it.local")
                        .enteredAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                        .build()));

        SettlementAccountingReportDto report = service.generateReport("SET-2026-05");

        assertThat(report.getMollieFees()).isEqualByComparingTo("3.57");
        assertThat(report.getMollieFeesIncludedInActualPayout()).isFalse();
        assertThat(report.getPayoutReference()).isEqualTo("stl_test123");
        assertThat(report.getEnunasCommissionGross()).isEqualByComparingTo("21.42"); // unaffected by fee
        assertThat(report.getBookingLines()).hasSize(2);
        assertThat(report.getBookingLines().stream().anyMatch(l ->
                l.getBookingType() == BookingType.EXPENSE_MOLLIE_FEE
                        && l.getAmount().compareTo(new BigDecimal("3.57")) == 0
                        && l.getVatRate().compareTo(BigDecimal.ZERO) == 0)).isTrue();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw test -Dtest=SettlementAccountingReportServiceTest -q`
Expected: FAIL — compile error, `SettlementAccountingReportService` does not exist.

- [ ] **Step 3: Write the service**

```java
package com.enunas.backend.settlement.accounting;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.BrandPartnerRepository;
import com.enunas.backend.exception.PeriodNotClosedException;
import com.enunas.backend.ledger.LedgerRepository;
import com.enunas.backend.payout.PayoutRepository;
import com.enunas.backend.settlement.accounting.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Platform-level, period-scoped accounting report. Every figure is a live SUM over
 * {@link com.enunas.backend.ledger.LedgerEntry}/{@link com.enunas.backend.payout.Payout} — no
 * second money-calculation path, so this can never drift from the ledger (see
 * SettlementAccountingReportServiceTest for the reconciliation-status contract). The only
 * persisted state this service touches is {@link SettlementAccountingInput} — the two facts
 * (Mollie fees, confirmed payout reference) the system has no other source for.
 */
@Service
@RequiredArgsConstructor
public class SettlementAccountingReportService {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final String SETTLEMENT_ID_PREFIX = "SET-";

    private final LedgerRepository ledgerRepository;
    private final PayoutRepository payoutRepository;
    private final BrandPartnerRepository brandPartnerRepository;
    private final SettlementAccountingInputRepository accountingInputRepository;

    @Value("${enunas.vat.service-rate:0.19}")
    private BigDecimal vatServiceRate;

    @Transactional(readOnly = true)
    public SettlementAccountingReportDto generateReport(String settlementId) {
        YearMonth ym = parseSettlementId(settlementId);
        String period = ym.toString();

        // Same closed-period rule as SettlementService.settle(): only fully-closed months.
        LocalDate nextPeriodStart = ym.plusMonths(1).atDay(1);
        if (LocalDate.now(BERLIN).isBefore(nextPeriodStart)) {
            throw new PeriodNotClosedException("Nur abgeschlossene Monate können abgerechnet werden.");
        }

        LocalDateTime startUtc = ym.atDay(1).atStartOfDay(BERLIN).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime endUtc = ym.plusMonths(1).atDay(1).atStartOfDay(BERLIN).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();

        List<LedgerRepository.AccountingPeriodAggregate> rows =
                ledgerRepository.aggregateAccountingByBrandForPeriod(startUtc, endUtc);

        List<BrandAccountingBreakdownDto> brands = new ArrayList<>();
        BigDecimal totalCustomerPayments = BigDecimal.ZERO;
        BigDecimal commissionNet = BigDecimal.ZERO;
        BigDecimal commissionVat = BigDecimal.ZERO;
        BigDecimal productAmount = BigDecimal.ZERO;
        BigDecimal shippingAmount = BigDecimal.ZERO;
        BigDecimal refundAmount = BigDecimal.ZERO;

        for (LedgerRepository.AccountingPeriodAggregate row : rows) {
            BigDecimal rowCommissionGross = row.getCommissionNet().add(row.getCommissionVat());
            BigDecimal rowPayout = row.getProductRevenueNet().add(row.getShippingRevenueNet());
            BrandPartner brand = brandPartnerRepository.findById(row.getBrandId()).orElse(null);

            brands.add(BrandAccountingBreakdownDto.builder()
                    .brandId(row.getBrandId())
                    .brandName(brand != null ? brand.getBrandName() : null)
                    .productAmount(row.getProductRevenueNet())
                    .shippingAmount(row.getShippingRevenueNet())
                    .commissionNet(row.getCommissionNet())
                    .commissionVat(row.getCommissionVat())
                    .commissionGross(rowCommissionGross)
                    .refundAmount(row.getRefundAmount())
                    .payoutAmount(rowPayout)
                    .build());

            totalCustomerPayments = totalCustomerPayments.add(row.getTotalAmount());
            commissionNet = commissionNet.add(row.getCommissionNet());
            commissionVat = commissionVat.add(row.getCommissionVat());
            productAmount = productAmount.add(row.getProductRevenueNet());
            shippingAmount = shippingAmount.add(row.getShippingRevenueNet());
            refundAmount = refundAmount.add(row.getRefundAmount());
        }

        BigDecimal commissionGross = commissionNet.add(commissionVat);
        BigDecimal payoutAmount = productAmount.add(shippingAmount);

        BigDecimal actualPayoutAmount = null;
        long paidCount = payoutRepository.countPaidPayoutsInRange(startUtc, endUtc);
        if (paidCount > 0) {
            actualPayoutAmount = payoutRepository.sumPaidAmountInRange(startUtc, endUtc);
        }

        BigDecimal reconciliationDifference = totalCustomerPayments.subtract(commissionGross).subtract(payoutAmount);
        BigDecimal payoutDifference = actualPayoutAmount != null ? actualPayoutAmount.subtract(payoutAmount) : null;

        ReconciliationStatus status;
        String note;
        if (reconciliationDifference.compareTo(BigDecimal.ZERO) != 0) {
            status = ReconciliationStatus.UNRECONCILED;
            note = "Ledger-internal invariant violated: totalCustomerPayments != commissionGross + brandPayoutAmount.";
        } else if (actualPayoutAmount == null) {
            status = ReconciliationStatus.UNRECONCILED;
            note = "No confirmed PAID payout found in this period yet.";
        } else if (payoutDifference.compareTo(BigDecimal.ZERO) != 0) {
            status = ReconciliationStatus.UNRECONCILED;
            note = "Actual payout does not match computed brand payout for this period.";
        } else {
            status = ReconciliationStatus.RECONCILED;
            note = null;
        }

        Optional<SettlementAccountingInput> input = accountingInputRepository.findByPeriod(period);
        BigDecimal mollieFees = input.map(SettlementAccountingInput::getMollieFees).orElse(null);
        Boolean mollieFeesIncluded = input.map(SettlementAccountingInput::getMollieFeesIncludedInActualPayout).orElse(null);
        String payoutReference = input.map(SettlementAccountingInput::getPayoutReference).orElse(null);

        List<AccountingBookingLineDto> bookingLines = new ArrayList<>();
        LocalDate today = LocalDate.now(BERLIN);
        BigDecimal vatRatePercent = vatServiceRate.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP);
        bookingLines.add(AccountingBookingLineDto.builder()
                .settlementId(settlementId).bookingDate(today).bookingType(BookingType.INCOME_COMMISSION)
                .amount(commissionGross).currency("EUR").vatRate(vatRatePercent)
                .description("Enunas-Provision Settlement " + period).externalReference(settlementId)
                .build());
        if (mollieFees != null) {
            bookingLines.add(AccountingBookingLineDto.builder()
                    .settlementId(settlementId).bookingDate(today).bookingType(BookingType.EXPENSE_MOLLIE_FEE)
                    .amount(mollieFees).currency("EUR").vatRate(BigDecimal.ZERO)
                    .description("Mollie-Gebühren Settlement " + period).externalReference(settlementId)
                    .build());
        }

        return SettlementAccountingReportDto.builder()
                .settlementId(settlementId).period(period)
                .periodStart(ym.atDay(1)).periodEnd(ym.atEndOfMonth())
                .currency("EUR").settlementDate(today)
                .payoutReference(payoutReference)
                .totalCustomerPayments(totalCustomerPayments)
                .enunasCommissionNet(commissionNet).enunasVatRate(vatRatePercent).enunasVatAmount(commissionVat)
                .enunasCommissionGross(commissionGross).isCreditNote(commissionNet.signum() < 0)
                .brandProductAmount(productAmount).brandShippingAmount(shippingAmount)
                .brandPayoutAmount(payoutAmount).brandTotalAmount(totalCustomerPayments)
                .mollieFees(mollieFees).mollieFeesIncludedInActualPayout(mollieFeesIncluded)
                .refundAmount(refundAmount).actualPayoutAmount(actualPayoutAmount)
                .reconciliationDifference(reconciliationDifference).payoutDifference(payoutDifference)
                .reconciliationStatus(status).reconciliationNote(note)
                .bookingLines(bookingLines).brands(brands)
                .build();
    }

    @Transactional
    public void upsertInput(String period, UpsertAccountingInputDto dto, String adminEmail) {
        parsePeriod(period); // validates format, throws IllegalArgumentException otherwise
        SettlementAccountingInput input = accountingInputRepository.findByPeriod(period)
                .orElseGet(() -> SettlementAccountingInput.builder()
                        .period(period).enteredByAdminEmail(adminEmail).enteredAt(LocalDateTime.now())
                        .build());
        if (dto.getMollieFees() != null) input.setMollieFees(dto.getMollieFees());
        if (dto.getMollieFeesIncludedInActualPayout() != null) input.setMollieFeesIncludedInActualPayout(dto.getMollieFeesIncludedInActualPayout());
        if (dto.getPayoutReference() != null) input.setPayoutReference(dto.getPayoutReference());
        if (dto.getMollieSettlementDate() != null) input.setMollieSettlementDate(dto.getMollieSettlementDate());
        if (dto.getNotes() != null) input.setNotes(dto.getNotes());
        input.setUpdatedAt(LocalDateTime.now());
        accountingInputRepository.save(input);
    }

    private YearMonth parseSettlementId(String settlementId) {
        if (settlementId == null || !settlementId.startsWith(SETTLEMENT_ID_PREFIX)) {
            throw new IllegalArgumentException(
                    "Invalid settlementId, expected 'SET-YYYY-MM': " + settlementId);
        }
        return parsePeriod(settlementId.substring(SETTLEMENT_ID_PREFIX.length()));
    }

    private YearMonth parsePeriod(String period) {
        try {
            return YearMonth.parse(period);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid period format, expected YYYY-MM: " + period);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=SettlementAccountingReportServiceTest -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/settlement/accounting/SettlementAccountingReportService.java \
        backend/src/test/java/com/enunas/backend/settlement/accounting/SettlementAccountingReportServiceTest.java
git commit -m "feat(accounting): add SettlementAccountingReportService"
```

---

### Task 6: `SettlementAccountingReportController`

**Files:**
- Create: `backend/src/main/java/com/enunas/backend/settlement/accounting/SettlementAccountingReportController.java`

**Interfaces:**
- Consumes: `SettlementAccountingReportService.generateReport`/`upsertInput` (Task 5).
- Produces: `GET /admin/settlements/{settlementId}/accounting-report`, `GET /admin/settlements/{settlementId}/accounting-report/export?format=csv|json`, `PUT /admin/settlements/{period}/accounting-input`.

Verified in Task 10's integration tests (a standalone controller test would only re-assert what those already cover via real HTTP).

- [ ] **Step 1: Write the controller**

```java
package com.enunas.backend.settlement.accounting;

import com.enunas.backend.settlement.accounting.dto.SettlementAccountingReportDto;
import com.enunas.backend.settlement.accounting.dto.UpsertAccountingInputDto;
import com.enunas.backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Admin-only platform-level accounting export. Aggregates already-authoritative ledger/payout data
 * — introduces no new business logic for payments, commission, discounts, or refunds (spec item 16).
 *
 *  - GET /admin/settlements/{settlementId}/accounting-report                  → full JSON (spec §2/§13)
 *  - GET /admin/settlements/{settlementId}/accounting-report/export?format=   → flat settlement-level row (spec §4)
 *  - PUT /admin/settlements/{period}/accounting-input                        → admin-entered Mollie fees / payout ref
 */
@RestController
@RequestMapping("/admin/settlements")
@RequiredArgsConstructor
public class SettlementAccountingReportController {

    private final SettlementAccountingReportService reportService;

    @GetMapping("/{settlementId}/accounting-report")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SettlementAccountingReportDto> getReport(@PathVariable String settlementId) {
        return ResponseEntity.ok(reportService.generateReport(settlementId));
    }

    @GetMapping("/{settlementId}/accounting-report/export")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> exportReport(
            @PathVariable String settlementId,
            @RequestParam(defaultValue = "json") String format) {
        SettlementAccountingReportDto report = reportService.generateReport(settlementId);
        if ("csv".equalsIgnoreCase(format)) {
            String csv = toCsv(report);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"accounting-report-" + settlementId + ".csv\"")
                    .body(csv);
        }
        return ResponseEntity.ok(report);
    }

    @PutMapping("/{period}/accounting-input")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> upsertInput(
            @PathVariable String period,
            @RequestBody UpsertAccountingInputDto dto,
            @AuthenticationPrincipal User admin) {
        reportService.upsertInput(period, dto, admin.getEmail());
        return ResponseEntity.noContent().build();
    }

    /** Flat, single-row CSV per spec §4's exact column order. RFC-4180 escaping. */
    private static String toCsv(SettlementAccountingReportDto r) {
        String[] header = {
                "settlement_id", "period_start", "period_end", "currency", "settlement_date",
                "payout_reference", "mollie_gross_inflows", "enunas_commission_net", "enunas_vat_rate",
                "enunas_vat_amount", "enunas_commission_gross", "brand_product_amount",
                "brand_shipping_amount", "brand_payout_amount", "mollie_fees", "refunds",
                "actual_payout_amount", "payout_account", "booking_date"
        };
        String[] row = {
                r.getSettlementId(), s(r.getPeriodStart()), s(r.getPeriodEnd()), r.getCurrency(), s(r.getSettlementDate()),
                s(r.getPayoutReference()), s(r.getTotalCustomerPayments()), s(r.getEnunasCommissionNet()), s(r.getEnunasVatRate()),
                s(r.getEnunasVatAmount()), s(r.getEnunasCommissionGross()), s(r.getBrandProductAmount()),
                s(r.getBrandShippingAmount()), s(r.getBrandPayoutAmount()), s(r.getMollieFees()), s(r.getRefundAmount()),
                s(r.getActualPayoutAmount()), "MOLLIE", s(r.getSettlementDate())
        };
        StringBuilder sb = new StringBuilder();
        sb.append(join(header)).append("\r\n").append(join(row)).append("\r\n");
        return sb.toString();
    }

    private static String join(String[] fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(esc(fields[i]));
        }
        return sb.toString();
    }

    private static String s(Object v) {
        return v == null ? "" : v.toString();
    }

    /** RFC-4180 escaping: quote fields containing a comma, quote, CR or LF; double inner quotes. */
    private static String esc(String v) {
        String s = v == null ? "" : v;
        if (s.contains("\"") || s.contains(",") || s.contains("\n") || s.contains("\r")) {
            s = "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
```

- [ ] **Step 2: Compile check**

Run: `cd backend && ./mvnw compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/settlement/accounting/SettlementAccountingReportController.java
git commit -m "feat(accounting): add SettlementAccountingReportController"
```

---

### Task 7: Test harness — TRUNCATE list + payout/pending-release helpers

**Files:**
- Modify: `backend/src/test/java/com/enunas/backend/discount/integration/AbstractDiscountIntegrationTest.java`

**Interfaces:**
- Produces: `releaseAllPending(long orderId)`, `generateApproveAndPayPayout(String adminToken, long brandId, String externalReference): long` (returns the payoutId).

- [ ] **Step 1: Add `payouts`, `brand_payout_profiles`, and `settlement_accounting_inputs` to the TRUNCATE list**

`settlement_accounting_inputs` has no FK to a truncated table (it's period-scoped, not brand-scoped), so `CASCADE` will NOT pick it up automatically — it must be listed explicitly. `payouts`/`brand_payout_profiles` DO have an FK to `brand_partners` and are already covered by `CASCADE`, but list them explicitly too for clarity/robustness against a future FK change.

In `cleanDatabase()`, change:

```java
        jdbc.execute("TRUNCATE TABLE settlement_runs, ledger_entries, payments, order_items, orders, listings, " +
                "product_variants, product_colors, products, discount_codes, brand_economics, " +
                "brand_partners, user_addresses, oauth_accounts, customers, users RESTART IDENTITY CASCADE");
```

to:

```java
        jdbc.execute("TRUNCATE TABLE settlement_accounting_inputs, payouts, brand_payout_profiles, " +
                "settlement_runs, ledger_entries, payments, order_items, orders, listings, " +
                "product_variants, product_colors, products, discount_codes, brand_economics, " +
                "brand_partners, user_addresses, oauth_accounts, customers, users RESTART IDENTITY CASCADE");
```

- [ ] **Step 2: Add `Payout`/`BrandPayoutProfile` autowires and the two helpers**

Add near the other `@Autowired` repository fields:

```java
    @Autowired protected com.enunas.backend.payout.PayoutRepository payoutRepository;
    @Autowired protected com.enunas.backend.brandpartner.brandpayoutprofile.BrandPayoutProfileRepository brandPayoutProfileRepository;
```

Add near `confirmPaid`:

```java
    /** Backdates every PENDING_RELEASE entry for an order into the past, then runs the release job
     *  synchronously — used instead of waiting out the real hold-days window in tests. */
    protected void releaseAllPending(long orderId) {
        jdbc.update("UPDATE ledger_entries SET payout_eligible_at = ? WHERE order_id = ?",
                java.time.LocalDateTime.now().minusDays(1), orderId);
        ledgerService.releasePendingBalances();
    }

    /** Drives a brand's AVAILABLE balance through generate → approve → markAsPaid, exactly the real
     *  admin flow (POST /admin/payouts/generate, /approve, /paid). Requires a BrandPayoutProfile to
     *  already exist for the brand. Returns the created payout's id. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    protected long generateApproveAndPayPayout(String adminToken, long brandId, String externalReference) {
        rest.exchange("/admin/payouts/generate", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(auth(adminToken)), java.util.List.class);

        java.util.Map<String, Object> row = jdbc.queryForList(
                "SELECT id FROM payouts WHERE brand_partner_id = ? ORDER BY id DESC LIMIT 1", brandId)
                .get(0);
        long payoutId = ((Number) row.get("id")).longValue();

        rest.exchange("/admin/payouts/" + payoutId + "/approve", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(auth(adminToken)), Map.class);
        rest.exchange("/admin/payouts/" + payoutId + "/paid", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(Map.of("externalReference", externalReference), auth(adminToken)), Map.class);
        return payoutId;
    }
```

- [ ] **Step 2: Run the existing discount/ledger/settlement integration suite to confirm no regression**

Run: `cd backend && ./mvnw test -Dtest=com.enunas.backend.discount.integration.*,com.enunas.backend.order.integration.* -q`
Expected: PASS (base class change is additive; existing tests unaffected)

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/com/enunas/backend/discount/integration/AbstractDiscountIntegrationTest.java
git commit -m "test: add payout/release helpers and new tables to integration test harness"
```

---

### Task 8: Integration tests — Simple Settlement + Multi-Brand

**Files:**
- Create: `backend/src/test/java/com/enunas/backend/settlement/accounting/SettlementAccountingReportIntegrationTest.java`

**Interfaces:**
- Consumes: `AbstractDiscountIntegrationTest` (base class + Task 7 helpers), `SettlementAccountingReportController` (Task 6).

- [ ] **Step 1: Write the test class with the first two scenarios**

```java
package com.enunas.backend.settlement.accounting;

import com.enunas.backend.brandpartner.brandshippingprofile.BrandShippingProfile;
import com.enunas.backend.brandpartner.brandshippingprofile.BrandShippingProfileRepository;
import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings({"rawtypes", "unchecked"})
class SettlementAccountingReportIntegrationTest extends AbstractDiscountIntegrationTest {

    @Autowired private BrandShippingProfileRepository brandShippingProfileRepository;

    // Always in the past, so it's a closed period regardless of when the suite runs — mirrors
    // SettlementIntegrationTest's approach to avoid month-rollover flakiness.
    private static String closedPeriod() {
        return YearMonth.now(ZoneId.of("Europe/Berlin")).minusMonths(1).toString();
    }

    @Test
    void simpleSettlement_100NetProduct_10Shipping_18PercentCommission() {
        seedAdmin();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        brandShippingProfileRepository.save(BrandShippingProfile.builder()
                .brandPartner(a.brand()).shippingCost(new BigDecimal("10.00")).currency("EUR").build());
        long listing = seedListing(a.brand(), a.user(), "119.00", 10); // gross 119 -> net 100
        String admin = login("admin@it.local", "Admin123!");
        seedCustomer();
        String cust = login("customer@it.local", "Customer123!");

        long oid = orderId(postOrder(cust, null, List.of(item(listing, 1))));
        confirmPaid(oid);
        backdateOrderIntoPeriod(oid);

        Map<String, Object> report = getReport(admin, closedPeriod());

        assertThat(bd(report.get("enunasCommissionNet"))).isEqualByComparingTo("18.00");
        assertThat(bd(report.get("enunasVatAmount"))).isEqualByComparingTo("3.42");
        assertThat(bd(report.get("enunasCommissionGross"))).isEqualByComparingTo("21.42");
        assertThat(bd(report.get("brandProductAmount"))).isEqualByComparingTo("97.58");
        assertThat(bd(report.get("brandShippingAmount"))).isEqualByComparingTo("10.00");
        assertThat(bd(report.get("brandPayoutAmount"))).isEqualByComparingTo("107.58");
        assertThat(bd(report.get("totalCustomerPayments"))).isEqualByComparingTo("129.00");
        assertThat(bd(report.get("refundAmount"))).isEqualByComparingTo("0.00");
        assertThat(bd(report.get("reconciliationDifference"))).isEqualByComparingTo("0.00");
        // No confirmed payout yet in this period -> honestly UNRECONCILED, not silently green.
        assertThat(report.get("actualPayoutAmount")).isNull();
        assertThat(report.get("reconciliationStatus")).isEqualTo("UNRECONCILED");

        List<Map<String, Object>> bookingLines = (List<Map<String, Object>>) report.get("bookingLines");
        assertThat(bookingLines).hasSize(1);
        assertThat(bookingLines.get(0).get("bookingType")).isEqualTo("INCOME_COMMISSION");
        assertThat(bd(bookingLines.get(0).get("amount"))).isEqualByComparingTo("21.42");
    }

    @Test
    void multiBrandSettlement_correctPerBrandSplit_andPlatformTotals() {
        seedAdmin();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        BrandFixture b = seedBrand("BrandB", "brand-b", "0.20");
        brandShippingProfileRepository.save(BrandShippingProfile.builder()
                .brandPartner(a.brand()).shippingCost(new BigDecimal("5.00")).currency("EUR").build());
        brandShippingProfileRepository.save(BrandShippingProfile.builder()
                .brandPartner(b.brand()).shippingCost(new BigDecimal("3.00")).currency("EUR").build());
        long listingA = seedListing(a.brand(), a.user(), "119.00", 10);  // net 100
        long listingB = seedListing(b.brand(), b.user(), "59.50", 10);  // net 50
        String admin = login("admin@it.local", "Admin123!");
        seedCustomer();
        String cust = login("customer@it.local", "Customer123!");

        long oid = orderId(postOrder(cust, null, List.of(item(listingA, 1), item(listingB, 1))));
        confirmPaid(oid);
        backdateOrderIntoPeriod(oid);

        Map<String, Object> report = getReport(admin, closedPeriod());

        assertThat(bd(report.get("enunasCommissionNet"))).isEqualByComparingTo("28.00");   // 18.00 + 10.00
        assertThat(bd(report.get("enunasVatAmount"))).isEqualByComparingTo("5.32");         // 3.42 + 1.90
        assertThat(bd(report.get("enunasCommissionGross"))).isEqualByComparingTo("33.32");
        assertThat(bd(report.get("brandProductAmount"))).isEqualByComparingTo("145.18");    // 97.58 + 47.60
        assertThat(bd(report.get("brandShippingAmount"))).isEqualByComparingTo("8.00");     // 5.00 + 3.00
        assertThat(bd(report.get("brandPayoutAmount"))).isEqualByComparingTo("153.18");
        assertThat(bd(report.get("totalCustomerPayments"))).isEqualByComparingTo("186.50"); // 124.00 + 62.50
        assertThat(bd(report.get("reconciliationDifference"))).isEqualByComparingTo("0.00");

        List<Map<String, Object>> brands = (List<Map<String, Object>>) report.get("brands");
        assertThat(brands).hasSize(2);
        Map<String, Object> brandA = brands.stream().filter(r -> r.get("brandId").equals((int) a.brand().getId().intValue())
                        || ((Number) r.get("brandId")).longValue() == a.brand().getId()).findFirst().orElseThrow();
        assertThat(bd(brandA.get("productAmount"))).isEqualByComparingTo("97.58");
        assertThat(bd(brandA.get("shippingAmount"))).isEqualByComparingTo("5.00");
        assertThat(bd(brandA.get("commissionGross"))).isEqualByComparingTo("21.42");
        Map<String, Object> brandB = brands.stream()
                .filter(r -> ((Number) r.get("brandId")).longValue() == b.brand().getId()).findFirst().orElseThrow();
        assertThat(bd(brandB.get("productAmount"))).isEqualByComparingTo("47.60");
        assertThat(bd(brandB.get("shippingAmount"))).isEqualByComparingTo("3.00");
        assertThat(bd(brandB.get("commissionGross"))).isEqualByComparingTo("11.90");
    }

    // ===== shared helpers for this class =====

    /** Orders created "now" land in the current (open) month; back-date created_at on every ledger
     *  row for the order into the last CLOSED month so the report's closed-period guard accepts it. */
    protected void backdateOrderIntoPeriod(long orderId) {
        LocalDateTime target = YearMonth.parse(closedPeriod()).atDay(10).atTime(12, 0);
        jdbc.update("UPDATE ledger_entries SET created_at = ? WHERE order_id = ?", target, orderId);
    }

    protected Map<String, Object> getReport(String adminToken, String period) {
        ResponseEntity<Map> resp = rest.exchange(
                "/admin/settlements/SET-" + period + "/accounting-report",
                HttpMethod.GET, new HttpEntity<>(auth(adminToken)), Map.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).as("report: %s", resp.getBody()).isTrue();
        return resp.getBody();
    }

    protected static BigDecimal bd(Object v) {
        return new BigDecimal(String.valueOf(v));
    }
}
```

- [ ] **Step 2: Run test to verify it fails, then implement/fix until it passes**

Run: `cd backend && ./mvnw test -Dtest=SettlementAccountingReportIntegrationTest -q`
Expected: first run FAILs only if Tasks 1–7 aren't wired correctly — since they're already implemented in prior tasks, this should PASS on the first run. If it fails, the failure is a real integration bug (e.g. a JPQL typo) — fix the implementation, not the test, unless the test's own arithmetic is wrong (re-check against the "Design Decisions" section's worked numbers first).
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/com/enunas/backend/settlement/accounting/SettlementAccountingReportIntegrationTest.java
git commit -m "test(accounting): simple settlement + multi-brand integration tests"
```

---

### Task 9: Integration tests — Admin Discount + Brand Discount

**Files:**
- Modify: `backend/src/test/java/com/enunas/backend/settlement/accounting/SettlementAccountingReportIntegrationTest.java`

- [ ] **Step 1: Add the two discount scenarios**

```java
    @Test
    void adminDiscount_reducesOnlyCommission_brandPayoutUnchanged() {
        seedAdmin();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        long listing = seedListing(a.brand(), a.user(), "119.00", 10); // net 100
        String admin = login("admin@it.local", "Admin123!");
        seedCustomer();
        String cust = login("customer@it.local", "Customer123!");

        createAdminDiscount(admin, Map.of("code", "ADM10", "percent", "0.1000"));
        long oid = orderId(postOrder(cust, "ADM10", List.of(item(listing, 1))));
        confirmPaid(oid);
        backdateOrderIntoPeriod(oid);

        Map<String, Object> report = getReport(admin, closedPeriod());

        // Brand payout is IDENTICAL to the no-discount case (97.58) — Enunas absorbs the whole 10%.
        assertThat(bd(report.get("brandPayoutAmount"))).isEqualByComparingTo("97.58");
        assertThat(bd(report.get("enunasCommissionNet"))).isEqualByComparingTo("8.00");   // 18.00 - 10.00
        assertThat(bd(report.get("enunasVatAmount"))).isEqualByComparingTo("1.52");
        assertThat(bd(report.get("enunasCommissionGross"))).isEqualByComparingTo("9.52");
        assertThat(bd(report.get("totalCustomerPayments"))).isEqualByComparingTo("107.10");
        assertThat(bd(report.get("reconciliationDifference"))).isEqualByComparingTo("0.00");
    }

    @Test
    void brandDiscount_splits5050_betweenBrandAndPlatform() {
        seedAdmin();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        long listing = seedListing(a.brand(), a.user(), "119.00", 10); // net 100
        String admin = login("admin@it.local", "Admin123!");
        String brandToken = login("brand-a@it.local", "Brand123!");
        seedCustomer();
        String cust = login("customer@it.local", "Customer123!");

        createBrandDiscount(brandToken, Map.of("code", "BRAND15", "percent", "0.1500"));
        long oid = orderId(postOrder(cust, "BRAND15", List.of(item(listing, 1))));
        confirmPaid(oid);
        backdateOrderIntoPeriod(oid);

        Map<String, Object> report = getReport(admin, closedPeriod());

        assertThat(bd(report.get("enunasCommissionNet"))).isEqualByComparingTo("10.50");  // 18.00 - 7.50
        assertThat(bd(report.get("enunasVatAmount"))).isEqualByComparingTo("2.00");
        assertThat(bd(report.get("enunasCommissionGross"))).isEqualByComparingTo("12.50");
        assertThat(bd(report.get("brandPayoutAmount"))).isEqualByComparingTo("88.65");
        assertThat(bd(report.get("totalCustomerPayments"))).isEqualByComparingTo("101.15");
        assertThat(bd(report.get("reconciliationDifference"))).isEqualByComparingTo("0.00");
    }
```

- [ ] **Step 2: Run test to verify it passes**

Run: `cd backend && ./mvnw test -Dtest=SettlementAccountingReportIntegrationTest -q`
Expected: PASS. If the numbers don't match, trace `OrderItem.applyMoneySnapshot` (see Design Decisions §4 in this plan) rather than adjusting the expected values blind — these were hand-derived from that exact formula.

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/java/com/enunas/backend/settlement/accounting/SettlementAccountingReportIntegrationTest.java
git commit -m "test(accounting): admin discount + brand discount integration tests"
```

---

### Task 10: Integration tests — Full Refund, Refund after Payout, Mollie Fees, Reconciliation Failure

**Files:**
- Modify: `backend/src/test/java/com/enunas/backend/settlement/accounting/SettlementAccountingReportIntegrationTest.java`

- [ ] **Step 1: Add the four remaining scenarios**

```java
    @Test
    void fullRefund_netsToZero_butRefundAmountIsVisible() {
        seedAdmin();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        brandShippingProfileRepository.save(BrandShippingProfile.builder()
                .brandPartner(a.brand()).shippingCost(new BigDecimal("4.99")).currency("EUR").build());
        long listing = seedListing(a.brand(), a.user(), "119.00", 10);
        String admin = login("admin@it.local", "Admin123!");
        seedCustomer();
        String cust = login("customer@it.local", "Customer123!");

        long oid = orderId(postOrder(cust, null, List.of(item(listing, 1))));
        confirmPaid(oid);
        ResponseEntity<Map> cancelled = rest.exchange("/admin/orders/" + oid + "/status?status=CANCELLED",
                HttpMethod.PATCH, new HttpEntity<>(null, auth(admin)), Map.class);
        assertThat(cancelled.getStatusCode().is2xxSuccessful()).as("cancel: %s", cancelled.getBody()).isTrue();
        backdateOrderIntoPeriod(oid);

        Map<String, Object> report = getReport(admin, closedPeriod());

        assertThat(bd(report.get("totalCustomerPayments"))).isEqualByComparingTo("0.00");
        assertThat(bd(report.get("enunasCommissionGross"))).isEqualByComparingTo("0.00");
        assertThat(bd(report.get("brandPayoutAmount"))).isEqualByComparingTo("0.00");
        assertThat(bd(report.get("refundAmount"))).isEqualByComparingTo("123.99"); // 119.00 + 4.99, reported positive
        assertThat(bd(report.get("reconciliationDifference"))).isEqualByComparingTo("0.00");
    }

    @Test
    void refundAfterBrandPayout_leavesHonestMismatch_notSilentlyReconciled() {
        seedAdmin();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        long listing = seedListing(a.brand(), a.user(), "119.00", 10);
        String admin = login("admin@it.local", "Admin123!");
        seedCustomer();
        String cust = login("customer@it.local", "Customer123!");
        brandPayoutProfileRepository.save(com.enunas.backend.brandpartner.brandpayoutprofile.BrandPayoutProfile.builder()
                .brandPartner(a.brand()).iban("DE89370400440532013000").bankAccountHolder("BrandA GmbH").build());

        long oid = orderId(postOrder(cust, null, List.of(item(listing, 1))));
        confirmPaid(oid);
        releaseAllPending(oid);
        long payoutId = generateApproveAndPayPayout(admin, a.brand().getId(), "QONTO-REF-1");
        assertThat(payoutId).isPositive();

        // Refund AFTER the money already left the bank — brand now owes it back (outstandingDebt).
        ResponseEntity<Map> cancelled = rest.exchange("/admin/orders/" + oid + "/status?status=CANCELLED",
                HttpMethod.PATCH, new HttpEntity<>(null, auth(admin)), Map.class);
        assertThat(cancelled.getStatusCode().is2xxSuccessful()).as("cancel: %s", cancelled.getBody()).isTrue();
        backdateOrderIntoPeriod(oid);
        jdbc.update("UPDATE payouts SET paid_at = (SELECT created_at FROM ledger_entries WHERE order_id = ? LIMIT 1) WHERE id = ?",
                oid, payoutId);

        assertThat(brandEconomicsRepository.findByBrandPartner_Id(a.brand().getId()).orElseThrow().getOutstandingDebt())
                .isEqualByComparingTo("97.58");

        Map<String, Object> report = getReport(admin, closedPeriod());

        assertThat(bd(report.get("brandPayoutAmount"))).isEqualByComparingTo("0.00");     // net of the refund
        assertThat(bd(report.get("actualPayoutAmount"))).isEqualByComparingTo("97.58");   // real money that left
        assertThat(bd(report.get("payoutDifference"))).isEqualByComparingTo("97.58");
        assertThat(report.get("reconciliationStatus")).isEqualTo("UNRECONCILED");
        assertThat(bd(report.get("reconciliationDifference"))).isEqualByComparingTo("0.00"); // ledger itself is still internally consistent
    }

    @Test
    void mollieFees_areExpenseLine_neverEnunasRevenue() {
        seedAdmin();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        long listing = seedListing(a.brand(), a.user(), "119.00", 10);
        String admin = login("admin@it.local", "Admin123!");
        seedCustomer();
        String cust = login("customer@it.local", "Customer123!");

        long oid = orderId(postOrder(cust, null, List.of(item(listing, 1))));
        confirmPaid(oid);
        backdateOrderIntoPeriod(oid);
        String period = closedPeriod();

        ResponseEntity<Void> put = rest.exchange("/admin/settlements/" + period + "/accounting-input",
                HttpMethod.PUT, new HttpEntity<>(Map.of(
                        "mollieFees", "3.57",
                        "mollieFeesIncludedInActualPayout", false,
                        "payoutReference", "stl_test123"), auth(admin)), Void.class);
        assertThat(put.getStatusCode().value()).isEqualTo(204);

        Map<String, Object> report = getReport(admin, period);

        assertThat(bd(report.get("mollieFees"))).isEqualByComparingTo("3.57");
        assertThat(report.get("mollieFeesIncludedInActualPayout")).isEqualTo(false);
        assertThat(report.get("payoutReference")).isEqualTo("stl_test123");
        // Enunas' own revenue is UNCHANGED by the fee — Mollie fees are Enunas' expense, not revenue.
        assertThat(bd(report.get("enunasCommissionNet"))).isEqualByComparingTo("18.00");
        assertThat(bd(report.get("enunasCommissionGross"))).isEqualByComparingTo("21.42");

        List<Map<String, Object>> bookingLines = (List<Map<String, Object>>) report.get("bookingLines");
        assertThat(bookingLines).hasSize(2);
        Map<String, Object> feeLine = bookingLines.stream()
                .filter(l -> l.get("bookingType").equals("EXPENSE_MOLLIE_FEE")).findFirst().orElseThrow();
        assertThat(bd(feeLine.get("amount"))).isEqualByComparingTo("3.57");
        assertThat(bd(feeLine.get("vatRate"))).isEqualByComparingTo("0");
    }

    @Test
    void reconciliationFailure_0_01Difference_isNeverSilentlyReconciled() {
        seedAdmin();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        String admin = login("admin@it.local", "Admin123!");
        String period = closedPeriod();

        // Deliberately inconsistent row: components sum to 119.00 but total_amount says 119.01.
        jdbc.update(
                "INSERT INTO ledger_entries (brand_partner_id, total_amount, platform_fee, brand_payout, " +
                "commission_net, commission_vat, commission_rate, currency, entry_type, status, " +
                "payout_eligible_at, moved_to_available, created_at) " +
                "VALUES (?, 119.01, 18.00, 97.58, 18.00, 3.42, 0.18, 'EUR', 'ORDER_PAYMENT', 'PENDING_RELEASE', ?, false, ?)",
                a.brand().getId(), LocalDateTime.now(), YearMonth.parse(period).atDay(10).atTime(12, 0));

        Map<String, Object> report = getReport(admin, period);

        assertThat(bd(report.get("reconciliationDifference"))).isEqualByComparingTo("0.01");
        assertThat(report.get("reconciliationStatus")).isEqualTo("UNRECONCILED");
    }
```

- [ ] **Step 2: Run the full new test class**

Run: `cd backend && ./mvnw test -Dtest=SettlementAccountingReportIntegrationTest -q`
Expected: PASS (all 8 scenarios)

- [ ] **Step 3: Run the ENTIRE backend test suite**

Run: `cd backend && ./mvnw test -q`
Expected: PASS — 259 pre-existing tests plus this plan's new ones, zero regressions (per `[[feedback_never_edit_applied_flyway]]`, V23 is a new migration, not an edit to an applied one).

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java/com/enunas/backend/settlement/accounting/SettlementAccountingReportIntegrationTest.java
git commit -m "test(accounting): full refund, refund-after-payout, mollie fees, reconciliation-failure tests"
```

---

## Self-Review Notes

- **Spec coverage:** §1 (accounting principle) → Design Decisions + Task 5 field mapping. §2 (report model) → Task 4 DTOs. §3 (two levels) → `SettlementAccountingReportDto` (Ebene A fields) + `bookingLines` (Ebene B). §4 (neutral export) → Task 6 CSV. §5 (fees/payout clarity) → `mollieFeesIncludedInActualPayout` field + Design Decision 2. §6 (booking lines) → Task 4/5. §7 (brand shares) → `brands[]`, Task 8. §8 (shipping 0% commission, never leaks into commissionNet) → Task 2's query keeps them structurally separate; asserted in Task 8. §9 (refunds from ledger, not recomputed) → Task 2 query sources only `LedgerEntry`. §10 (brandPayoutAmount vs actual) → `payoutDifference`, Task 9's refund-after-payout test. §11 (reconciliation) → Design Decision 3, Task 10. §12 (control values) → Design Decision 5 (deliberately scoped down, documented why). §13 (document/Nachweis) → Design Decision 7 (CSV export is the Nachweis). §14 (no Norman-specific assumption) → nothing in this plan references Norman. §15 (tests) → Tasks 8–10 cover all 8 named scenarios. §16 (API) → Task 6, GET only, aggregates existing data. §17 (analyze first) → Design Decisions section + this plan's preceding architecture summary.
- **Placeholder scan:** none — every step has full, concrete code.
- **Type consistency:** `AccountingPeriodAggregate` (Task 2) → consumed identically in Task 5's service and its test's mock. `SettlementAccountingReportDto` fields (Task 4) → used identically in Task 5's builder and Task 8–10's JSON key assertions (`report.get("...")` strings match the DTO's Jackson-serialized field names exactly, since Lombok `@Getter` + no custom `@JsonProperty` means field name == JSON key). `ReconciliationStatus`/`BookingType` enums → serialize as their name (`"UNRECONCILED"`, `"INCOME_COMMISSION"`) by default, matching the test assertions.
