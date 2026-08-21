package com.enunas.backend.admin;

import com.enunas.backend.user.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the account-approved email AFTER the admin's approval transaction commits. Best-effort:
 * any failure is logged and swallowed — it must never propagate or roll back the already-committed
 * approval (the brand would otherwise stay stuck un-approved just because Gmail hiccuped).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BrandApprovedEmailListener {

    private final EmailService emailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBrandApproved(BrandApprovedEvent event) {
        try {
            emailService.sendAccountApprovedEmail(event.email());
        } catch (Exception ex) {
            // EMAIL_DELIVERY_FAILURE — stable marker for a log-based alert (e.g. CloudWatch Logs
            // metric filter). Approval is already persisted; only the notification email failed.
            log.error("EMAIL_DELIVERY_FAILURE type=brand-approved email={} reason={}",
                    event.email(), ex.getMessage());
        }
    }
}
