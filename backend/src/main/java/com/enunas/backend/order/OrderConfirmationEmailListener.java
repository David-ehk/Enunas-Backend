package com.enunas.backend.order;

import com.enunas.backend.user.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.stream.Collectors;

/**
 * Confirms a captured payment to the customer. Best-effort: an SMTP failure is logged and
 * swallowed, same as {@link RefundCompletedEmailListener} / {@link ReturnApprovedEmailListener} —
 * it must never undo an already-committed PENDING → PAID transition.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderConfirmationEmailListener {

    private final EmailService emailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderConfirmed(OrderConfirmationEvent event) {
        try {
            String itemBlock = event.itemLines().stream().collect(Collectors.joining("\n"));
            String discountLine = event.discountAmount() != null && event.discountAmount().signum() > 0
                    ? "Rabatt: -" + event.discountAmount() + " " + event.currency() + "\n"
                    : "";
            String shippingLine = event.shippingBreakdown().isEmpty()
                    ? "Versand: " + event.shippingTotal() + " " + event.currency() + "\n"
                    : "Versand (" + event.shippingBreakdown().size() + " Marken): " + event.shippingTotal()
                            + " " + event.currency() + "\n" + event.shippingBreakdown().stream()
                            .collect(Collectors.joining("\n")) + "\n";

            String body = "Danke für deine Bestellung! Wir haben deine Zahlung erhalten.\n\n"
                    + "Bestellnummer: " + event.orderNumber() + "\n\n"
                    + "Bestellte Artikel:\n" + itemBlock + "\n\n"
                    + "Zwischensumme: " + event.subtotal() + " " + event.currency() + "\n"
                    + shippingLine
                    + discountLine
                    + "Gesamt: " + event.total() + " " + event.currency() + "\n\n"
                    + "Lieferadresse:\n" + event.shippingAddressBlock() + "\n\n"
                    + "Du kannst deine Bestellung hier einsehen:\n" + event.orderLink() + "\n\n"
                    + "Wir informieren dich per E-Mail, sobald deine Bestellung versendet wurde.";

            emailService.sendPlainTextEmail(
                    event.buyerEmail(),
                    "Bestellbestätigung " + event.orderNumber(),
                    body);

            log.info("Order confirmation email sent to {} for order {}",
                    event.buyerEmail(), event.orderNumber());
        } catch (Exception ex) {
            log.error("Best-effort order-confirmation email failed for order {} — payment already captured: {}",
                    event.orderNumber(), ex.getMessage());
        }
    }
}
