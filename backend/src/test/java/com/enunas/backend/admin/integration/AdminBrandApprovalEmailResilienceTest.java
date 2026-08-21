package com.enunas.backend.admin.integration;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.user.EmailService;
import com.enunas.backend.user.Role;
import com.enunas.backend.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Regression + wiring coverage for AdminService.approveBrand() — used to call EmailService
 * directly/synchronously, so an SMTP failure would 500 and roll back an already-committed brand
 * approval (BrandStatus.ACTIVE + User.adminApproved = true), leaving the brand stuck un-approved
 * for a reason invisible to the admin who clicked approve. Now routed through BrandApprovedEvent,
 * AFTER_COMMIT, best-effort.
 */
class AdminBrandApprovalEmailResilienceTest extends AbstractDiscountIntegrationTest {

    @MockitoBean
    private EmailService emailService;

    private long seedPendingBrand(String email, String brandName, String slug) {
        User user = userRepository.save(User.builder()
                .email(email)
                .password(passwordEncoder.encode("Brand123!"))
                .role(Role.BRAND_PARTNER)
                .enabled(true)
                .adminApproved(false)
                .build());
        BrandPartner brand = brandPartnerRepository.save(BrandPartner.builder()
                .user(user)
                .brandName(brandName)
                .slug(slug)
                .build()); // status defaults to PENDING_REVIEW, approved defaults to false
        return brand.getId();
    }

    @Test
    void approveBrand_onSuccess_sendsAccountApprovedEmail() {
        seedAdmin();
        String adminToken = login("admin@it.local", "Admin123!");
        long brandId = seedPendingBrand("wired-brand@it.local", "Wired Brand", "wired-brand");

        rest.exchange("/admin/brands/" + brandId + "/approve", HttpMethod.POST,
                new HttpEntity<>(null, auth(adminToken)), Map.class);

        verify(emailService).sendAccountApprovedEmail(eq("wired-brand@it.local"));
    }

    @Test
    void approveBrand_succeedsEvenWhenEmailThrows() {
        seedAdmin();
        String adminToken = login("admin@it.local", "Admin123!");
        long brandId = seedPendingBrand("resilient-brand@it.local", "Resilient Brand", "resilient-brand");
        doThrow(new RuntimeException("smtp down")).when(emailService).sendAccountApprovedEmail(anyString());

        ResponseEntity<Map> resp = rest.exchange("/admin/brands/" + brandId + "/approve", HttpMethod.POST,
                new HttpEntity<>(null, auth(adminToken)), Map.class);

        assertThat(resp.getStatusCode().is2xxSuccessful()).as("approve: %s", resp.getBody()).isTrue(); // no rollback
        assertThat(brandPartnerRepository.findById(brandId).orElseThrow().isApproved()).isTrue();
        assertThat(userRepository.findByEmail("resilient-brand@it.local").orElseThrow().isAdminApproved()).isTrue();
    }
}
