package com.enunas.backend.order.integration;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.payment.CreatePaymentCommand;
import com.enunas.backend.payment.PaymentProvider;
import com.enunas.backend.payment.PaymentResult;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * createOrder used to run the Mollie call inside the same transaction as every write around it, so
 * a failure anywhere after that call rolled the order away and left a live payment at Mollie
 * belonging to an order that no longer existed — recoverable only by hand, and the comment in
 * OrderService said exactly that.
 *
 * <p>The order is now committed BEFORE the provider is called, which inverts the failure: a
 * payment can never outlive its order. These tests pin both halves of that boundary — what
 * survives when the provider fails, and that the happy path still records the id the webhook needs
 * to settle by.
 */
class OrderCreationPaymentBoundaryTest extends AbstractDiscountIntegrationTest {

    @MockitoBean private PaymentProvider paymentProvider;

    private record Cart(String customerToken, long listingId) {}

    private Cart seedCart() {
        BrandPartner brand = seedBrand("Alpha", "alpha", "0.15").brand();
        seedCustomer();
        long listingId = seedListing(brand, brand.getUser(), "89.95", 5);
        return new Cart(login("customer@it.local", "Customer123!"), listingId);
    }

    @SuppressWarnings("unchecked")
    @Test
    void providerFailure_leavesTheOrderCommittedInsteadOfRollingItBack() {
        Cart cart = seedCart();
        when(paymentProvider.createPayment(any())).thenThrow(new RuntimeException("mollie unreachable"));

        ResponseEntity<Map> resp = postOrder(cart.customerToken(), null, List.of(item(cart.listingId(), 1)));

        // PaymentException -> 400: the customer is correctly told checkout could not start.
        assertThat(resp.getStatusCode().value()).isEqualTo(400);

        // ...but the order survives, PENDING, with its items and its unlinked payment row. This is
        // the whole point: had the provider call actually reached Mollie before failing, that
        // payment now has a real order behind it. OrderExpiryService cancels this within 30
        // minutes and releases any discount usage it reserved.
        Map<String, Object> order = jdbc.queryForMap("SELECT id, status FROM orders");
        assertThat(order.get("status")).isEqualTo("PENDING");

        Long orderId = ((Number) order.get("id")).longValue();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM order_items WHERE order_id = ?", Integer.class, orderId)).isEqualTo(1);

        // The payment row exists but carries no transaction id yet — there was none to record.
        Map<String, Object> payment = jdbc.queryForMap(
                "SELECT status, transaction_id FROM payments WHERE order_id = ?", orderId);
        assertThat(payment.get("status")).isEqualTo("PENDING");
        assertThat(payment.get("transaction_id")).isNull();
    }

    @SuppressWarnings("unchecked")
    @Test
    void successfulCheckout_recordsTheProviderIdTheWebhookResolvesBy() {
        Cart cart = seedCart();
        when(paymentProvider.createPayment(any(CreatePaymentCommand.class)))
                .thenReturn(new PaymentResult("tr_boundary_test", "https://pay.example/tr_boundary_test"));

        ResponseEntity<Map> resp = postOrder(cart.customerToken(), null, List.of(item(cart.listingId(), 1)));

        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        long orderId = ((Number) resp.getBody().get("id")).longValue();

        // MollieWebhookController finds the order by transaction id and nothing else, so an order
        // that reached the customer with a checkout URL must always have this column populated.
        assertThat(jdbc.queryForObject(
                "SELECT transaction_id FROM payments WHERE order_id = ?", String.class, orderId))
                .isEqualTo("tr_boundary_test");
    }
}
