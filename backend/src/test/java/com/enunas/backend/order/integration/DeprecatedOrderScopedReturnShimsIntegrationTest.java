package com.enunas.backend.order.integration;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.user.EmailService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deprecated order-scoped return shims (POST /admin/orders/{id}/return/approve|receive|refund
 * — kept so pre-existing callers work while they migrate to /admin/returns/{returnNumber}/*)
 * delegate to approveReturn/receiveReturn/processRefund via a plain in-class method call. Spring's
 * @Transactional is proxy-based and never intercepts that kind of self-invocation, so the callee's
 * own @Transactional was silently inert — receiveReturnByOrder 500'd with
 * InvalidDataAccessApiUsageException ("No active transaction for update or delete query") the
 * moment it hit ProductVariantRepository.restoreStock, a bare @Modifying query that requires an
 * active transaction. Fixed by adding @Transactional directly on the shim methods themselves (see
 * OrderService for why processRefundByOrder is deliberately excluded).
 */
class DeprecatedOrderScopedReturnShimsIntegrationTest extends AbstractDiscountIntegrationTest {

    @MockitoBean private EmailService emailService;

    @Test
    void approveReceiveRefund_viaDeprecatedOrderScopedShims_allSucceed() {
        BrandPartner brand = seedBrand("Acme", "acme", "0.15").brand();
        seedCustomer();
        seedAdmin();
        long listingId = seedListing(brand, brand.getUser(), "50.00", 5);

        String customerToken = login("customer@it.local", "Customer123!");
        String brandToken = login("acme@it.local", "Brand123!");
        String adminToken = login("admin@it.local", "Admin123!");

        ResponseEntity<Map> order = postOrder(customerToken, null, List.of(item(listingId, 1)));
        long orderId = ((Number) order.getBody().get("id")).longValue();
        confirmPaid(orderId);

        rest.exchange("/brand/orders/" + orderId + "/ship", HttpMethod.POST,
                new HttpEntity<>(Map.of("carrier", "DHL", "trackingNumber", "T1"), auth(brandToken)), Map.class);
        rest.exchange("/admin/orders/" + orderId + "/status?status=DELIVERED", HttpMethod.PATCH,
                new HttpEntity<>(null, auth(adminToken)), Map.class);

        Map<String, Object> returnBody = new HashMap<>();
        returnBody.put("reason", "WRONG_SIZE");
        returnBody.put("description", "Passt nicht");
        rest.exchange("/orders/" + orderId + "/return", HttpMethod.POST,
                new HttpEntity<>(returnBody, auth(customerToken)), Map.class);

        ResponseEntity<Map> approve = rest.exchange("/admin/orders/" + orderId + "/return/approve",
                HttpMethod.POST, new HttpEntity<>(null, auth(adminToken)), Map.class);
        assertThat(approve.getStatusCode().value()).isEqualTo(200);
        assertThat(approve.getBody().get("status")).isEqualTo("RETURN_APPROVED");

        ResponseEntity<Map> receive = rest.exchange("/admin/orders/" + orderId + "/return/receive",
                HttpMethod.POST, new HttpEntity<>(null, auth(adminToken)), Map.class);
        assertThat(receive.getStatusCode().value()).as("body: %s", receive.getBody()).isEqualTo(200);
        assertThat(receive.getBody().get("status")).isEqualTo("RETURN_RECEIVED");

        // Stock genuinely restored (the exact write that used to 500 outside a transaction).
        int stock = jdbc.queryForObject(
                "SELECT stock_quantity FROM product_variants WHERE id = " +
                "(SELECT variant_id FROM order_items WHERE order_id = ?)", Integer.class, orderId);
        assertThat(stock).isEqualTo(5); // back to the seeded quantity after restoring the 1 returned

        ResponseEntity<Map> refund = rest.exchange("/admin/orders/" + orderId + "/return/refund",
                HttpMethod.POST, new HttpEntity<>(null, auth(adminToken)), Map.class);
        assertThat(refund.getStatusCode().value()).as("body: %s", refund.getBody()).isEqualTo(200);
        assertThat(refund.getBody().get("status")).isEqualTo("REFUNDED");
    }
}
