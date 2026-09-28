package com.enunas.backend.order;

import com.enunas.backend.user.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.stream.Collectors;

/**
 * Tells the customer which items were cancelled and refunded, and tells the brand which items not
 * to ship. Best-effort: an SMTP failure is logged and swallowed. The brand mail is ADVISORY — the
 * hard guarantee that a cancelled item never ships is OrderService's shipment guard (spec D17/D18).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderItemsCancelledEmailListener {

    private final EmailService emailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onItemsCancelled(OrderItemsCancelledEvent event) {
        String lines = event.itemDescriptions().stream().map(d -> "- " + d).collect(Collectors.joining("\n"));
        try {
            emailService.sendPlainTextEmail(
                    event.buyerEmail(),
                    "Artikel aus Bestellung " + event.orderNumber() + " storniert",
                    "Folgende Artikel von " + event.brandName() + " aus deiner Bestellung " + event.orderNumber()
                            + " wurden storniert:\n" + lines
                            + "\n\nWir erstatten dir " + event.refundAmount() + " " + event.currency()
                            + " auf dein ursprüngliches Zahlungsmittel. Das dauert je nach Bank 5–10 Werktage.");
        } catch (Exception ex) {
            log.error("EMAIL_DELIVERY_FAILURE type=items-cancelled-customer order={} reason={}",
                    event.orderNumber(), ex.getMessage());
        }
        try {
            emailService.sendPlainTextEmail(
                    event.brandEmail(),
                    "Stornierung: Bestellung " + event.orderNumber(),
                    "Bitte versende folgende Artikel der Bestellung " + event.orderNumber()
                            + " NICHT – sie wurden storniert und dem Kunden erstattet:\n" + lines);
        } catch (Exception ex) {
            log.error("EMAIL_DELIVERY_FAILURE type=items-cancelled-brand order={} reason={}",
                    event.orderNumber(), ex.getMessage());
        }
    }
}
