package com.enunas.backend.user;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Confirms an account erasure to the address the account used to have. Best-effort and
 * AFTER_COMMIT, same contract as every other listener here: the erasure is already durable and
 * irreversible, so an SMTP failure must not roll it back — there would be nothing to roll back
 * to. A failure here means the customer was not told; it does not mean their data survived.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountErasedEmailListener {

    private final EmailService emailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountErased(AccountErasedEvent event) {
        try {
            emailService.sendPlainTextEmail(
                    event.formerEmail(),
                    "Dein Enunas-Konto wurde gelöscht",
                    "Dein Konto und deine Profildaten wurden gemäß Art. 17 DSGVO gelöscht.\n\n"
                    + "Du kannst dich mit dieser Adresse nicht mehr anmelden. Falls du Enunas später"
                    + " wieder nutzen möchtest, kannst du jederzeit ein neues Konto anlegen.\n\n"
                    + "Deine Bestellhistorie bleibt aus gesetzlichen Gründen gespeichert: Handels-"
                    + " und steuerrechtlich sind wir verpflichtet, Rechnungs- und Bestelldaten zehn"
                    + " Jahre aufzubewahren (§257 HGB, §147 AO). Diese Daten werden ausschließlich"
                    + " zu diesem Zweck vorgehalten und nach Ablauf der Frist gelöscht.");
            log.info("Account erasure confirmation sent");
        } catch (Exception ex) {
            // EMAIL_DELIVERY_FAILURE — stable marker for a log-based alert. Deliberately without
            // the address: it belongs to an account we have just been asked to erase.
            log.error("EMAIL_DELIVERY_FAILURE type=account-erased reason={}", ex.getMessage());
        }
    }
}
