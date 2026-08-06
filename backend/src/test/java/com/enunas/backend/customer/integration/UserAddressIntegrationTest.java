package com.enunas.backend.customer.integration;

import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UserAddressIntegrationTest extends AbstractDiscountIntegrationTest {

    @Test
    void firstAddress_autoBecomesDefault() {
        seedCustomer();
        String token = login("customer@it.local", "Customer123!");

        ResponseEntity<Map> created = createAddress(token, addressBody("Jane", "Doe"));

        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getBody().get("isDefault")).isEqualTo(true);
        // Regression: Jackson must key the boolean by "isDefault" only — a field/getter implicit-name
        // mismatch previously caused a duplicate "default" key alongside it in the serialized JSON.
        assertThat(created.getBody()).doesNotContainKey("default");
    }

    @Test
    void secondAddress_doesNotAutoBecomeDefault() {
        seedCustomer();
        String token = login("customer@it.local", "Customer123!");
        createAddress(token, addressBody("Jane", "Doe"));

        ResponseEntity<Map> second = createAddress(token, addressBody("John", "Doe"));

        assertThat(second.getBody().get("isDefault")).isEqualTo(false);
    }

    @Test
    void setDefault_reassignsDefaultAmongOwnAddresses() {
        seedCustomer();
        String token = login("customer@it.local", "Customer123!");
        long first = addressId(createAddress(token, addressBody("Jane", "Doe")));
        long second = addressId(createAddress(token, addressBody("John", "Doe")));

        ResponseEntity<Map> resp = rest.exchange(
                "/customer/addresses/" + second + "/default", HttpMethod.POST,
                new HttpEntity<>(auth(token)), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("isDefault")).isEqualTo(true);

        List<Map> list = listAddresses(token);
        Map firstRow = list.stream().filter(a -> ((Number) a.get("id")).longValue() == first).findFirst().orElseThrow();
        assertThat(firstRow.get("isDefault")).isEqualTo(false);
    }

    @Test
    void deleteDefault_doesNotAutoPromoteAnother() {
        seedCustomer();
        String token = login("customer@it.local", "Customer123!");
        long first = addressId(createAddress(token, addressBody("Jane", "Doe")));
        createAddress(token, addressBody("John", "Doe"));

        rest.exchange("/customer/addresses/" + first, HttpMethod.DELETE,
                new HttpEntity<>(auth(token)), Void.class);

        List<Map> remaining = listAddresses(token);
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).get("isDefault")).isEqualTo(false);
    }

    @Test
    void customerCannotUpdateAnotherCustomersAddress() {
        seedCustomer();
        String ownerToken = login("customer@it.local", "Customer123!");
        long addressId = addressId(createAddress(ownerToken, addressBody("Jane", "Doe")));

        seedUser("other@it.local", "Other123!", com.enunas.backend.user.Role.CUSTOMER);
        customerRepository.save(com.enunas.backend.customer.Customer.builder()
                .user(userRepository.findByEmail("other@it.local").orElseThrow())
                .build());
        String otherToken = login("other@it.local", "Other123!");

        ResponseEntity<Map> resp = rest.exchange(
                "/customer/addresses/" + addressId, HttpMethod.PUT,
                new HttpEntity<>(addressBody("Mallory", "Hacker"), auth(otherToken)), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    // ===== helpers =====

    private Map<String, Object> addressBody(String firstName, String lastName) {
        Map<String, Object> body = new HashMap<>();
        body.put("firstName", firstName);
        body.put("lastName", lastName);
        body.put("street", "Hauptstrasse");
        body.put("houseNumber", "1");
        body.put("postalCode", "10115");
        body.put("city", "Berlin");
        body.put("country", "DE");
        return body;
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> createAddress(String token, Map<String, Object> body) {
        return rest.exchange("/customer/addresses", HttpMethod.POST,
                new HttpEntity<>(body, auth(token)), Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map> listAddresses(String token) {
        ResponseEntity<List> resp = rest.exchange("/customer/addresses", HttpMethod.GET,
                new HttpEntity<>(auth(token)), List.class);
        return resp.getBody();
    }

    private long addressId(ResponseEntity<Map> resp) {
        return ((Number) resp.getBody().get("id")).longValue();
    }
}
