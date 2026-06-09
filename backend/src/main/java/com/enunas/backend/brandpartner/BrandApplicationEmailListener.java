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
            log.error("Best-effort verification email failed for {} — application already persisted: {}",
                    event.email(), ex.getMessage());
        }
    }
}
