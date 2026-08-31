package com.enunas.backend.customer.integration;

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

/**
 * DELETE /customer/me — erasure under DSGVO Art. 17, against a retention obligation that pulls the
 * other way (§257 HGB / §147 AO: ten years for the commercial and tax record). The whole design
 * rests on order-time identity being snapshotted at checkout rather than read live from the
 * profile, so these tests check both halves: that the personal data really is gone, and that the
 * retained record is still intact and readable without it.
 */
class AccountErasureIntegrationTest extends AbstractDiscountIntegrationTest {

    @MockitoBean private EmailService emailService;

    private long userId(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private ResponseEntity<Map> erase(String token) {
        return rest.exchange("/customer/me", HttpMethod.DELETE, new HttpEntity<>(auth(token)), Map.class);
    }

    @SuppressWarnings("unchecked")
    @Test
    void erasure_removesProfileAndLoginButKeepsTheOrderRecord() {
        var brand = seedBrand("Acme", "acme", "0.15");
        seedCustomer();
        seedAdmin();
        String customerToken = login("customer@it.local", "Customer123!");
        String adminToken = login("admin@it.local", "Admin123!");
        long listing = seedListing(brand.brand(), brand.user(), "50.00", 5);
        long uid = userId("customer@it.local");

        // Give the customer a profile worth erasing, and a settled order worth keeping.
        rest.exchange("/customer/me", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("firstName", "Erika", "lastName", "Mustermann",
                        "city", "Berlin", "preferredStyles", List.of("minimal")), auth(customerToken)),
                Map.class);

        ResponseEntity<Map> order = postOrder(customerToken, null, List.of(item(listing, 1)));
        long orderId = ((Number) order.getBody().get("id")).longValue();
        confirmPaid(orderId);
        // Drive it to a settled state so erasure is allowed: CANCELLED is reachable from PAID.
        rest.exchange("/admin/orders/" + orderId + "/status?status=CANCELLED", HttpMethod.PATCH,
                new HttpEntity<>(auth(adminToken)), Map.class);

        assertThat(erase(customerToken).getStatusCode().value()).isEqualTo(204);

        // --- erased: nothing on the profile identifies a person any more ---
        Map<String, Object> profile = jdbc.queryForMap(
                "SELECT first_name, last_name, username, city, profile_image_url FROM customers WHERE user_id = ?", uid);
        assertThat(profile.values()).containsOnlyNulls();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM customer_preferred_styles s JOIN customers c ON c.id = s.customer_id"
                + " WHERE c.user_id = ?", Integer.class, uid)).isZero();

        // --- erased: the login identity is gone and unroutable ---
        Map<String, Object> user = jdbc.queryForMap(
                "SELECT email, password, enabled FROM users WHERE id = ?", uid);
        assertThat((String) user.get("email")).isEqualTo("geloescht+" + uid + "@deleted.invalid");
        assertThat(user.get("password")).isNull();
        assertThat(user.get("enabled")).isEqualTo(false);

        // --- kept: the order and its checkout-time identity snapshot, which is the tax record ---
        Map<String, Object> kept = jdbc.queryForMap(
                "SELECT status, first_name, last_name, city FROM orders WHERE id = ?", orderId);
        assertThat(kept.get("status")).isEqualTo("CANCELLED");
        assertThat(kept.get("first_name")).isEqualTo("John");
        assertThat(kept.get("last_name")).isEqualTo("Doe");
        assertThat(kept.get("city")).isEqualTo("Berlin");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM order_items WHERE order_id = ?", Integer.class, orderId)).isEqualTo(1);
    }

    /** The old credentials must stop working — an erasure that leaves a usable login is not one. */
    @Test
    void erasure_endsTheAbilityToLogIn() {
        seedCustomer();
        String token = login("customer@it.local", "Customer123!");

        assertThat(erase(token).getStatusCode().value()).isEqualTo(204);

        ResponseEntity<Map> relogin = rest.postForEntity("/auth/login",
                Map.of("email", "customer@it.local", "password", "Customer123!"), Map.class);
        assertThat(relogin.getStatusCode().is2xxSuccessful()).isFalse();
    }

    /**
     * Art. 17(3)(e): an order still in flight is a live claim on both sides, so erasure waits. A
     * PAID order is the obvious case; the point of the rule is that it also covers a delivered
     * order whose 14-day Widerruf window is still open, which a naive "unpaid or unshipped" check
     * would have let through.
     */
    @SuppressWarnings("unchecked")
    @Test
    void erasure_isRefusedWhileAnOrderIsStillInFlight() {
        var brand = seedBrand("Acme", "acme", "0.15");
        seedCustomer();
        String customerToken = login("customer@it.local", "Customer123!");
        long listing = seedListing(brand.brand(), brand.user(), "50.00", 5);
        long uid = userId("customer@it.local");

        ResponseEntity<Map> order = postOrder(customerToken, null, List.of(item(listing, 1)));
        confirmPaid(((Number) order.getBody().get("id")).longValue());

        assertThat(erase(customerToken).getStatusCode().value()).isEqualTo(409);

        // Nothing was half-done: the profile and the login are exactly as they were.
        assertThat(jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, uid))
                .isEqualTo("customer@it.local");
        assertThat(jdbc.queryForObject("SELECT enabled FROM users WHERE id = ?", Boolean.class, uid)).isTrue();
    }

    /** Saved addresses are a live convenience copy, not part of the retained record. */
    @SuppressWarnings("unchecked")
    @Test
    void erasure_removesSavedAddresses() {
        seedCustomer();
        String token = login("customer@it.local", "Customer123!");
        long uid = userId("customer@it.local");

        rest.exchange("/customer/addresses", HttpMethod.POST,
                new HttpEntity<>(Map.of("firstName", "Erika", "lastName", "Mustermann",
                        "street", "Hauptstrasse", "houseNumber", "1",
                        "city", "Berlin", "postalCode", "10115", "country", "DE"), auth(token)),
                Map.class);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM user_addresses WHERE user_id = ?", Integer.class, uid)).isEqualTo(1);

        assertThat(erase(token).getStatusCode().value()).isEqualTo(204);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM user_addresses WHERE user_id = ?", Integer.class, uid)).isZero();
    }
}
