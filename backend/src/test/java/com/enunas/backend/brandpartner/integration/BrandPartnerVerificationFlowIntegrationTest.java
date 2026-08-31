package com.enunas.backend.brandpartner.integration;

import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.user.EmailService;
import com.enunas.backend.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the bug that shipped undetected for ~6 months: {@code applyForBrand()} set
 * {@code User.enabled = true} at apply time, so {@code verifyBrandApplicant()}'s own "already
 * verified" guard rejected every real applicant's first (and only) verify attempt. Every other
 * brand-verification test (BrandPartnerEmailResilienceTest, BrandPartnerEmailNormalizationTest)
 * bypasses {@code /brandpartner/apply} and hand-constructs the {@code User} row directly via the
 * repository — none of them would have caught this, because none of them ever go through the real
 * flow that was actually broken. This test does: apply -> read the real generated code back out of
 * the database (never exposed over HTTP) -> verify -> assert it actually succeeds.
 */
class BrandPartnerVerificationFlowIntegrationTest extends AbstractDiscountIntegrationTest {

    // No-ops the apply/verify emails (real SMTP is unreachable in the test environment) — this
    // class isn't testing email delivery, only the enabled/verification-code state machine.
    @MockitoBean
    private EmailService emailService;

    @Test
    void applyThenVerify_withTheRealGeneratedCode_succeeds() {
        Map<String, Object> body = baseApply();

        ResponseEntity<Map> applyResp = apply(body);
        assertThat(applyResp.getStatusCode().value()).as("apply: %s", applyResp.getBody()).isEqualTo(201);

        String email = (String) body.get("email");
        User applicant = userRepository.findByEmail(email).orElseThrow();
        // Precondition this whole test exists to pin down: enabled must be false immediately after
        // apply, or "verify" is a no-op that was never actually gated on anything.
        assertThat(applicant.isEnabled()).as("enabled right after apply, before verification").isFalse();
        String realCode = applicant.getVerificationCode();
        assertThat(realCode).isNotNull();

        ResponseEntity<String> verifyResp = rest.exchange("/brandpartner/verify", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email, "verificationCode", realCode)), String.class);

        assertThat(verifyResp.getStatusCode().value()).as("verify: %s", verifyResp.getBody()).isEqualTo(200);
        assertThat(userRepository.findByEmail(email).orElseThrow().isEnabled())
                .as("enabled after a correct verify").isTrue();
    }

    @Test
    void applyThenVerify_withAWrongCode_isRejected_andStaysUnverified() {
        Map<String, Object> body = baseApply();
        apply(body);
        String email = (String) body.get("email");

        ResponseEntity<String> verifyResp = rest.exchange("/brandpartner/verify", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email, "verificationCode", "000000")), String.class);

        assertThat(verifyResp.getStatusCode().value()).isEqualTo(400);
        assertThat(userRepository.findByEmail(email).orElseThrow().isEnabled()).isFalse();
    }

    /**
     * The other half of the same two-gate system, broken the other way: a May 2026 commit
     * (unrelated to this area — bundled into a SKU/ProductColor refactor) made AdminService.
     * approveBrand() also force User.enabled = true, so an admin approving an applicant who never
     * verified their email silently granted them a working login anyway. Reverted — approve only
     * ever flips adminApproved; enabled stays exactly what verifyBrandApplicant() last set it to.
     */
    @Test
    void adminApprovingBeforeVerification_doesNotEnableLogin() {
        seedAdmin();
        String adminToken = login("admin@it.local", "Admin123!");
        Map<String, Object> body = baseApply();
        ResponseEntity<Map> applyResp = apply(body);
        long brandId = ((Number) applyResp.getBody().get("id")).longValue();
        String email = (String) body.get("email");

        ResponseEntity<Map> approveResp = rest.exchange("/admin/brands/" + brandId + "/approve",
                HttpMethod.POST, new HttpEntity<>(auth(adminToken)), Map.class);
        assertThat(approveResp.getStatusCode().value()).as("approve: %s", approveResp.getBody()).isEqualTo(200);

        User afterApprove = userRepository.findByEmail(email).orElseThrow();
        assertThat(afterApprove.isAdminApproved()).as("adminApproved after approve").isTrue();
        assertThat(afterApprove.isEnabled()).as("enabled must stay false — never verified").isFalse();

        ResponseEntity<Map> loginResp = rest.postForEntity("/auth/login",
                Map.of("email", email, "password", body.get("password")), Map.class);
        assertThat(loginResp.getStatusCode().value()).as("login before verify: %s", loginResp.getBody())
                .isNotEqualTo(200);

        // Now actually verify — login must succeed only once BOTH gates are satisfied.
        String realCode = userRepository.findByEmail(email).orElseThrow().getVerificationCode();
        rest.exchange("/brandpartner/verify", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email, "verificationCode", realCode)), String.class);

        ResponseEntity<Map> loginAfterVerify = rest.postForEntity("/auth/login",
                Map.of("email", email, "password", body.get("password")), Map.class);
        assertThat(loginAfterVerify.getStatusCode().value())
                .as("login after both gates: %s", loginAfterVerify.getBody()).isEqualTo(200);
    }

    @Test
    void verifyingTwice_withTheSameCode_rejectsTheSecondAttempt() {
        // This is the guard's actual job once enabled correctly starts false: reject a REPEAT
        // verification, not reject the first (and only) legitimate one.
        Map<String, Object> body = baseApply();
        apply(body);
        String email = (String) body.get("email");
        String realCode = userRepository.findByEmail(email).orElseThrow().getVerificationCode();

        ResponseEntity<String> first = rest.exchange("/brandpartner/verify", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email, "verificationCode", realCode)), String.class);
        assertThat(first.getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> second = rest.exchange("/brandpartner/verify", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", email, "verificationCode", realCode)), String.class);
        assertThat(second.getStatusCode().value()).isEqualTo(409); // IllegalStateException("Email already verified")
    }

    private Map<String, Object> baseApply() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> body = new HashMap<>();
        body.put("email", "flow-" + unique + "@apply.local");
        body.put("password", "Brand123!");
        body.put("brandName", "FlowCheck " + unique);
        body.put("firstName", "Erika");
        body.put("lastName", "Mustermann");
        body.put("legalName", "FlowCheck GmbH");
        body.put("addressStreet", "Friedrichstr. 1");
        body.put("addressPostalCode", "10115");
        body.put("addressCity", "Berlin");
        body.put("addressCountry", "DE");
        return body;
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> apply(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/brandpartner/apply", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }
}
