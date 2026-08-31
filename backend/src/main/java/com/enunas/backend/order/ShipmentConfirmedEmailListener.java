package com.enunas.backend.order;

import com.enunas.backend.user.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;
import java.util.List;

/**
 * Notifies the buyer that their order shipped, AFTER the shipment-confirmation transaction
 * commits. Best-effort: an SMTP failure is logged and swallowed, same as {@link
 * OrderConfirmationEmailListener} — it must never undo an already-committed PAID → SHIPPED
 * transition.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShipmentConfirmedEmailListener {

    private final EmailService emailService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onShipmentConfirmed(ShipmentConfirmedEvent event) {
        try {
            // Deliberately worded as a PART of the order, not the whole thing: on a multi-brand
            // order each brand ships its own parcel and triggers its own email, so "Deine
            // Bestellung ist unterwegs" would claim more than has actually shipped, and the
            // tracking number would only ever cover this one brand's parcel.
            //
            // A missing brandName drops the "von <Marke>" clause rather than substituting a
            // placeholder into it. brand_name is NOT NULL and Product.brand is a required
            // association, so this is defensive only — but the previous fallback read "die Artikel
            // von Deine Bestellung", which is not German, in the one situation nobody would be
            // watching for it.
            String brand = event.brandName();
            String subject = brand != null
                    ? "Teilsendung deiner Bestellung " + event.orderNumber() + " von " + brand + " ist unterwegs"
                    : "Teilsendung deiner Bestellung " + event.orderNumber() + " ist unterwegs";
            String intro = brand != null
                    ? "Ein Teil deiner Bestellung ist unterwegs — die Artikel von " + brand + "."
                    : "Ein Teil deiner Bestellung ist unterwegs.";

            String itemBlock = event.itemLines() == null || event.itemLines().isEmpty()
                    ? ""
                    : "\n\nIn dieser Sendung:\n" + String.join("\n", event.itemLines());
            String linkBlock = event.orderLink() == null || event.orderLink().isBlank()
                    ? ""
                    : "\n\nDu kannst deine Bestellung hier einsehen:\n" + event.orderLink();

            // The admin's order-wide SHIPPED override records a dispatch with no carrier and no
            // tracking number, so neither line can be assumed present — printing them unconditionally
            // would mail the customer the word "null" twice.
            List<String> dispatchLines = new ArrayList<>();
            if (event.carrier() != null && !event.carrier().isBlank()) {
                dispatchLines.add("Versanddienstleister: " + event.carrier());
            }
            if (event.trackingNumber() != null && !event.trackingNumber().isBlank()) {
                dispatchLines.add("Tracking-Nummer: " + event.trackingNumber());
            }
            if (dispatchLines.isEmpty()) {
                dispatchLines.add("Für diese Teilsendung liegt uns keine Tracking-Nummer vor.");
            }
            if (event.note() != null && !event.note().isBlank()) {
                dispatchLines.add("Hinweis: " + event.note());
            }

            String body = intro
                    + itemBlock
                    + "\n\n" + String.join("\n", dispatchLines)
                    + "\n\nBestellungen mit Artikeln mehrerer Marken werden getrennt verschickt —"
                    + " für jede Teilsendung erhältst du eine eigene E-Mail mit eigener Tracking-Nummer."
                    + linkBlock;

            emailService.sendPlainTextEmail(event.buyerEmail(), subject, body);

            log.info("Shipment confirmation email sent to {} for order {}", event.buyerEmail(), event.orderNumber());
        } catch (Exception ex) {
            // EMAIL_DELIVERY_FAILURE — stable marker for a log-based alert (e.g. CloudWatch Logs
            // metric filter). The shipment is already confirmed; only the buyer notification failed.
            log.error("EMAIL_DELIVERY_FAILURE type=shipment-confirmed order={} reason={}",
                    event.orderNumber(), ex.getMessage());
        }
    }
}
