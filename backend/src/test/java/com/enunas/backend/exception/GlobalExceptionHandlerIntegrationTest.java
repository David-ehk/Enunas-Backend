package com.enunas.backend.exception;

import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
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

    /**
     * GET /users is {@code @PreAuthorize("hasRole('ADMIN')")}. Spring Security 6+'s
     * AuthorizationDeniedException (thrown by that check) extends the older AccessDeniedException —
     * a completely different type from java.lang.SecurityException (already handled above) — and
     * had no handler here, falling through to the generic Exception.class catch-all as 500.
     */
    @Test
    void preAuthorizeDenial_returns403NotInternalServerError() {
        seedCustomer();
        String token = login("customer@it.local", "Customer123!");

        ResponseEntity<Map> resp = rest.exchange(
                "/users", HttpMethod.GET, new HttpEntity<>(auth(token)), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(403);
    }

    /**
     * GET /products/search declares {@code @RequestParam String keyword} with no default, so
     * omitting it throws MissingServletRequestParameterException. That type had no handler and hit
     * the Exception.class catch-all as 500 — on a permitAll storefront route, so any crawler could
     * trigger it. ResponseEntityExceptionHandler now maps it, along with the rest of the Spring MVC
     * exception family.
     */
    @Test
    void missingRequiredRequestParam_returns400NotInternalServerError() {
        ResponseEntity<Map> resp = rest.getForEntity("/products/search", Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    /**
     * A body posted without a Content-Type Spring can read raises
     * HttpMediaTypeNotSupportedException — 415, not 500 — and RFC 9110 requires the 415 to name the
     * types that would have worked.
     */
    @Test
    void unsupportedContentType_returns415WithAcceptHeader() {
        seedCustomer();
        String token = login("customer@it.local", "Customer123!");

        HttpHeaders headers = auth(token);
        headers.setContentType(MediaType.TEXT_PLAIN);

        ResponseEntity<Map> resp = rest.exchange(
                "/orders", HttpMethod.POST, new HttpEntity<>("not json", headers), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(415);
        assertThat(resp.getHeaders().getAccept()).contains(MediaType.APPLICATION_JSON);
    }

    /** RFC 9110 makes Allow mandatory on a 405; hand-rolled handlers dropped it. */
    @Test
    void methodNotAllowed_carriesAllowHeader() {
        seedBrand("Acme", "acme", "0.15");
        String token = login("acme@it.local", "Brand123!");

        ResponseEntity<Map> resp = rest.exchange(
                "/brandpartner/me", HttpMethod.DELETE,
                new HttpEntity<>(auth(token)), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(405);
        assertThat(resp.getHeaders().getAllow()).contains(HttpMethod.GET);
    }

    /**
     * An unknown enum constant in a path variable must read like the same mistake made in a JSON
     * body: the rejected value plus the constants that would have been accepted.
     */
    @Test
    void unknownEnumPathVariable_returns400ListingAllowedValues() {
        seedAdmin();
        String token = login("admin@it.local", "Admin123!");

        ResponseEntity<Map> resp = rest.exchange(
                "/admin/orders/status/NOT_A_STATUS", HttpMethod.GET,
                new HttpEntity<>(auth(token)), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat((String) resp.getBody().get("message"))
                .contains("NOT_A_STATUS")
                .contains("Allowed values")
                .contains("PENDING");
    }

    /**
     * A URL-rule denial (CUSTOMER hitting "/admin/**") is raised by the AuthorizationFilter, inside
     * the security filter chain, where no DispatcherServlet and therefore no @ControllerAdvice
     * exists. Spring Security's default AccessDeniedHandler answered it with the right status and
     * an empty body, so this one class of 403 broke the envelope every other error keeps.
     */
    @Test
    void urlRuleDenial_returns403WithTheSameErrorEnvelope() {
        seedCustomer();
        String token = login("customer@it.local", "Customer123!");

        ResponseEntity<Map> resp = rest.exchange(
                "/admin/orders", HttpMethod.GET, new HttpEntity<>(auth(token)), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(403);
        assertThat(resp.getBody())
                .containsEntry("status", 403)
                .containsEntry("error", "Forbidden")
                .containsEntry("message", "Access denied")
                .containsEntry("path", "/admin/orders")
                .containsKey("timestamp");
    }

    /**
     * No credentials at all is a different failure from wrong-role, and RFC 9110 gives it a
     * different status: 401 plus a WWW-Authenticate challenge. Spring Security's default entry
     * point returned a bodyless 403 for this, conflating "who are you?" with "you may not".
     */
    @Test
    void anonymousRequestToProtectedRoute_returns401WithChallengeAndEnvelope() {
        ResponseEntity<Map> resp = rest.exchange(
                "/admin/orders", HttpMethod.GET, HttpEntity.EMPTY, Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
        assertThat(resp.getBody())
                .containsEntry("status", 401)
                .containsEntry("message", "Authentication required")
                .containsEntry("path", "/admin/orders");
    }

    /**
     * An expired/garbage token is answered by JwtAuthenticationFilter, which routes through the
     * same resolver — it must stay 401 and keep its own message rather than being flattened into
     * the entry point's generic one.
     */
    @Test
    void malformedToken_returns401FromTheJwtFilter() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("not.a.jwt");

        ResponseEntity<Map> resp = rest.exchange(
                "/admin/orders", HttpMethod.GET, new HttpEntity<>(headers), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
        assertThat((String) resp.getBody().get("message")).contains("Token is invalid or expired");
    }

    /** The error envelope carries the offending path, for both hand-written and inherited handlers. */
    @Test
    void errorBodyCarriesRequestPath() {
        seedCustomer();
        String token = login("customer@it.local", "Customer123!");

        ResponseEntity<Map> fromOwnHandler = rest.exchange(
                "/users", HttpMethod.GET, new HttpEntity<>(auth(token)), Map.class);
        assertThat(fromOwnHandler.getBody()).containsEntry("path", "/users");

        ResponseEntity<Map> fromInheritedHandler = rest.getForEntity("/products/search", Map.class);
        assertThat(fromInheritedHandler.getBody()).containsEntry("path", "/products/search");
    }
}
