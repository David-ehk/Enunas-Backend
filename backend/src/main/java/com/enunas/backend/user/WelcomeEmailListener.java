package com.enunas.backend.user;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the new-account welcome email AFTER the signup transaction commits. Best-effort: any
 * failure is logged and swallowed — it must never propagate or roll back the already-committed
 * account. Signup/Google sign-up therefore never depend on email delivery. Same shape as {@link
 * com.enunas.backend.brandpartner.BrandApplicationEmailListener}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WelcomeEmailListener {

    private final EmailService emailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onWelcomeEmail(WelcomeEmailEvent event) {
        try {
            emailService.sendWelcomeEmail(event.email(), event.email());
        } catch (Exception ex) {
            // EMAIL_DELIVERY_FAILURE — stable marker for a log-based alert (e.g. CloudWatch Logs
            // metric filter). The account is already persisted; only the welcome email failed.
            log.error("EMAIL_DELIVERY_FAILURE type=welcome email={} reason={}",
                    event.email(), ex.getMessage());
        }
    }
}
