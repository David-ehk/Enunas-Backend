package com.enunas.backend.brandpartner;

import com.enunas.backend.user.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the brand applicant's verification email AFTER the application transaction commits.
 * Best-effort: any failure is logged and swallowed — it must never propagate or roll back the
 * already-committed application. Onboarding therefore never depends on email delivery.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BrandApplicationEmailListener {

    private final EmailService emailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBrandApplicationSubmitted(BrandApplicationSubmittedEvent event) {
        try {
            emailService.sendVerificationEmail(event.email(), event.verificationCode());
        } catch (Exception ex) {
            // EMAIL_DELIVERY_FAILURE — stable marker for a log-based alert (e.g. CloudWatch Logs
            // metric filter). The application/resend is already persisted; only the code delivery failed
            // — and unlike the other best-effort emails, this one blocks the applicant's own next step
            // (they have no code to enter) until they use resend-verification again.
            log.error("EMAIL_DELIVERY_FAILURE type=brand-verification-code email={} reason={}",
                    event.email(), ex.getMessage());
        }
    }
}
