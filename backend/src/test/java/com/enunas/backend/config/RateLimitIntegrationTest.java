package com.enunas.backend.config;

import com.enunas.backend.customer.Customer;
import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.user.Role;
import com.enunas.backend.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the real security chain. Counters live for the whole (shared) context, so every test
 * uses its own X-Forwarded-For IP (honoured via server.forward-headers-strategy=native, backed by
 * Tomcat's RemoteIpValve). TestRestTemplate connects from 127.0.0.1, which is inside Tomcat's
 * default trusted internal-proxies range, so its X-Forwarded-For is honoured here too.
 */
@TestPropertySource(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.rules.checkout.limit=2",
        "app.rate-limit.rules.global.limit=3"
})
class RateLimitIntegrationTest extends AbstractDiscountIntegrationTest {

    private HttpHeaders from(String ip) {
        HttpHeaders h = new HttpHeaders();
        h.set("X-Forwarded-For", ip);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> post(String path, Object body, HttpHeaders headers) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    @SuppressWarnings("unchecked")
    private String loginFrom(String ip, String email, String password) {
        return (String) post("/auth/login", Map.of("email", email, "password", password), from(ip))
                .getBody().get("token");
    }

    @Test
    @SuppressWarnings("rawtypes")
    void login_eleventhAttemptFromSameIp_is429WithRetryAfter() {
        Map<String, String> bad = Map.of("email", "nobody@it.local", "password", "wrong");
        for (int i = 0; i < 10; i++) {
            assertThat(post("/auth/login", bad, from("203.0.113.1")).getStatusCode().value()).isNotEqualTo(429);
        }

        ResponseEntity<Map> blocked = post("/auth/login", bad, from("203.0.113.1"));

        assertThat(blocked.getStatusCode().value()).isEqualTo(429);
        assertThat(Long.parseLong(blocked.getHeaders().getFirst("Retry-After"))).isBetween(1L, 60L);
        assertThat(blocked.getBody()).containsEntry("status", 429).containsEntry("path", "/auth/login");
    }

    @Test
    void differentForwardedIp_isNotAffected() {
        Map<String, String> bad = Map.of("email", "nobody@it.local", "password", "wrong");
        for (int i = 0; i < 11; i++) {
            post("/auth/login", bad, from("203.0.113.2"));
        }

        assertThat(post("/auth/login", bad, from("203.0.113.3")).getStatusCode().value()).isNotEqualTo(429);
    }

    @Test
    void checkout_isKeyedPerUserNotPerIp() {
        seedCustomer();
        User second = seedUser("second@it.local", "Customer123!", Role.CUSTOMER);
        customerRepository.save(Customer.builder().user(second).build());
        String a = loginFrom("198.51.100.1", "customer@it.local", "Customer123!");
        String b = loginFrom("198.51.100.2", "second@it.local", "Customer123!");

        HttpHeaders asA = from("198.51.100.7");
        asA.setBearerAuth(a);
        HttpHeaders asB = from("198.51.100.7");
        asB.setBearerAuth(b);

        // "{}" is an invalid preview body (400), but the limiter counts it before the controller runs.
        assertThat(post("/orders/preview", "{}", asA).getStatusCode().value()).isNotEqualTo(429);
        assertThat(post("/orders/preview", "{}", asA).getStatusCode().value()).isNotEqualTo(429);
        assertThat(post("/orders/preview", "{}", asA).getStatusCode().value()).isEqualTo(429);

        assertThat(post("/orders/preview", "{}", asB).getStatusCode().value()).isNotEqualTo(429);
    }

    @Test
    void anonymousCatalogReads_hitGlobalCap() {
        for (int i = 0; i < 3; i++) {
            assertThat(rest.exchange("/products", HttpMethod.GET, new HttpEntity<>(from("203.0.113.9")), String.class)
                    .getStatusCode().value()).isNotEqualTo(429);
        }

        assertThat(rest.exchange("/products", HttpMethod.GET, new HttpEntity<>(from("203.0.113.9")), String.class)
                .getStatusCode().value()).isEqualTo(429);
    }

    @Test
    void healthCheck_isNeverLimited() {
        for (int i = 0; i < 6; i++) {
            assertThat(rest.exchange("/actuator/health", HttpMethod.GET,
                    new HttpEntity<>(from("203.0.113.10")), String.class).getStatusCode().value()).isNotEqualTo(429);
        }
    }
}
