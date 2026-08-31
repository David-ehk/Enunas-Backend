package com.enunas.backend.user.integration;

import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.user.Role;
import com.enunas.backend.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * POST /auth/login for a disabled (e.g. email-unverified) BRAND_PARTNER account used to 500.
 * Spring Security's DaoAuthenticationProvider ran its own default account-status pre-check BEFORE
 * password verification, threw DisabledException for user.isEnabled() == false, and
 * GlobalExceptionHandler had no handler for that type — falling through to the generic 500, and
 * pre-empting AuthenticationService.assertAccountActive's own role-aware message, which never got
 * a chance to run. Fixed by disabling that pre-check (ApplicationConfiguration.authenticationProvider)
 * so assertAccountActive is what actually decides.
 */
class DisabledAccountLoginIntegrationTest extends AbstractDiscountIntegrationTest {

    @Test
    void login_disabledBrandPartner_returnsClearMessageNot500() {
        userRepository.save(User.builder()
                .email("unverified@it.local")
                .password(passwordEncoder.encode("Brand123!"))
                .role(Role.BRAND_PARTNER)
                .enabled(false)
                .adminApproved(false)
                .build());

        ResponseEntity<Map> resp = rest.postForEntity("/auth/login",
                Map.of("email", "unverified@it.local", "password", "Brand123!"), Map.class);

        assertThat(resp.getStatusCode().value()).isNotEqualTo(500);
        assertThat((String) resp.getBody().get("message")).containsIgnoringCase("verify your email");
    }

    /** A wrong password on a disabled account must still fail as a credentials problem, not
     *  silently authenticate just because the account-status pre-check was disabled. */
    @Test
    void login_disabledBrandPartner_wrongPassword_stillRejected() {
        userRepository.save(User.builder()
                .email("unverified2@it.local")
                .password(passwordEncoder.encode("Brand123!"))
                .role(Role.BRAND_PARTNER)
                .enabled(false)
                .adminApproved(false)
                .build());

        ResponseEntity<Map> resp = rest.postForEntity("/auth/login",
                Map.of("email", "unverified2@it.local", "password", "WrongPassword!"), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }
}
