package com.enunas.backend.user.integration;

import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.user.EmailService;
import com.enunas.backend.user.Role;
import com.enunas.backend.user.User;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoogleAuthIntegrationTest extends AbstractDiscountIntegrationTest {

    @MockitoBean
    private GoogleIdTokenVerifier googleIdTokenVerifier;

    // Real SMTP is unreachable in the test environment; loginWithGoogle publishes a best-effort,
    // AFTER_COMMIT welcome email for new signups (WelcomeEmailListener), so it's mocked to keep
    // tests fast/quiet (same pattern as MultiBrandReturnTest, ReturnLifecyclePhase3Test,
    // Vat22fComplianceTest) — not because a real failure here would break anything anymore.
    @MockitoBean
    private EmailService emailService;

    @Test
    void googleSignIn_newUser_createsCustomerAccount() throws Exception {
        stubGoogleToken("fake-token-1", "sub-1", "brandnew@example.com", true, "Brand", "New");

        ResponseEntity<Map> resp = rest.exchange("/auth/google", HttpMethod.POST,
                new HttpEntity<>(Map.of("idToken", "fake-token-1")), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("token")).isNotNull();
        var created = userRepository.findByEmail("brandnew@example.com").orElseThrow();
        assertThat(created.getRole()).isEqualTo(Role.CUSTOMER);
        assertThat(created.getPassword()).isNull();
        assertThat(created.isEnabled()).isTrue();
    }

    /** Wiring proof, not just failure-swallowing: a broken/missing event publish would also make
     *  the "throws" test below pass (for the wrong reason — the mocked throw simply never fires). */
    @Test
    void googleSignIn_newUser_onSuccess_sendsWelcomeEmail() throws Exception {
        stubGoogleToken("fake-token-1c", "sub-1c", "wired@example.com", true, "Wired", "User");

        rest.exchange("/auth/google", HttpMethod.POST,
                new HttpEntity<>(Map.of("idToken", "fake-token-1c")), Map.class);

        verify(emailService).sendWelcomeEmail(org.mockito.ArgumentMatchers.eq("wired@example.com"),
                org.mockito.ArgumentMatchers.eq("wired@example.com"));
    }

    /** Regression: welcome email must be best-effort (AFTER_COMMIT) — an SMTP failure must never
     *  turn a successful Google signup into a 500 or roll back the newly-created account. */
    @Test
    void googleSignIn_newUser_succeedsEvenWhenWelcomeEmailThrows() throws Exception {
        org.mockito.Mockito.doThrow(new RuntimeException("smtp down"))
                .when(emailService).sendWelcomeEmail(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
        stubGoogleToken("fake-token-1b", "sub-1b", "resilient@example.com", true, "Resilient", "User");

        ResponseEntity<Map> resp = rest.exchange("/auth/google", HttpMethod.POST,
                new HttpEntity<>(Map.of("idToken", "fake-token-1b")), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200); // no rollback
        assertThat(resp.getBody().get("token")).isNotNull();
        assertThat(userRepository.findByEmail("resilient@example.com")).isPresent();
    }

    @Test
    void googleSignIn_existingPasswordUser_linksAccount_noDuplicate() throws Exception {
        seedCustomer(); // customer@it.local / Customer123!
        stubGoogleToken("fake-token-2", "sub-2", "customer@it.local", true, "Existing", "Customer");

        ResponseEntity<Map> resp = rest.exchange("/auth/google", HttpMethod.POST,
                new HttpEntity<>(Map.of("idToken", "fake-token-2")), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Integer userCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, "customer@it.local");
        assertThat(userCount).isEqualTo(1);
        Integer oauthCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM oauth_accounts WHERE provider_user_id = ?", Integer.class, "sub-2");
        assertThat(oauthCount).isEqualTo(1);
    }

    @Test
    void googleSignIn_returningGoogleIdentity_logsIn_noDuplicate() throws Exception {
        stubGoogleToken("fake-token-3", "sub-3", "returning@example.com", true, "Returning", "User");
        rest.exchange("/auth/google", HttpMethod.POST, new HttpEntity<>(Map.of("idToken", "fake-token-3")), Map.class);

        stubGoogleToken("fake-token-3b", "sub-3", "returning@example.com", true, "Returning", "User");
        ResponseEntity<Map> resp = rest.exchange("/auth/google", HttpMethod.POST,
                new HttpEntity<>(Map.of("idToken", "fake-token-3b")), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Integer userCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, "returning@example.com");
        assertThat(userCount).isEqualTo(1);
    }

    @Test
    void setPassword_thenPasswordLoginWorks() throws Exception {
        stubGoogleToken("fake-token-4", "sub-4", "setpw@example.com", true, "Set", "Pw");
        ResponseEntity<Map> googleResp = rest.exchange("/auth/google", HttpMethod.POST,
                new HttpEntity<>(Map.of("idToken", "fake-token-4")), Map.class);
        String googleToken = (String) googleResp.getBody().get("token");

        ResponseEntity<Void> setPwResp = rest.exchange("/auth/set-password", HttpMethod.POST,
                new HttpEntity<>(Map.of("newPassword", "brandNewPassword1"), auth(googleToken)), Void.class);
        assertThat(setPwResp.getStatusCode().value()).isEqualTo(200);

        String loginToken = login("setpw@example.com", "brandNewPassword1");
        assertThat(loginToken).isNotNull();
    }

    @Test
    void passwordLogin_googleOnlyAccount_failsCleanlyWith401() throws Exception {
        stubGoogleToken("fake-token-5", "sub-5", "googleonly@example.com", true, "Google", "Only");
        rest.exchange("/auth/google", HttpMethod.POST, new HttpEntity<>(Map.of("idToken", "fake-token-5")), Map.class);

        ResponseEntity<Map> resp = rest.exchange("/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "googleonly@example.com", "password", "whatever123")), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    /**
     * C1 — the brand-partner approval workflow must not be bypassable via Google Sign-In.
     * POST /brandpartner/apply leaves enabled=true, adminApproved=false; password login refuses it,
     * so /auth/google must refuse it too instead of minting a BRAND_PARTNER JWT.
     */
    @Test
    void googleSignIn_brandPartnerPendingAdminApproval_isRefused_noToken_noLink() throws Exception {
        userRepository.save(User.builder()
                .email("pending-brand@it.local")
                .password(passwordEncoder.encode("Brand123!"))
                .role(Role.BRAND_PARTNER)
                .enabled(true)
                .adminApproved(false)
                .build());
        stubGoogleToken("fake-token-6", "sub-6", "pending-brand@it.local", true, "Pending", "Brand");

        ResponseEntity<Map> resp = rest.exchange("/auth/google", HttpMethod.POST,
                new HttpEntity<>(Map.of("idToken", "fake-token-6")), Map.class);

        // IllegalStateException -> 409 via GlobalExceptionHandler, same class login() throws.
        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody().get("token")).isNull();
        Integer oauthCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM oauth_accounts WHERE provider_user_id = ?", Integer.class, "sub-6");
        assertThat(oauthCount).isZero();
    }

    /** C1 — an operator-disabled account must not regain access through Google. */
    @Test
    void googleSignIn_disabledAccount_isRefused() throws Exception {
        userRepository.save(User.builder()
                .email("banned@it.local")
                .password(passwordEncoder.encode("Customer123!"))
                .role(Role.CUSTOMER)
                .enabled(false)
                .adminApproved(true)
                .build());
        stubGoogleToken("fake-token-7", "sub-7", "banned@it.local", true, "Banned", "User");

        ResponseEntity<Map> resp = rest.exchange("/auth/google", HttpMethod.POST,
                new HttpEntity<>(Map.of("idToken", "fake-token-7")), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody().get("token")).isNull();
    }

    /** C2 — an unverified Google email may not create a brand-new account either. */
    @Test
    void googleSignIn_unverifiedEmail_noExistingUser_isRefused_createsNothing() throws Exception {
        stubGoogleToken("fake-token-8", "sub-8", "unverified-new@example.com", false, "Un", "Verified");

        ResponseEntity<Map> resp = rest.exchange("/auth/google", HttpMethod.POST,
                new HttpEntity<>(Map.of("idToken", "fake-token-8")), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(userRepository.findByEmail("unverified-new@example.com")).isEmpty();
    }

    /** I3 — /auth/set-password must be rejected by Spring Security before the controller NPEs.
     *  401, not 403: the caller sent no credentials at all, so the honest answer is "who are you?"
     *  rather than "you may not". Spring Security's default entry point answered 403 for anonymous
     *  callers too; SecurityConfiguration now distinguishes the two. */
    @Test
    void setPassword_unauthenticated_isRejectedBySecurity_notA500() {
        ResponseEntity<Map> resp = rest.exchange("/auth/set-password", HttpMethod.POST,
                new HttpEntity<>(Map.of("newPassword", "brandNewPassword1")), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    /** I3 — the same defense applies to the pre-existing /auth/change-password. */
    @Test
    void changePassword_unauthenticated_isRejectedBySecurity_notA500() {
        ResponseEntity<Map> resp = rest.exchange("/auth/change-password", HttpMethod.POST,
                new HttpEntity<>(Map.of("currentPassword", "oldPassword1", "newPassword", "newPassword1")),
                Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    /** I3 — the blanket rule must NOT have broken unauthenticated login/signup. */
    @Test
    void otherAuthRoutes_remainPubliclyReachable() {
        seedCustomer();

        assertThat(login("customer@it.local", "Customer123!")).isNotNull();

        ResponseEntity<Map> signupResp = rest.exchange("/auth/signup", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "fresh-signup@it.local", "password", "Password123!")), Map.class);
        assertThat(signupResp.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<Void> forgotResp = rest.exchange("/auth/forgot-password", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "customer@it.local")), Void.class);
        assertThat(forgotResp.getStatusCode().value()).isEqualTo(200);
    }

    /** I4 — a password signup at a differently-cased email must not duplicate a Google account. */
    @Test
    void signup_afterGoogleAccountAtSameEmail_differentCasing_isRejectedAsDuplicate() throws Exception {
        stubGoogleToken("fake-token-9", "sub-9", "dupe@example.com", true, "Dupe", "User");
        rest.exchange("/auth/google", HttpMethod.POST,
                new HttpEntity<>(Map.of("idToken", "fake-token-9")), Map.class);

        ResponseEntity<Map> resp = rest.exchange("/auth/signup", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "Dupe@Example.com", "password", "Password123!")), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        Integer userCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE lower(email) = ?", Integer.class, "dupe@example.com");
        assertThat(userCount).isEqualTo(1);
    }

    private void stubGoogleToken(String tokenString, String subject, String email, boolean emailVerified,
                                  String firstName, String lastName) throws Exception {
        GoogleIdToken idToken = mock(GoogleIdToken.class);
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setSubject(subject);
        payload.setEmail(email);
        payload.setEmailVerified(emailVerified);
        payload.put("given_name", firstName);
        payload.put("family_name", lastName);
        when(idToken.getPayload()).thenReturn(payload);
        when(googleIdTokenVerifier.verify(tokenString)).thenReturn(idToken);
    }
}
