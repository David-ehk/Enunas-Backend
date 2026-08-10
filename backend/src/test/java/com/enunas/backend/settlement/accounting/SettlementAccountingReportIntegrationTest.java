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

    @Test
    void adminDiscount_reducesOnlyCommission_brandPayoutUnchanged() {
        seedAdmin();
        BrandFixture a = seedBrand("BrandA", "brand-a", "0.18");
        brandShippingProfileRepository.save(BrandShippingProfile.builder()
                .brandPartner(a.brand()).shippingCost(new BigDecimal("0.00")).currency("EUR").build());
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
        brandShippingProfileRepository.save(BrandShippingProfile.builder()
                .brandPartner(a.brand()).shippingCost(new BigDecimal("0.00")).currency("EUR").build());
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
