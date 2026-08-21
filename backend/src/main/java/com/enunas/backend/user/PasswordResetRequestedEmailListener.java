package com.enunas.backend.user;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the password-reset code AFTER the reset-token transaction commits. Best-effort: any
 * failure is logged and swallowed — it must never propagate or roll back the already-persisted
 * reset token, and must never turn a "forgot password" click into a 500.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PasswordResetRequestedEmailListener {

    private final EmailService emailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPasswordResetRequested(PasswordResetRequestedEvent event) {
        try {
            emailService.sendPasswordResetEmail(event.email(), event.code());
        } catch (Exception ex) {
            // EMAIL_DELIVERY_FAILURE — stable marker for a log-based alert (e.g. CloudWatch Logs
            // metric filter). This one deserves real attention, not just a log line: the reset token
            // is issued, the HTTP caller gets a 200, and the user has no way to know delivery failed
            // — they'll just never receive a code. A silent mail outage here surfaces as a wave of
            // "forgot password isn't working" support tickets, not an error anyone sees.
            log.error("EMAIL_DELIVERY_FAILURE type=password-reset email={} reason={}",
                    event.email(), ex.getMessage());
        }
    }
}
