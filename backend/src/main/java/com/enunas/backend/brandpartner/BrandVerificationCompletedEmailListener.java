package com.enunas.backend.brandpartner;

import com.enunas.backend.user.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends the pending-approval email (to the applicant) and the approval-needed notification (to
 * the admin) AFTER the verification transaction commits. Best-effort: each send is independently
 * try/caught so one failing never blocks the other, and neither can propagate or roll back the
 * already-committed verification.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BrandVerificationCompletedEmailListener {

    private final EmailService emailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBrandVerificationCompleted(BrandVerificationCompletedEvent event) {
        try {
            emailService.sendPendingApprovalEmail(event.applicantEmail());
        } catch (Exception ex) {
            // EMAIL_DELIVERY_FAILURE — stable marker for a log-based alert (e.g. CloudWatch Logs
            // metric filter). Verification is already persisted; only the applicant notification failed.
            log.error("EMAIL_DELIVERY_FAILURE type=brand-pending-approval email={} reason={}",
                    event.applicantEmail(), ex.getMessage());
        }

        try {
            String subject = "New Brand Partner pending approval";
            String message = String.format("""
                    A new brand partner is awaiting admin approval.

                    Email: %s
                    User ID: %d

                    Approve via: POST /admin/brands/{brandId}/approve
                    """, event.applicantEmail(), event.applicantUserId());
            emailService.sendPlainTextEmail(event.adminEmail(), subject, message);
        } catch (Exception ex) {
            // EMAIL_DELIVERY_FAILURE — stable marker for a log-based alert (e.g. CloudWatch Logs
            // metric filter). A missed admin-notify email stalls this applicant's approval until
            // someone notices via other means (e.g. checking the admin dashboard directly).
            log.error("EMAIL_DELIVERY_FAILURE type=brand-admin-notify applicant={} reason={}",
                    event.applicantEmail(), ex.getMessage());
        }
    }
}
