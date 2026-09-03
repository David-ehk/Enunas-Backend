package com.enunas.backend.order;

import com.enunas.backend.user.EmailService;
import com.enunas.backend.user.EmailTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.util.HtmlUtils;

import java.util.stream.Collectors;

/**
 * Notifies the buyer that their order shipped, AFTER the shipment-confirmation transaction
 * commits. Best-effort: an SMTP failure is logged and swallowed, same as {@link
 * OrderConfirmationEmailListener} — it must never undo an already-committed PAID → SHIPPED
 * transition.
 *
 * <p>No delivery-address block here, unlike the order-confirmation mail's — {@link
 * ShipmentConfirmedEvent} doesn't carry one, and adding it is a separate change from the HTML
 * redesign this listener went through. The CTA links to the order page (the only link this event
 * actually has), not a carrier tracking page we don't have a real URL for.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShipmentConfirmedEmailListener {

    private final EmailService emailService;

    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onShipmentConfirmed(ShipmentConfirmedEvent event) {
        try {
            // Deliberately worded as a PART of the order, not the whole thing: on a multi-brand
            // order each brand ships its own parcel and triggers its own email, so "Deine
            // Bestellung ist unterwegs" would claim more than has actually shipped, and the
            // tracking number would only ever cover this one brand's parcel.
            String brand = event.brandName();
            String subject = brand != null
                    ? "Teilsendung deiner Bestellung " + event.orderNumber() + " von " + brand + " ist unterwegs"
                    : "Teilsendung deiner Bestellung " + event.orderNumber() + " ist unterwegs";
            String intro = brand != null
                    ? "Ein Teil deiner Bestellung ist unterwegs — die Artikel von <strong>"
                            + HtmlUtils.htmlEscape(brand) + "</strong>."
                    : "Ein Teil deiner Bestellung ist unterwegs.";

            String rows = event.items() == null ? "" : event.items().stream()
                    .map(item -> {
                        String name = (item.quantity() > 1 ? item.quantity() + "× " : "")
                                + HtmlUtils.htmlEscape(item.productName());
                        String meta = HtmlUtils.htmlEscape(item.color()) + " · " + HtmlUtils.htmlEscape(item.size());
                        return EmailTemplate.itemRow(name, meta, null);
                    })
                    .collect(Collectors.joining());
            String itemsHtml = rows.isEmpty() ? "" : EmailTemplate.itemsTable(rows);

            // The admin's order-wide SHIPPED override records a dispatch with no carrier and no
            // tracking number, so neither can be assumed present — the info box must say so
            // rather than showing "null" at the customer.
            String trackingValue;
            if (event.carrier() != null && !event.carrier().isBlank()
                    && event.trackingNumber() != null && !event.trackingNumber().isBlank()) {
                trackingValue = HtmlUtils.htmlEscape(event.carrier()) + " · " + HtmlUtils.htmlEscape(event.trackingNumber());
            } else if (event.trackingNumber() != null && !event.trackingNumber().isBlank()) {
                trackingValue = HtmlUtils.htmlEscape(event.trackingNumber());
            } else {
                trackingValue = "Für diese Teilsendung liegt uns keine Tracking-Nummer vor.";
            }
            String noteLine = event.note() == null || event.note().isBlank()
                    ? ""
                    : EmailTemplate.note("Hinweis: " + HtmlUtils.htmlEscape(event.note()));

            String body = EmailTemplate.heroSection(
                    "Versandbestätigung",
                    "Deine " + EmailTemplate.highlight("Bestellung") + " ist unterwegs",
                    intro + " Bestellung <strong>" + HtmlUtils.htmlEscape(event.orderNumber()) + "</strong>.")
                    + itemsHtml
                    + EmailTemplate.infoBox("Sendungsverfolgung", trackingValue)
                    + noteLine
                    + EmailTemplate.note("Bestellungen mit Artikeln mehrerer Marken werden getrennt "
                            + "verschickt — für jede Teilsendung erhältst du eine eigene E-Mail mit "
                            + "eigener Tracking-Nummer.")
                    + EmailTemplate.button(event.orderLink(), "Bestellung ansehen");

            String html = EmailTemplate.shell("Enunas — Versandbestätigung",
                    "Dein Paket ist unterwegs.", body, frontendBaseUrl);

            emailService.sendHtmlEmail(event.buyerEmail(), subject, html);

            log.info("Shipment confirmation email sent to {} for order {}", event.buyerEmail(), event.orderNumber());
        } catch (Exception ex) {
            // EMAIL_DELIVERY_FAILURE — stable marker for a log-based alert (e.g. CloudWatch Logs
            // metric filter). The shipment is already confirmed; only the buyer notification failed.
            log.error("EMAIL_DELIVERY_FAILURE type=shipment-confirmed order={} reason={}",
                    event.orderNumber(), ex.getMessage());
        }
    }
}
