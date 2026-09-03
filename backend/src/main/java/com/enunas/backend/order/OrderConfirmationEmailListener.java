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

import java.math.BigDecimal;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Confirms a captured payment to the customer. Best-effort: an SMTP failure is logged and
 * swallowed, same as {@link RefundCompletedEmailListener} / {@link ReturnApprovedEmailListener} —
 * it must never undo an already-committed PENDING → PAID transition.
 *
 * <p>Every dynamic value that reaches {@link HtmlUtils#htmlEscape} below originates as customer or
 * brand-entered text (product name, address, shipping-breakdown brand names) — unlike the old plain
 * text version, this is real HTML now, so unescaped input would inject markup into the mail.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderConfirmationEmailListener {

    private final EmailService emailService;

    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderConfirmed(OrderConfirmationEvent event) {
        try {
            String rows = event.items().stream()
                    .map(item -> {
                        String name = (item.quantity() > 1 ? item.quantity() + "× " : "")
                                + HtmlUtils.htmlEscape(item.productName());
                        String meta = HtmlUtils.htmlEscape(item.color()) + " · " + HtmlUtils.htmlEscape(item.size());
                        return EmailTemplate.itemRow(name, meta, money(item.lineTotal(), event.currency()));
                    })
                    .collect(Collectors.joining());

            StringBuilder summaryRows = new StringBuilder();
            summaryRows.append(EmailTemplate.summaryRow("Zwischensumme", money(event.subtotal(), event.currency()), false));
            summaryRows.append(EmailTemplate.summaryRow("Versand", shippingValue(event.shippingTotal(), event.currency()), false));
            if (event.discountAmount() != null && event.discountAmount().signum() > 0) {
                summaryRows.append(EmailTemplate.summaryRow("Rabatt", "−" + money(event.discountAmount(), event.currency()), false));
            }
            summaryRows.append(EmailTemplate.summaryRow("Gesamt", money(event.total(), event.currency()), true));

            // Per-brand shipping breakdown only exists (non-empty) on a multi-brand order — a
            // separate note below the summary table rather than forcing it into the two-column
            // row layout, since it's already a pre-joined display string (see OrderService).
            String breakdownNote = event.shippingBreakdown().isEmpty() ? "" : EmailTemplate.note(
                    "Versand nach Marke:<br>" + event.shippingBreakdown().stream()
                            .map(line -> HtmlUtils.htmlEscape(line.strip()))
                            .collect(Collectors.joining("<br>")));

            String addressHtml = event.shippingAddressBlock() == null || event.shippingAddressBlock().isBlank()
                    ? ""
                    : EmailTemplate.labeledBlock("Lieferadresse",
                            HtmlUtils.htmlEscape(event.shippingAddressBlock()).replace("\n", "<br>"));

            String body = EmailTemplate.heroSection(
                    "Bestellbestätigung",
                    "Deine " + EmailTemplate.highlight("Bestellung") + " ist bestätigt",
                    "Bestellnummer <strong>" + HtmlUtils.htmlEscape(event.orderNumber())
                            + "</strong> — wir bereiten sie für den Versand vor.")
                    + EmailTemplate.itemsTable(rows)
                    + EmailTemplate.summaryTable(summaryRows.toString())
                    + breakdownNote
                    + addressHtml
                    + EmailTemplate.button(event.orderLink(), "Bestellung ansehen");

            String html = EmailTemplate.shell("Enunas — Bestellbestätigung",
                    "Deine Bestellung " + event.orderNumber() + " wurde bestätigt.", body, frontendBaseUrl);

            emailService.sendHtmlEmail(event.buyerEmail(), "Bestellbestätigung " + event.orderNumber(), html);

            log.info("Order confirmation email sent to {} for order {}",
                    event.buyerEmail(), event.orderNumber());
        } catch (Exception ex) {
            // EMAIL_DELIVERY_FAILURE — stable marker for a log-based alert (e.g. CloudWatch Logs
            // metric filter). Payment is already captured; only the confirmation email failed.
            log.error("EMAIL_DELIVERY_FAILURE type=order-confirmation order={} reason={}",
                    event.orderNumber(), ex.getMessage());
        }
    }

    private static String shippingValue(BigDecimal shippingTotal, String currency) {
        return shippingTotal == null || shippingTotal.signum() == 0 ? "Kostenlos" : money(shippingTotal, currency);
    }

    private static String money(BigDecimal amount, String currency) {
        if (amount == null) {
            amount = BigDecimal.ZERO;
        }
        String symbol = "EUR".equalsIgnoreCase(currency) ? "€ " : currency + " ";
        return symbol + String.format(Locale.GERMANY, "%,.2f", amount);
    }
}
