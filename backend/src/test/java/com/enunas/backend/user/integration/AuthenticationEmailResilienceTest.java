package com.enunas.backend.user.integration;

import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.user.EmailService;
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
 * Regression coverage for the production incident where POST /auth/signup 500'd on every attempt
 * because the SMTP credentials Gmail rejected turned into an uncaught exception inside the
 * signup transaction (AuthenticationService.signup -> emailService.sendWelcomeEmail, synchronous,
 * no try/catch). Welcome/password-reset emails are now best-effort, AFTER_COMMIT events
 * (WelcomeEmailListener / PasswordResetRequestedEmailListener) — same pattern already proven by
 * BrandApplicationEmailListener (see Vat22fComplianceTest#onboarding_succeedsEvenWhenVerificationEmailThrows).
 *
 * <p>Each flow gets TWO tests, not one: a failure-swallowed test alone would pass even if the
 * event/listener wiring were silently broken (the mocked "throw" simply never fires, and the
 * request still returns 200 — for the wrong reason). The success-path test with {@code verify(...)}
 * is what actually proves the listener runs at all.
 */
class AuthenticationEmailResilienceTest extends AbstractDiscountIntegrationTest {

    @MockitoBean
    private EmailService emailService;

    @Test
    void signup_onSuccess_sendsWelcomeEmail() {
        rest.exchange("/auth/signup", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "wired-signup@it.local", "password", "Password123!")),
                Map.class);

        // AFTER_COMMIT listeners run synchronously on the same thread right after commit — by the
        // time exchange() returns, WelcomeEmailListener has already run (no @Async on it).
        verify(emailService).sendWelcomeEmail(eq("wired-signup@it.local"), eq("wired-signup@it.local"));
    }

    @Test
    void signup_succeedsEvenWhenWelcomeEmailThrows() {
        doThrow(new RuntimeException("smtp down")).when(emailService).sendWelcomeEmail(anyString(), anyString());

        ResponseEntity<Map> resp = rest.exchange("/auth/signup", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "resilient-signup@it.local", "password", "Password123!")),
                Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200); // no rollback
        var user = userRepository.findByEmail("resilient-signup@it.local");
        assertThat(user).isPresent();
        // existsByUser(user), not findAll().anyMatch(c -> c.getUser()...): Customer.user is a lazy
        // association, and findAll() returns proxies outside any open Hibernate session by the time
        // this assertion runs (LazyInitializationException) — existsByUser() stays a DB-side query.
        assertThat(customerRepository.existsByUser(user.get())).isTrue();
    }

    @Test
    void forgotPassword_onSuccess_sendsResetEmailWithThePersistedCode() {
        seedCustomer(); // customer@it.local

        rest.exchange("/auth/forgot-password", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "customer@it.local")), Void.class);

        String persistedToken = userRepository.findByEmail("customer@it.local").orElseThrow().getPasswordResetToken();
        assertThat(persistedToken).isNotNull();
        verify(emailService).sendPasswordResetEmail(eq("customer@it.local"), eq(persistedToken));
    }

    @Test
    void forgotPassword_succeedsEvenWhenEmailThrows() {
        seedCustomer(); // customer@it.local

        doThrow(new RuntimeException("smtp down")).when(emailService).sendPasswordResetEmail(anyString(), anyString());

        ResponseEntity<Void> resp = rest.exchange("/auth/forgot-password", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "customer@it.local")), Void.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200); // no rollback
        var user = userRepository.findByEmail("customer@it.local").orElseThrow();
        assertThat(user.getPasswordResetToken()).isNotNull(); // token write persisted despite the email failure
    }
}
