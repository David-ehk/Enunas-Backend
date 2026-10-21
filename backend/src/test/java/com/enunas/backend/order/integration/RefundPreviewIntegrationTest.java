package com.enunas.backend.order.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings({"rawtypes", "unchecked"})
class RefundPreviewIntegrationTest extends AbstractReturnRefundIntegrationTest {

    private ResponseEntity<Map> get(String token, String url) {
        return rest.exchange(url, HttpMethod.GET, new HttpEntity<>(null, auth(token)), Map.class);
    }

    private Map<String, Object> preview(String token, String rn) {
        ResponseEntity<Map> r = get(token, "/admin/returns/" + rn + "/refund-preview");
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        return r.getBody();
    }

    private static BigDecimal money(Object o) {
        return new BigDecimal(o.toString()).setScale(2);
    }

    @Test
    void partialDiscountedReturnPreviewEqualsWhatReleaseRefunds() {
        ReturnFixture f = deliveredOrder("TEN");
        String rn = receivedReturn(f, f.a1(), 1);
        BigDecimal expected = itemMoney(f.a1(), "customer_gross_after_discount")
                .divide(BigDecimal.valueOf(2), 2, java.math.RoundingMode.HALF_UP);

        Map<String, Object> p = preview(f.adminToken(), rn);
        assertThat(money(p.get("total"))).isEqualTo(expected);
        assertThat(money(p.get("shippingAmount"))).isEqualTo(new BigDecimal("0.00"));
        assertThat(p.get("stored")).isEqualTo(false);
        List<Map<String, Object>> lines = (List<Map<String, Object>>) p.get("lines");
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0)).containsEntry("unitsReturned", 1).containsEntry("unitsOrdered", 2);
        assertThat(lines.get(0).get("productName")).isNotNull();
        assertThat(returnRow(rn).get("refund_claim_key")).isNull();   // preview wrote nothing

        assertThat(releaseRefund(f.adminToken(), rn, null).getStatusCode().value()).isEqualTo(200);
        assertThat(money(returnRow(rn).get("refund_amount"))).isEqualTo(money(p.get("total")));
    }

    @Test
    void fullyReturnedBrandIncludesShipping() {
        ReturnFixture f = deliveredOrder(null);
        String rn = receivedReturn(f, f.b1(), 1);
        Map<String, Object> p = preview(f.adminToken(), rn);
        assertThat(money(p.get("shippingAmount"))).isEqualTo(shippingOf(f.orderId(), f.brandBId()).setScale(2));
        assertThat(money(p.get("total"))).isEqualTo(money(p.get("itemsAmount")).add(money(p.get("shippingAmount"))));
        assertThat(((Number) p.get("brandId")).longValue()).isEqualTo(f.brandBId());
    }

    @Test
    void orderPreviewListsEveryBrandReturn() {
        ReturnFixture f = deliveredOrder(null);
        String a = receivedReturn(f, f.a1(), 1);
        String b = receivedReturn(f, f.b1(), 1);
        ResponseEntity<Map> r = get(f.adminToken(), "/admin/orders/" + f.orderId() + "/refund-preview");
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> returns = (List<Map<String, Object>>) r.getBody().get("returns");
        assertThat(returns).extracting(x -> x.get("returnNumber")).containsExactlyInAnyOrder(a, b);
        Map<String, Object> ra = returns.stream().filter(x -> a.equals(x.get("returnNumber"))).findFirst().orElseThrow();
        Map<String, Object> rb = returns.stream().filter(x -> b.equals(x.get("returnNumber"))).findFirst().orElseThrow();
        assertThat(money(ra.get("shippingAmount"))).isEqualTo(new BigDecimal("0.00"));
        assertThat(money(rb.get("shippingAmount"))).isEqualTo(shippingOf(f.orderId(), f.brandBId()).setScale(2));
        assertThat(money(r.getBody().get("total"))).isEqualTo(money(ra.get("total")).add(money(rb.get("total"))));
    }

    @Test
    void approvedButNotReceivedReturnCanBePreviewed() {
        ReturnFixture f = deliveredOrder(null);
        assertThat(requestReturn(f.customerToken(), f.orderId(), f.b1(), 1).getStatusCode().value()).isEqualTo(200);
        String rn = returnNumberOf(f.orderId(), f.brandBId());
        assertThat(approve(f.adminToken(), rn).getStatusCode().value()).isEqualTo(200);
        assertThat(money(preview(f.adminToken(), rn).get("total"))).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void releasedReturnShowsStoredAmounts() {
        ReturnFixture f = deliveredOrder(null);
        String rn = receivedReturn(f, f.b1(), 1);
        releaseRefund(f.adminToken(), rn, null);
        Map<String, Object> p = preview(f.adminToken(), rn);
        assertThat(p.get("stored")).isEqualTo(true);
        assertThat(money(p.get("total"))).isEqualTo(money(returnRow(rn).get("refund_amount")));
        assertThat(money(p.get("shippingAmount"))).isEqualTo(money(returnRow(rn).get("refund_shipping_amount")));
    }

    @Test
    void dueOverviewRowCarriesThePreviewAmounts() {
        ReturnFixture f = deliveredOrder(null);
        String rn = receivedReturn(f, f.b1(), 1);
        ResponseEntity<Map> r = get(f.adminToken(), "/admin/refunds?state=DUE");
        Map<String, Object> row = ((List<Map<String, Object>>) r.getBody().get("content")).get(0);
        Map<String, Object> p = preview(f.adminToken(), rn);
        assertThat(money(row.get("itemsAmount"))).isEqualTo(money(p.get("itemsAmount")));
        assertThat(money(row.get("shippingAmount"))).isEqualTo(money(p.get("shippingAmount")));
        assertThat(money(row.get("total"))).isEqualTo(money(p.get("total")));
    }

    @Test
    void legacyLineBlanksOnlyItsDueRow() {
        ReturnFixture f = deliveredOrder(null);
        String ok = receivedReturn(f, f.a1(), 1);
        String legacy = receivedReturn(f, f.b1(), 1);
        jdbc.update("UPDATE order_items SET customer_gross_after_discount = NULL WHERE id = ?", f.b1());

        ResponseEntity<Map> r = get(f.adminToken(), "/admin/refunds?state=DUE");
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) r.getBody().get("content");
        Map<String, Object> okRow = rows.stream().filter(x -> ok.equals(x.get("returnNumber"))).findFirst().orElseThrow();
        Map<String, Object> legacyRow = rows.stream().filter(x -> legacy.equals(x.get("returnNumber"))).findFirst().orElseThrow();
        assertThat(okRow.get("total")).isNotNull();
        assertThat(legacyRow.get("itemsAmount")).isNull();
        assertThat(legacyRow.get("shippingAmount")).isNull();
        assertThat(legacyRow.get("total")).isNull();
    }

    @Test
    void customerCannotPreview() {
        ReturnFixture f = deliveredOrder(null);
        String rn = receivedReturn(f, f.b1(), 1);
        assertThat(get(f.customerToken(), "/admin/returns/" + rn + "/refund-preview").getStatusCode().value())
                .isEqualTo(403);
        assertThat(get(f.customerToken(), "/admin/orders/" + f.orderId() + "/refund-preview").getStatusCode().value())
                .isEqualTo(403);
    }
}
