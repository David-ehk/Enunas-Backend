package com.enunas.backend.order.integration;

import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CheckoutAddressIntegrationTest extends AbstractDiscountIntegrationTest {

    @Test
    void checkout_withSavedAddressId_usesThatAddress() {
        var brand = seedBrand("BrandA", "brand-a", "0.18");
        long listing = seedListing(brand.brand(), brand.user(), "50.00", 10);
        seedCustomer();
        String token = login("customer@it.local", "Customer123!");

        long addressId = createSavedAddress(token, "Jane", "Doe");

        Map<String, Object> body = new HashMap<>();
        body.put("items", List.of(item(listing, 1)));
        body.put("savedAddressId", addressId);
        ResponseEntity<Map> resp = rest.exchange("/orders", HttpMethod.POST,
                new HttpEntity<>(body, auth(token)), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        Map shippingAddress = (Map) resp.getBody().get("shippingAddress");
        assertThat(shippingAddress.get("firstName")).isEqualTo("Jane");
        assertThat(shippingAddress.get("country")).isEqualTo("DE");
    }

    @Test
    void checkout_withInlineAddress_stillWorks() {
        var brand = seedBrand("BrandA", "brand-a", "0.18");
        long listing = seedListing(brand.brand(), brand.user(), "50.00", 10);
        seedCustomer();
        String token = login("customer@it.local", "Customer123!");

        Map<String, Object> body = new HashMap<>();
        body.put("items", List.of(item(listing, 1)));
        body.put("shippingAddress", Map.of(
                "firstName", "Jane", "lastName", "Doe", "street", "Hauptstrasse",
                "houseNumber", "1", "city", "Berlin", "postalCode", "10115", "country", "DE"));
        ResponseEntity<Map> resp = rest.exchange("/orders", HttpMethod.POST,
                new HttpEntity<>(body, auth(token)), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void checkout_withBothAddressSources_badRequest() {
        var brand = seedBrand("BrandA", "brand-a", "0.18");
        long listing = seedListing(brand.brand(), brand.user(), "50.00", 10);
        seedCustomer();
        String token = login("customer@it.local", "Customer123!");
        long addressId = createSavedAddress(token, "Jane", "Doe");

        Map<String, Object> body = new HashMap<>();
        body.put("items", List.of(item(listing, 1)));
        body.put("savedAddressId", addressId);
        body.put("shippingAddress", Map.of(
                "firstName", "Jane", "lastName", "Doe", "street", "Hauptstrasse",
                "houseNumber", "1", "city", "Berlin", "postalCode", "10115", "country", "DE"));
        ResponseEntity<Map> resp = rest.exchange("/orders", HttpMethod.POST,
                new HttpEntity<>(body, auth(token)), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void checkout_withNeitherAddressSource_badRequest() {
        var brand = seedBrand("BrandA", "brand-a", "0.18");
        long listing = seedListing(brand.brand(), brand.user(), "50.00", 10);
        seedCustomer();
        String token = login("customer@it.local", "Customer123!");

        Map<String, Object> body = new HashMap<>();
        body.put("items", List.of(item(listing, 1)));
        ResponseEntity<Map> resp = rest.exchange("/orders", HttpMethod.POST,
                new HttpEntity<>(body, auth(token)), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void checkout_withAnotherCustomersSavedAddressId_notFound() {
        var brand = seedBrand("BrandA", "brand-a", "0.18");
        long listing = seedListing(brand.brand(), brand.user(), "50.00", 10);
        seedCustomer();
        String ownerToken = login("customer@it.local", "Customer123!");
        long addressId = createSavedAddress(ownerToken, "Jane", "Doe");

        seedUser("other@it.local", "Other123!", com.enunas.backend.user.Role.CUSTOMER);
        customerRepository.save(com.enunas.backend.customer.Customer.builder()
                .user(userRepository.findByEmail("other@it.local").orElseThrow())
                .build());
        String otherToken = login("other@it.local", "Other123!");

        Map<String, Object> body = new HashMap<>();
        body.put("items", List.of(item(listing, 1)));
        body.put("savedAddressId", addressId);
        ResponseEntity<Map> resp = rest.exchange("/orders", HttpMethod.POST,
                new HttpEntity<>(body, auth(otherToken)), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void checkout_withSavedAddressInDisallowedCountry_badRequest() {
        var brand = seedBrand("BrandA", "brand-a", "0.18");
        long listing = seedListing(brand.brand(), brand.user(), "50.00", 10);
        seedCustomer();
        String token = login("customer@it.local", "Customer123!");

        Map<String, Object> frAddress = new HashMap<>();
        frAddress.put("firstName", "Jean");
        frAddress.put("lastName", "Dupont");
        frAddress.put("street", "Rue de Paris");
        frAddress.put("houseNumber", "1");
        frAddress.put("postalCode", "75001");
        frAddress.put("city", "Paris");
        frAddress.put("country", "FR");
        ResponseEntity<Map> created = rest.exchange("/customer/addresses", HttpMethod.POST,
                new HttpEntity<>(frAddress, auth(token)), Map.class);
        long addressId = ((Number) created.getBody().get("id")).longValue();

        Map<String, Object> body = new HashMap<>();
        body.put("items", List.of(item(listing, 1)));
        body.put("savedAddressId", addressId);
        ResponseEntity<Map> resp = rest.exchange("/orders", HttpMethod.POST,
                new HttpEntity<>(body, auth(token)), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void checkout_withSavedAddressWithMalformedPostalCode_badRequest() {
        var brand = seedBrand("BrandA", "brand-a", "0.18");
        long listing = seedListing(brand.brand(), brand.user(), "50.00", 10);
        seedCustomer();
        String token = login("customer@it.local", "Customer123!");

        // UserAddressDto.postalCode has no German-format pattern, so this saves fine — but
        // ShippingAddressDto requires ^\d{5}$, and the country check alone would let it through.
        Map<String, Object> malformedAddress = new HashMap<>();
        malformedAddress.put("firstName", "Jane");
        malformedAddress.put("lastName", "Doe");
        malformedAddress.put("street", "Hauptstrasse");
        malformedAddress.put("houseNumber", "1");
        malformedAddress.put("postalCode", "ABC123");
        malformedAddress.put("city", "Berlin");
        malformedAddress.put("country", "DE");
        ResponseEntity<Map> created = rest.exchange("/customer/addresses", HttpMethod.POST,
                new HttpEntity<>(malformedAddress, auth(token)), Map.class);
        long addressId = ((Number) created.getBody().get("id")).longValue();

        Map<String, Object> body = new HashMap<>();
        body.put("items", List.of(item(listing, 1)));
        body.put("savedAddressId", addressId);
        ResponseEntity<Map> resp = rest.exchange("/orders", HttpMethod.POST,
                new HttpEntity<>(body, auth(token)), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    private long createSavedAddress(String token, String firstName, String lastName) {
        Map<String, Object> body = new HashMap<>();
        body.put("firstName", firstName);
        body.put("lastName", lastName);
        body.put("street", "Hauptstrasse");
        body.put("houseNumber", "1");
        body.put("postalCode", "10115");
        body.put("city", "Berlin");
        body.put("country", "DE");
        ResponseEntity<Map> resp = rest.exchange("/customer/addresses", HttpMethod.POST,
                new HttpEntity<>(body, auth(token)), Map.class);
        return ((Number) resp.getBody().get("id")).longValue();
    }
}
