package com.enunas.backend.brandpartner.integration;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.BrandStatus;
import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.user.Role;
import com.enunas.backend.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /brands/{id}/public-profile} used to not exist: the only by-ID brand lookup was
 * {@code GET /brandpartner/{id}}, locked to {@code BRAND_PARTNER}/{@code ADMIN} at the security
 * filter chain, so an anonymous storefront visitor got 401 with no public path to a brand's
 * profile at all. This endpoint fills that gap with its own narrow, storefront-safe DTO.
 */
class BrandPublicProfileIntegrationTest extends AbstractDiscountIntegrationTest {

    @Test
    void activeBrand_publicProfileIsReachableAnonymously() {
        long brandId = seedActiveBrand().getId();

        @SuppressWarnings("unchecked")
        ResponseEntity<Map> response =
                rest.getForEntity("/brands/{id}/public-profile", Map.class, brandId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(((Number) body.get("id")).longValue()).isEqualTo(brandId);
        assertThat(body.get("brandName")).isEqualTo("Alpha");
        assertThat(body.get("slug")).isEqualTo("alpha");
    }

    @Test
    void activeBrand_publicProfileNeverExposesSensitiveFields() {
        long brandId = seedActiveBrand().getId();

        @SuppressWarnings("unchecked")
        ResponseEntity<Map> response =
                rest.getForEntity("/brands/{id}/public-profile", Map.class, brandId);

        Map<String, Object> body = response.getBody();
        assertThat(body).doesNotContainKeys(
                "vatId", "taxNumber", "legalName", "contactEmail", "userEmail", "userId",
                "addressStreet", "addressPostalCode", "addressCity", "addressCountry",
                "returnStreet", "returnPostalCode", "returnCity", "returnCountry",
                "returnRecipient", "returnInstructions", "effectiveReturnAddress",
                "approved", "status");
    }

    /** A brand still in onboarding must not be reachable by ID — same as if it didn't exist. */
    @Test
    void pendingReviewBrand_publicProfileIsNotFound() {
        User u = seedUser("pending@it.local", "Brand123!", Role.BRAND_PARTNER);
        BrandPartner brand = brandPartnerRepository.save(
                BrandPartner.builder().user(u).brandName("Pending Co").slug("pending-co").build());
        assertThat(brand.getStatus()).isEqualTo(BrandStatus.PENDING_REVIEW);

        ResponseEntity<String> response =
                rest.getForEntity("/brands/{id}/public-profile", String.class, brand.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void nonexistentBrand_publicProfileIsNotFound() {
        ResponseEntity<String> response =
                rest.getForEntity("/brands/{id}/public-profile", String.class, 999_999L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** Regression guard: the original bug report — the authenticated-only lookup is untouched. */
    @Test
    void oldFullProfileEndpoint_stillRejectsAnonymousCallers() {
        long brandId = seedActiveBrand().getId();

        ResponseEntity<String> response =
                rest.getForEntity("/brandpartner/{id}", String.class, brandId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private BrandPartner seedActiveBrand() {
        return seedBrand("Alpha", "alpha", "0.15").brand();
    }
}
