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

        // Real values on the row, so the brand assertions below can actually catch a leak.
        jdbc.update("UPDATE orders SET refund_transaction_id = 'ref_leak_probe', "
                + "cancellation_note = 'REFUND_REQUIRED internal' WHERE id = ?", oid);

        String brandToken = login("brand-a@it.local", "Brand123!");
        ResponseEntity<Map> brandView = rest.exchange("/brand/orders", HttpMethod.GET,
                new HttpEntity<>(null, auth(brandToken)), Map.class);
        List<Map<String, Object>> content = (List<Map<String, Object>>) brandView.getBody().get("content");
        assertThat(content).isNotEmpty();
        assertThat(content.get(0)).containsKey("cancellationReason");
        assertThat(content.get(0)).doesNotContainKey("cancellationNote");
        // The DTO field exists (Jackson emits nulls), so the key is present for brands — what must
        // never happen is a non-null value. The row holds 'ref_leak_probe', so null proves scoping.
        assertThat(content.get(0).get("refundTransactionId")).isNull();
    }
}
