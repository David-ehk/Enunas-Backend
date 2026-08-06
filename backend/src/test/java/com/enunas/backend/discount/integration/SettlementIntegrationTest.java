package com.enunas.backend.discount.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Monthly settlement report + marker. Reuses the integration harness; ledger entries are inserted
 * via raw SQL so created_at (and entry_type/amounts) are controlled deterministically. "Today" is
 * the real clock — closed-period tests use months ≤ the last closed month.
 */
class SettlementIntegrationTest extends AbstractDiscountIntegrationTest {

    // Mirrors SettlementService's own zone: a period is closed once real time passes the 1st of
    // the FOLLOWING month, Berlin. Tests 2's "current"/"future"/"closed" months are computed from
    // this against the real clock, not hardcoded literals — a hardcoded month becomes a past
    // (permanently closed) month the moment real time passes it, silently flipping the "not yet
    // closed → 422" assertions to fail against the now-closed month. See git history for the bug
    // this replaced.
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    // ===== 1. Refund-period rule + hard invariant + credit-note =====
    @Test
    void refundLandsInItsOwnPeriod_hardInvariantHolds_andNegativeMonthIsCreditNote() {
        seedAdmin();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        long bid = a.brand().getId();
        String admin = login("admin@it.local", "Admin123!");

        // Two sales in May (gross 119 each: net 100 → commission 18.00 + VAT 3.42, payout 97.58).
        insertLedger(bid, "ORDER_PAYMENT", "2026-05-10 12:00:00", "18.00", "3.42", "97.58", "119.00");
        insertLedger(bid, "ORDER_PAYMENT", "2026-05-15 09:30:00", "18.00", "3.42", "97.58", "119.00");
        // One refund (reversal of a May sale) booked in JUNE — negated.
        insertLedger(bid, "REFUND_REVERSAL", "2026-06-10 08:00:00", "-18.00", "-3.42", "-97.58", "-119.00");

        // May shows the full commission; refund does NOT touch May.
        Map<String, Object> may = row(admin, "2026-05", bid);
        assertThat(bd(may.get("commissionNet"))).isEqualByComparingTo("36.00");
        assertThat(bd(may.get("commissionVat"))).isEqualByComparingTo("6.84");
        assertThat(bd(may.get("commissionGross"))).isEqualByComparingTo("42.84");
        assertThat(bd(may.get("payoutAmount"))).isEqualByComparingTo("195.16");
        assertThat(((Number) may.get("orderCount")).longValue()).isEqualTo(2);
        assertThat(((Number) may.get("refundCount")).longValue()).isEqualTo(0);
        assertThat((Boolean) may.get("isCreditNote")).isFalse();
        // Hard invariant: commissionNet + commissionVat + payout == SUM(total_amount) = 238.00.
        assertThat(bd(may.get("commissionNet")).add(bd(may.get("commissionVat"))).add(bd(may.get("payoutAmount"))))
                .isEqualByComparingTo("238.00");

        // June shows only the refund as a negative reduction → net-negative → credit note.
        Map<String, Object> jun = row(admin, "2026-06", bid);
        assertThat(bd(jun.get("commissionNet"))).isEqualByComparingTo("-18.00");
        assertThat(bd(jun.get("commissionVat"))).isEqualByComparingTo("-3.42");
        assertThat(bd(jun.get("payoutAmount"))).isEqualByComparingTo("-97.58");
        assertThat(((Number) jun.get("orderCount")).longValue()).isEqualTo(0);
        assertThat(((Number) jun.get("refundCount")).longValue()).isEqualTo(1);
        assertThat((Boolean) jun.get("isCreditNote")).isTrue();
        assertThat(bd(jun.get("commissionNet")).add(bd(jun.get("commissionVat"))).add(bd(jun.get("payoutAmount"))))
                .isEqualByComparingTo("-119.00");
    }

    // ===== 2. Closed-period guard (1st of FOLLOWING month, Berlin) =====
    @Test
    void closedPeriodGuard_rejectsRunningAndFutureMonths_allowsClosedMonth() {
        seedAdmin();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        long bid = a.brand().getId();
        String admin = login("admin@it.local", "Admin123!");

        YearMonth currentMonth = YearMonth.now(BERLIN);
        YearMonth futureMonth = currentMonth.plusMonths(1);
        YearMonth closedMonth = currentMonth.minusMonths(1); // always in the past → always closed
        insertLedger(bid, "ORDER_PAYMENT", closedMonth + "-10 12:00:00", "18.00", "3.42", "97.58", "119.00");

        // Current month — not yet closed → 422.
        assertThat(postSettle(admin, bid, currentMonth.toString(), null).getStatusCode().value()).isEqualTo(422);
        // Future month → 422.
        assertThat(postSettle(admin, bid, futureMonth.toString(), null).getStatusCode().value()).isEqualTo(422);
        // Bad format → 400. (Month 13 is invalid regardless of the current date — no rot risk here.)
        assertThat(postSettle(admin, bid, "2026-13", null).getStatusCode().value()).isEqualTo(400);
        // Closed month → 200.
        assertThat(postSettle(admin, bid, closedMonth.toString(), null).getStatusCode().value()).isEqualTo(200);
    }

    // ===== 3. Double-settle → 409; settled row leaves the GET =====
    @Test
    void doubleSettleRejected_andSettledRowExcludedFromGet() {
        seedAdmin();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        long bid = a.brand().getId();
        String admin = login("admin@it.local", "Admin123!");
        insertLedger(bid, "ORDER_PAYMENT", "2026-05-10 12:00:00", "18.00", "3.42", "97.58", "119.00");

        assertThat(postSettle(admin, bid, "2026-05", null).getStatusCode().value()).isEqualTo(200);
        assertThat(postSettle(admin, bid, "2026-05", null).getStatusCode().value())
                .isEqualTo(HttpStatus.CONFLICT.value()); // 409
        assertThat(row(admin, "2026-05", bid)).isNull(); // excluded after settlement
    }

    // ===== 4. Freeze: stored snapshot is immune to later ledger changes =====
    @Test
    void settlementSnapshotIsFrozen_againstLaterLedgerChanges() {
        seedAdmin();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        long bid = a.brand().getId();
        String admin = login("admin@it.local", "Admin123!");
        insertLedger(bid, "ORDER_PAYMENT", "2026-05-10 12:00:00", "18.00", "3.42", "97.58", "119.00");

        // Live aggregation before settling.
        assertThat(bd(row(admin, "2026-05", bid).get("commissionNet"))).isEqualByComparingTo("18.00");

        // Settle (freezes 18.00 / 3.42 / 97.58) with an invoice reference.
        ResponseEntity<Map> resp = postSettle(admin, bid, "2026-05", Map.of("invoiceReference", "LEX-2026-05-001"));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        // A refund booked into the now-closed May AFTER settlement.
        insertLedger(bid, "REFUND_REVERSAL", "2026-05-20 10:00:00", "-18.00", "-3.42", "-97.58", "-119.00");

        // The stored snapshot is unchanged (the binding basis for invoice + payout).
        Map<String, Object> run = jdbc.queryForMap(
                "SELECT commission_net, commission_vat, payout_amount, invoice_reference " +
                "FROM settlement_runs WHERE brand_id = ? AND period = '2026-05'", bid);
        assertThat((BigDecimal) run.get("commission_net")).isEqualByComparingTo("18.00");
        assertThat((BigDecimal) run.get("commission_vat")).isEqualByComparingTo("3.42");
        assertThat((BigDecimal) run.get("payout_amount")).isEqualByComparingTo("97.58");
        assertThat(run.get("invoice_reference")).isEqualTo("LEX-2026-05-001");
    }

    // ===== 5. Timezone: 2026-05-31 22:00 UTC == 2026-06-01 00:00 Berlin → June =====
    @Test
    void utcTimestampAtBerlinMonthBoundary_aggregatesIntoJune() {
        seedAdmin();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        long bid = a.brand().getId();
        String admin = login("admin@it.local", "Admin123!");

        // CEST (summer) is UTC+2, so 22:00Z is exactly 00:00 Berlin on June 1.
        insertLedger(bid, "ORDER_PAYMENT", "2026-05-31 22:00:00", "18.00", "3.42", "97.58", "119.00");

        assertThat(row(admin, "2026-05", bid)).isNull();            // NOT May
        Map<String, Object> jun = row(admin, "2026-06", bid);       // June
        assertThat(jun).isNotNull();
        assertThat(((Number) jun.get("orderCount")).longValue()).isEqualTo(1);
        assertThat(bd(jun.get("commissionNet"))).isEqualByComparingTo("18.00");
    }

    // ===== helpers =====

    private void insertLedger(long brandId, String entryType, String createdAtUtc,
                              String commissionNet, String commissionVat, String brandPayout, String total) {
        jdbc.update(
                "INSERT INTO ledger_entries (brand_partner_id, total_amount, platform_fee, brand_payout, " +
                "commission_net, commission_vat, commission_rate, currency, entry_type, status, " +
                "payout_eligible_at, moved_to_available, created_at) " +
                "VALUES (?, CAST(? AS numeric), CAST(? AS numeric), CAST(? AS numeric), CAST(? AS numeric), " +
                "CAST(? AS numeric), 0.18, 'EUR', ?, 'PENDING_RELEASE', CAST(? AS timestamp), false, CAST(? AS timestamp))",
                brandId, total, commissionNet, brandPayout, commissionNet, commissionVat,
                entryType, createdAtUtc, createdAtUtc);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> row(String adminToken, String period, long brandId) {
        ResponseEntity<List> resp = rest.exchange("/admin/settlements?period=" + period,
                HttpMethod.GET, new HttpEntity<>(auth(adminToken)), List.class);
        List<Map<String, Object>> rows = resp.getBody();
        if (rows == null) return null;
        return rows.stream()
                .filter(r -> ((Number) r.get("brandId")).longValue() == brandId)
                .findFirst().orElse(null);
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> postSettle(String adminToken, long brandId, String period, Map<String, Object> body) {
        return rest.exchange("/admin/settlements/" + brandId + "/" + period, HttpMethod.POST,
                new HttpEntity<>(body, auth(adminToken)), Map.class);
    }

    private static BigDecimal bd(Object v) {
        return new BigDecimal(String.valueOf(v));
    }
}
