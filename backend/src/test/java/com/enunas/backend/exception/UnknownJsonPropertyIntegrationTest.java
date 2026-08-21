package com.enunas.backend.exception;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * spring.jackson.deserialization.fail-on-unknown-properties=true (application.yaml) closes the gap
 * that misled the frontend into believing PATCH /brandpartner/me accepted "logoUrl": Spring Boot's
 * Jackson autoconfig disables this vanilla-Jackson default, so an unmodeled field was silently
 * dropped and the request still returned 200.
 */
class UnknownJsonPropertyIntegrationTest extends AbstractDiscountIntegrationTest {

    @Test
    void unknownField_rejectedWithFieldNameInMessage() {
        seedBrand("Acme", "acme", "0.15");
        String token = login("acme@it.local", "Brand123!");

        ResponseEntity<Map> resp = rest.exchange("/brandpartner/me", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("logoUrl", "https://example.com/x.png"), auth(token)), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat((String) resp.getBody().get("message")).contains("logoUrl");
    }

    /** Partial updates are the normal shape of this endpoint (see UpdateBrandPartnerDto: every
     *  field null = "leave unchanged") — the stricter Jackson setting must not require every field
     *  to be present, only that every field it DOES see is a real one. */
    @Test
    void validPartialUpdate_stillReturns200AndPersists() {
        seedBrand("Acme", "acme", "0.15");
        String token = login("acme@it.local", "Brand123!");

        ResponseEntity<Map> resp = rest.exchange("/brandpartner/me", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("description", "Handmade in Berlin"), auth(token)), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("description")).isEqualTo("Handmade in Berlin");

        BrandPartner reloaded = brandPartnerRepository.findByUser(userRepository.findByEmail("acme@it.local").orElseThrow()).orElseThrow();
        assertThat(reloaded.getDescription()).isEqualTo("Handmade in Berlin");
    }
}
