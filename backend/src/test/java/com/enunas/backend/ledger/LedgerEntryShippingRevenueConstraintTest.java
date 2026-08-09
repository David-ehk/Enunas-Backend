package com.enunas.backend.ledger;

import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test: V22 migration updates the ledger_entries.entry_type CHECK constraint to allow
 * SHIPPING_REVENUE. This test verifies that a LedgerEntry with entryType=SHIPPING_REVENUE can be
 * persisted to the real Postgres database without violating the constraint. Tests run against
 * Testcontainers PostgreSQL with the full Flyway migration stack.
 */
class LedgerEntryShippingRevenueConstraintTest extends AbstractDiscountIntegrationTest {

    @Test
    void shippingRevenueEntryTypePassesDbConstraint() {
        // Seed a brand to get a valid brand_partner_id
        seedAdmin();
        var brand = seedBrand("ShippingBrand", "shipping-brand", "0.18");
        long brandId = brand.brand().getId();

        // Insert a SHIPPING_REVENUE entry via raw SQL (matching the SettlementIntegrationTest pattern)
        // This exercises the actual DB constraint, not just the Java enum.
        insertShippingRevenueEntry(brandId, "2026-06-15 10:00:00", "25.00");

        // Query back to confirm the row persisted without constraint violation
        Long count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM ledger_entries WHERE brand_partner_id = ? AND entry_type = 'SHIPPING_REVENUE'",
            Long.class,
            brandId
        );

        assertThat(count).isEqualTo(1L);
    }

    /**
     * Insert a SHIPPING_REVENUE entry using raw JDBC, matching the SettlementIntegrationTest pattern.
     * Uses typical values for a shipping revenue entry (no commission/vat, same as total_amount).
     */
    private void insertShippingRevenueEntry(long brandId, String createdAtUtc, String shippingAmount) {
        jdbc.update(
            "INSERT INTO ledger_entries (brand_partner_id, total_amount, platform_fee, brand_payout, " +
            "commission_rate, currency, entry_type, status, " +
            "payout_eligible_at, moved_to_available, created_at) " +
            "VALUES (?, CAST(? AS numeric), CAST(? AS numeric), CAST(? AS numeric), " +
            "0.00, 'EUR', 'SHIPPING_REVENUE', 'PENDING_RELEASE', CAST(? AS timestamp), false, CAST(? AS timestamp))",
            brandId, shippingAmount, shippingAmount, shippingAmount, createdAtUtc, createdAtUtc
        );
    }
}
