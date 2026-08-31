package com.enunas.backend.user.integration;

import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.user.EmailService;
import com.enunas.backend.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage for /auth/reset-password and /auth/change-password. Before this test class,
 * NEITHER endpoint had ANY test coverage in this codebase — not unit, not integration. (Contrast
 * /auth/set-password, which already had a real happy-path test in GoogleAuthIntegrationTest.) Same
 * class of gap as the brand-verification bug: code that reads correctly but was never actually
 * proven to work by execution. These tests drive the real HTTP endpoints end-to-end, including
 * logging in with the new password afterward to prove the change is real, not just that a 200 came
 * back.
 */
class AuthenticationPasswordFlowIntegrationTest extends AbstractDiscountIntegrationTest {

    @MockitoBean
    private EmailService emailService;

    // ===== /auth/reset-password =====

    @Test
    void resetPassword_withTheRealToken_succeeds_andTheNewPasswordWorksForLogin() {
        seedCustomer(); // customer@it.local / Customer123!
        rest.exchange("/auth/forgot-password", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "customer@it.local")), Void.class);
        String realToken = userRepository.findByEmail("customer@it.local").orElseThrow().getPasswordResetToken();
        assertThat(realToken).isNotNull();

        ResponseEntity<Void> resetResp = rest.exchange("/auth/reset-password", HttpMethod.POST,
                new HttpEntity<>(Map.of("token", realToken, "newPassword", "BrandNewPassword1")), Void.class);
        assertThat(resetResp.getStatusCode().value()).isEqualTo(200);

        assertThat(login("customer@it.local", "BrandNewPassword1")).isNotNull();
        ResponseEntity<Map> oldPasswordResp = rest.exchange("/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "customer@it.local", "password", "Customer123!")), Map.class);
        assertThat(oldPasswordResp.getStatusCode().value()).isEqualTo(401);

        User user = userRepository.findByEmail("customer@it.local").orElseThrow();
        assertThat(user.getPasswordResetToken()).isNull(); // single-use: cleared after success
        assertThat(user.getPasswordResetExpiresAt()).isNull();
    }

    @Test
    void resetPassword_withAnUnknownToken_isRejected() {
        seedCustomer();

        ResponseEntity<Map> resp = rest.exchange("/auth/reset-password", HttpMethod.POST,
                new HttpEntity<>(Map.of("token", "not-a-real-token", "newPassword", "BrandNewPassword1")), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(login("customer@it.local", "Customer123!")).isNotNull(); // original password untouched
    }

    @Test
    void resetPassword_withAnExpiredToken_isRejected_passwordUnchanged() {
        seedCustomer();
        rest.exchange("/auth/forgot-password", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "customer@it.local")), Void.class);
        String realToken = userRepository.findByEmail("customer@it.local").orElseThrow().getPasswordResetToken();
        // Backdate the expiry directly — same technique AbstractDiscountIntegrationTest uses to
        // avoid waiting out a real 15-minute window (see releaseAllPending).
        jdbc.update("UPDATE users SET password_reset_expires_at = ? WHERE email = ?",
                LocalDateTime.now().minusMinutes(1), "customer@it.local");

        ResponseEntity<Map> resp = rest.exchange("/auth/reset-password", HttpMethod.POST,
                new HttpEntity<>(Map.of("token", realToken, "newPassword", "BrandNewPassword1")), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(login("customer@it.local", "Customer123!")).isNotNull(); // original password untouched
    }

    // ===== /auth/change-password =====

    @Test
    void changePassword_withCorrectCurrentPassword_succeeds_andTheNewPasswordWorksForLogin() {
        seedCustomer(); // customer@it.local / Customer123!
        String token = login("customer@it.local", "Customer123!");

        ResponseEntity<Void> changeResp = rest.exchange("/auth/change-password", HttpMethod.POST,
                new HttpEntity<>(Map.of("currentPassword", "Customer123!", "newPassword", "BrandNewPassword1"),
                        auth(token)),
                Void.class);
        assertThat(changeResp.getStatusCode().value()).isEqualTo(200);

        assertThat(login("customer@it.local", "BrandNewPassword1")).isNotNull();
        ResponseEntity<Map> oldPasswordResp = rest.exchange("/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "customer@it.local", "password", "Customer123!")), Map.class);
        assertThat(oldPasswordResp.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void changePassword_withWrongCurrentPassword_isRejected_passwordUnchanged() {
        seedCustomer(); // customer@it.local / Customer123!
        String token = login("customer@it.local", "Customer123!");

        ResponseEntity<Map> resp = rest.exchange("/auth/change-password", HttpMethod.POST,
                new HttpEntity<>(Map.of("currentPassword", "TotallyWrongPassword1", "newPassword", "BrandNewPassword1"),
                        auth(token)),
                Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(login("customer@it.local", "Customer123!")).isNotNull(); // original password untouched
    }
}
