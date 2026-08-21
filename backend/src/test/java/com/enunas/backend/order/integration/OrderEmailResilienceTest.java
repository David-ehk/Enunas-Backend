package com.enunas.backend.order.integration;

import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.user.EmailService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Regression + wiring coverage for OrderService.confirmShipment() and cancelOrder() — both used to
 * call EmailService directly/synchronously, so an SMTP failure would 500 and roll back an
 * already-committed PAID -> SHIPPED transition (or a cancellation + discount-usage release). Now
 * routed through ShipmentConfirmedEvent / OrderCancelledEvent, both AFTER_COMMIT, best-effort.
 */
class OrderEmailResilienceTest extends AbstractDiscountIntegrationTest {

    @MockitoBean
    private EmailService emailService;

    @Test
    void confirmShipment_onSuccess_sendsShipmentEmail() {
        BrandFixture brand = seedBrand("BrandA", "brand-a", "0.18");
        seedCustomer();
        long listingId = seedListing(brand.brand(), brand.user(), "50.00", 5);
        String customerToken = login("customer@it.local", "Customer123!");
        String brandToken = login("brand-a@it.local", "Brand123!");
        long orderId = orderId(postOrder(customerToken, null, List.of(item(listingId, 1))));
        confirmPaid(orderId);

        rest.exchange("/brand/orders/" + orderId + "/ship", HttpMethod.POST,
                new HttpEntity<>(Map.of("carrier", "DHL", "trackingNumber", "TRACK-WIRED"), auth(brandToken)),
                Map.class);

        // confirmPaid() above also triggers OrderConfirmationEmailListener's "Bestellbestätigung"
        // email to the same customer — match on subject, not just recipient, to isolate the
        // shipment email specifically (same fix ReturnLifecyclePhase3Test needed for the same reason).
        verify(emailService).sendPlainTextEmail(eq("customer@it.local"), contains("wurde versendet"), anyString());
    }

    @Test
    void confirmShipment_succeedsEvenWhenEmailThrows() {
        BrandFixture brand = seedBrand("BrandA", "brand-a", "0.18");
        seedCustomer();
        long listingId = seedListing(brand.brand(), brand.user(), "50.00", 5);
        String customerToken = login("customer@it.local", "Customer123!");
        String brandToken = login("brand-a@it.local", "Brand123!");
        long orderId = orderId(postOrder(customerToken, null, List.of(item(listingId, 1))));
        confirmPaid(orderId);
        doThrow(new RuntimeException("smtp down")).when(emailService)
                .sendPlainTextEmail(anyString(), anyString(), anyString());

        ResponseEntity<Map> resp = rest.exchange("/brand/orders/" + orderId + "/ship", HttpMethod.POST,
                new HttpEntity<>(Map.of("carrier", "DHL", "trackingNumber", "TRACK-RESILIENT"), auth(brandToken)),
                Map.class);

        assertThat(resp.getStatusCode().is2xxSuccessful()).as("ship: %s", resp.getBody()).isTrue(); // no rollback
        assertThat(orderRow(orderId).get("status")).isEqualTo("SHIPPED");
    }

    @Test
    void cancelOrder_onSuccess_sendsCancellationEmail() {
        BrandFixture brand = seedBrand("BrandA", "brand-a", "0.18");
        seedAdmin();
        seedCustomer();
        long listingId = seedListing(brand.brand(), brand.user(), "50.00", 5);
        String customerToken = login("customer@it.local", "Customer123!");
        String adminToken = login("admin@it.local", "Admin123!");
        long orderId = orderId(postOrder(customerToken, null, List.of(item(listingId, 1)))); // stays PENDING

        rest.exchange("/admin/orders/" + orderId + "/cancel", HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "CUSTOMER_REQUEST", "note", "wiring check"), auth(adminToken)),
                Map.class);

        verify(emailService).sendPlainTextEmail(eq("customer@it.local"), anyString(), anyString());
    }

    @Test
    void cancelOrder_succeedsEvenWhenEmailThrows() {
        BrandFixture brand = seedBrand("BrandA", "brand-a", "0.18");
        seedAdmin();
        seedCustomer();
        long listingId = seedListing(brand.brand(), brand.user(), "50.00", 5);
        String customerToken = login("customer@it.local", "Customer123!");
        String adminToken = login("admin@it.local", "Admin123!");
        long orderId = orderId(postOrder(customerToken, null, List.of(item(listingId, 1))));
        doThrow(new RuntimeException("smtp down")).when(emailService)
                .sendPlainTextEmail(anyString(), anyString(), anyString());

        ResponseEntity<Map> resp = rest.exchange("/admin/orders/" + orderId + "/cancel", HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "CUSTOMER_REQUEST", "note", "resilience check"), auth(adminToken)),
                Map.class);

        assertThat(resp.getStatusCode().is2xxSuccessful()).as("cancel: %s", resp.getBody()).isTrue(); // no rollback
        assertThat(orderRow(orderId).get("status")).isEqualTo("CANCELLED");
    }
}
