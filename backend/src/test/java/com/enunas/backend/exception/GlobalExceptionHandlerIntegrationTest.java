package com.enunas.backend.exception;

import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GlobalExceptionHandler's {@code @ExceptionHandler(Exception.class)} catch-all must not swallow
 * Spring MVC's own routing exceptions (unmapped path -> NoResourceFoundException, wrong HTTP verb
 * on a real mapping -> HttpRequestMethodNotSupportedException) into a generic 500.
 *
 * Requests go out authenticated as a BRAND_PARTNER: the security filter chain matches
 * "/brandpartner/**" by URL pattern alone (before Spring MVC ever resolves a handler), so an
 * anonymous call would 401 before this bug could even surface.
 */
class GlobalExceptionHandlerIntegrationTest extends AbstractDiscountIntegrationTest {

    /** "/brandpartner/{id}" matches this path structurally, but the value isn't a valid Long —
     *  a malformed id is a bad request, distinct from the genuinely-unmapped-path 404 below. */
    @Test
    void nonNumericIdPathVariable_returns400NotInternalServerError() {
        seedBrand("Acme", "acme", "0.15");
        String token = login("acme@it.local", "Brand123!");

        ResponseEntity<Map> resp = rest.exchange(
                "/brandpartner/totally-made-up-path-xyz123", HttpMethod.GET,
                new HttpEntity<>(auth(token)), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void genuinelyUnmappedPath_returns404NotInternalServerError() {
        seedBrand("Acme", "acme", "0.15");
        String token = login("acme@it.local", "Brand123!");

        // Two segments after /brandpartner/ can't match any @RequestMapping (incl. the single-
        // segment /{id} lookup), so this is a true "no handler" case, not a path-variable
        // type-mismatch case.
        ResponseEntity<Map> resp = rest.exchange(
                "/brandpartner/totally-made-up-path-xyz123/sub", HttpMethod.GET,
                new HttpEntity<>(auth(token)), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void wrongHttpMethodOnRealMapping_returns405NotInternalServerError() {
        seedBrand("Acme", "acme", "0.15");
        String token = login("acme@it.local", "Brand123!");

        // /brandpartner/me only maps GET and PATCH.
        ResponseEntity<Map> resp = rest.exchange(
                "/brandpartner/me", HttpMethod.DELETE,
                new HttpEntity<>(auth(token)), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(405);
    }
}
