package com.enunas.backend.order.integration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BrandReceiveReturnIntegrationTest extends AbstractReturnRefundIntegrationTest {

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
    @SuppressWarnings({"rawtypes", "unchecked"})
    void receiveAndLabelResponsesAreScopedToTheBrand() {
        ReturnFixture f = deliveredOrder(null);
        requestReturn(f.customerToken(), f.orderId(), null, null);   // whole order: a return per brand
        String rn = returnNumberOf(f.orderId(), f.brandAId());
        approve(f.adminToken(), rn);
        var label = rest.exchange("/brand/returns/" + rn + "/label", HttpMethod.POST,
                new HttpEntity<>(Map.of("carrier", "DHL", "trackingNumber", "T1",
                        "labelUrl", "https://example.com/l.pdf"), auth(f.brandAToken())), Map.class);
        assertThat(label.getStatusCode().value()).isEqualTo(200);
        var received = brandReceive(f.brandAToken(), rn);
        assertThat(received.getStatusCode().value()).isEqualTo(200);
        for (Map body : List.of(label.getBody(), received.getBody())) {
            var items = (List<Map>) body.get("items");
            assertThat(items).extracting(i -> ((Number) i.get("id")).longValue()).doesNotContain(f.b1());
            var returns = (List<Map>) body.get("returns");
            assertThat(returns).extracting(r -> ((Number) r.get("brandId")).longValue()).containsOnly(f.brandAId());
        }
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
        rest.exchange("/admin/returns/" + rn + "/receive", HttpMethod.POST,
                new HttpEntity<>(null, auth(f.adminToken())), Map.class);
        assertThat(returnRow(rn).get("received_by")).isEqualTo("ADMIN");
    }

    @Test
    void cannotConfirmBeforeApproval() {
        ReturnFixture f = deliveredOrder(null);
        requestReturn(f.customerToken(), f.orderId(), f.a1(), 1);
        assertThat(brandReceive(f.brandAToken(), returnNumberOf(f.orderId(), f.brandAId()))
                .getStatusCode().value()).isEqualTo(409);
    }
}
