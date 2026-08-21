package com.enunas.backend.brandpartner.integration;

import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.user.EmailService;
import com.enunas.backend.user.Role;
import com.enunas.backend.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * Regression + wiring coverage for BrandPartnerService.verifyBrandApplicant() and
 * resendVerificationCode() — both used to call EmailService directly/synchronously, so an SMTP
 * failure would 500 and roll back an already-valid verification. Now routed through
 * BrandVerificationCompletedEvent / (reused) BrandApplicationSubmittedEvent, both AFTER_COMMIT,
 * best-effort.
 *
 * <p>These tests persist a not-yet-verified applicant directly via the repository (same technique
 * as BrandPartnerEmailNormalizationTest) rather than driving through the real
 * {@code /brandpartner/apply} endpoint — that keeps the fixtures focused on verify/resend
 * email-wiring specifically, without needing a full valid apply payload. {@code User.enabled}
 * starts {@code false} until verified (BrandPartnerService.applyForBrand), matching what these
 * tests set up directly.
 */
class BrandPartnerEmailResilienceTest extends AbstractDiscountIntegrationTest {

    @MockitoBean
    private EmailService emailService;

    @Value("${admin.email}")
    private String adminEmail;

    private User seedUnverifiedApplicant(String email, String code) {
        return userRepository.save(User.builder()
                .email(email)
                .password(passwordEncoder.encode("Brand123!"))
                .role(Role.BRAND_PARTNER)
                .enabled(false)
                .adminApproved(false)
                .verificationCode(code)
                .verificationCodeExpiresAt(LocalDateTime.now().plusMinutes(15))
                .build());
    }

    @Test
    void verify_onSuccess_sendsApplicantAndAdminEmails() {
        User applicant = seedUnverifiedApplicant("verify-wired@it.local", "111111");

        ResponseEntity<String> resp = rest.exchange("/brandpartner/verify", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", applicant.getEmail(), "verificationCode", "111111")),
                String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(emailService).sendPendingApprovalEmail(eq(applicant.getEmail()));
        verify(emailService).sendPlainTextEmail(eq(adminEmail), anyString(), anyString());
    }

    @Test
    void verify_succeedsEvenWhenBothEmailsThrow() {
        User applicant = seedUnverifiedApplicant("verify-resilient@it.local", "222222");
        doThrow(new RuntimeException("smtp down")).when(emailService).sendPendingApprovalEmail(anyString());
        doThrow(new RuntimeException("smtp down")).when(emailService).sendPlainTextEmail(anyString(), anyString(), anyString());

        ResponseEntity<String> resp = rest.exchange("/brandpartner/verify", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", applicant.getEmail(), "verificationCode", "222222")),
                String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200); // no rollback
        assertThat(userRepository.findByEmail(applicant.getEmail()).orElseThrow().isEnabled()).isTrue();
    }

    @Test
    void resendVerificationCode_onSuccess_sendsVerificationEmailWithTheNewCode() {
        User applicant = seedUnverifiedApplicant("resend-wired@it.local", "333333");

        rest.exchange("/brandpartner/resend-verification?email=" + applicant.getEmail(), HttpMethod.POST,
                HttpEntity.EMPTY, String.class);

        String newCode = userRepository.findByEmail(applicant.getEmail()).orElseThrow().getVerificationCode();
        assertThat(newCode).isNotNull().isNotEqualTo("333333");
        verify(emailService).sendVerificationEmail(eq(applicant.getEmail()), eq(newCode));
    }

    @Test
    void resendVerificationCode_succeedsEvenWhenEmailThrows() {
        User applicant = seedUnverifiedApplicant("resend-resilient@it.local", "444444");
        doThrow(new RuntimeException("smtp down")).when(emailService).sendVerificationEmail(anyString(), anyString());

        ResponseEntity<String> resp = rest.exchange("/brandpartner/resend-verification?email=" + applicant.getEmail(),
                HttpMethod.POST, HttpEntity.EMPTY, String.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200); // no rollback
        String newCode = userRepository.findByEmail(applicant.getEmail()).orElseThrow().getVerificationCode();
        assertThat(newCode).isNotNull().isNotEqualTo("444444"); // code still rotated despite the email failure
    }
}
