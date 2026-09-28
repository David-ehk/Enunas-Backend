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
