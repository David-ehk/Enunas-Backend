package com.enunas.backend.order.integration;

import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The order response carries the payment provider's own id for the order's payment, copied from
 * {@code Payment.transactionId}. It is what reconciles an order against the Mollie dashboard, a bank
 * statement or a support ticket — before this there was no way to get from an order to its Mollie
 * payment through the API at all.
 *
 * <p>Deliberately a field of its own rather than part of {@code orderNumber}: the order number is
 * assigned at order creation, before any payment exists, and has to stay stable because it is on
 * invoices and in the customer's inbox, whereas a retried payment gets a fresh provider id.
 */
class OrderMolliePaymentIdIntegrationTest extends AbstractDiscountIntegrationTest {

    @Test
    void orderCreation_returnsTheProviderPaymentId() {
        seedCustomer();
        BrandFixture brand = seedBrand("Acme", "acme", "0.18");
        long listing = seedListing(brand.brand(), brand.user(), "100.00", 5);
        String token = login("customer@it.local", "Customer123!");

        ResponseEntity<Map> resp = postOrder(token, null, List.of(item(listing, 1)));

        assertThat(resp.getStatusCode().is2xxSuccessful()).as("order: %s", resp.getBody()).isTrue();
        String paymentId = (String) resp.getBody().get("molliePaymentId");
        assertThat(paymentId).isNotBlank();
        assertThat(paymentId).isEqualTo(transactionIdOf(orderId(resp)));
    }

    @Test
    void orderDetail_carriesTheProviderPaymentId() {
        seedCustomer();
        BrandFixture brand = seedBrand("Acme", "acme", "0.18");
        long listing = seedListing(brand.brand(), brand.user(), "100.00", 5);
        String token = login("customer@it.local", "Customer123!");
        long oid = orderId(postOrder(token, null, List.of(item(listing, 1))));

        ResponseEntity<Map> resp = rest.exchange("/orders/" + oid, HttpMethod.GET,
                new HttpEntity<>(auth(token)), Map.class);

        assertThat(resp.getStatusCode().is2xxSuccessful()).as("body: %s", resp.getBody()).isTrue();
        assertThat(resp.getBody().get("molliePaymentId")).isEqualTo(transactionIdOf(oid));
    }

    /**
     * The list path resolves the id through the batched {@code loadRelations}, not per order — this
     * asserts every row on the page actually gets its own id rather than one being smeared across
     * the page or dropped.
     */
    @Test
    @SuppressWarnings("unchecked")
    void orderList_carriesEachOrdersOwnPaymentId() {
        seedCustomer();
        BrandFixture brand = seedBrand("Acme", "acme", "0.18");
        long listing = seedListing(brand.brand(), brand.user(), "100.00", 20);
        String token = login("customer@it.local", "Customer123!");
        long first = orderId(postOrder(token, null, List.of(item(listing, 1))));
        long second = orderId(postOrder(token, null, List.of(item(listing, 1))));

        ResponseEntity<Map> resp = rest.exchange("/orders/me", HttpMethod.GET,
                new HttpEntity<>(auth(token)), Map.class);

        assertThat(resp.getStatusCode().is2xxSuccessful()).as("body: %s", resp.getBody()).isTrue();
        List<Map<String, Object>> rows = content(resp);
        assertThat(rows).as("both orders on the page").hasSizeGreaterThanOrEqualTo(2);
        assertThat(paymentIdIn(rows, first)).isEqualTo(transactionIdOf(first));
        assertThat(paymentIdIn(rows, second)).isEqualTo(transactionIdOf(second));
        assertThat(transactionIdOf(first)).isNotEqualTo(transactionIdOf(second));
    }

    /** Null, not an error, while the provider has issued no id — the field is nullable by design. */
    @Test
    void orderWithoutAProviderId_reportsNull() {
        seedCustomer();
        BrandFixture brand = seedBrand("Acme", "acme", "0.18");
        long listing = seedListing(brand.brand(), brand.user(), "100.00", 5);
        String token = login("customer@it.local", "Customer123!");
        long oid = orderId(postOrder(token, null, List.of(item(listing, 1))));
        jdbc.update("UPDATE payments SET transaction_id = NULL WHERE order_id = ?", oid);

        ResponseEntity<Map> resp = rest.exchange("/orders/" + oid, HttpMethod.GET,
                new HttpEntity<>(auth(token)), Map.class);

        assertThat(resp.getStatusCode().is2xxSuccessful()).as("body: %s", resp.getBody()).isTrue();
        assertThat(resp.getBody().get("molliePaymentId")).isNull();
    }

    /** The order number is the stable customer-facing identifier and must not absorb the payment id. */
    @Test
    void orderNumber_staysFreeOfThePaymentId() {
        seedCustomer();
        BrandFixture brand = seedBrand("Acme", "acme", "0.18");
        long listing = seedListing(brand.brand(), brand.user(), "100.00", 5);
        String token = login("customer@it.local", "Customer123!");

        ResponseEntity<Map> resp = postOrder(token, null, List.of(item(listing, 1)));

        String orderNumber = (String) resp.getBody().get("orderNumber");
        assertThat(orderNumber).startsWith("ENS-");
        assertThat(orderNumber).doesNotContain((String) resp.getBody().get("molliePaymentId"));
    }

    // ===== Helpers =====

    private String transactionIdOf(long orderId) {
        return jdbc.queryForObject(
                "SELECT transaction_id FROM payments WHERE order_id = ?", String.class, orderId);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> content(ResponseEntity<Map> resp) {
        Object body = resp.getBody().get("content");
        return body != null ? (List<Map<String, Object>>) body : (List<Map<String, Object>>) (Object) resp.getBody();
    }

    private String paymentIdIn(List<Map<String, Object>> rows, long orderId) {
        return rows.stream()
                .filter(r -> ((Number) r.get("id")).longValue() == orderId)
                .map(r -> (String) r.get("molliePaymentId"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("order " + orderId + " not on the page"));
    }
}
