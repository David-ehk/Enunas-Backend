package com.enunas.backend.user;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Year;

/**
 * Shared HTML shell + building blocks for every transactional email. Before this existed, each
 * email in {@link EmailService} carried its own full copy of the header/footer chrome and CSS —
 * five near-identical ~40-line blocks that only ever drifted apart by accident. Callers now supply
 * just their own middle content (built from the component methods below) to {@link #shell}.
 *
 * <p>Design system lifted from the brand's own reference templates (Cormorant Garamond serif +
 * League Spartan sans, warm greige background, deep-purple accent) — see the class-level docs on
 * each component for what was deliberately simplified versus that source (no {@code position:
 * absolute} hover flourish on the button, no embedded @font-face — a Google Fonts {@code <link>}
 * instead, so email clients that strip external resources — most webmail — just fall back to the
 * Georgia/Arial stack already declared inline, exactly as the reference file's own CSS does).
 *
 * <p>Every dynamic value passed into these methods is expected to already be HTML-escaped by the
 * caller ({@code org.springframework.web.util.HtmlUtils.htmlEscape}) if it did not originate on the
 * server — a product name, an address line, a free-text note are all customer/brand input, and this
 * class does no escaping of its own since some callers legitimately need to pass pre-built HTML
 * (a highlighted heading span, a joined address block with {@code <br>}s).
 */
public final class EmailTemplate {

    private EmailTemplate() {}

    // ---- Design tokens ----
    public static final String BG = "#EDEBE6";
    public static final String INK = "#0A0A0A";
    public static final String MUTED = "#6B6B6B";
    public static final String ACCENT = "#370E4D";
    public static final String BOX_BG = "#F5F5F0";
    private static final String HR_COLOR = "#E8E8E8";
    private static final String FOOTER_MUTED = "#B5B2AC";
    private static final String SERIF = "'Cormorant Garamond',Georgia,serif";
    private static final String SANS = "'League Spartan',Arial,sans-serif";

    private static final String LOGO_BASE64 = loadResource("/email/logo-base64.txt");

    /**
     * Wraps {@code bodyHtml} (built from the component methods below) in the full HTML document:
     * head/fonts/CSS, hidden preheader, header (logo/wordmark/tagline), and footer (contact +
     * account/orders links + copyright). {@code previewText} is the preheader most mail clients
     * show next to the subject line in an inbox list — keep it a short, plain sentence.
     */
    public static String shell(String title, String previewText, String bodyHtml, String frontendBaseUrl) {
        String accountUrl = frontendBaseUrl + "/account";
        String ordersUrl = frontendBaseUrl + "/orders";
        return """
                <!DOCTYPE html>
                <html lang="de">
                <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>%s</title>
                <link rel="preconnect" href="https://fonts.googleapis.com">
                <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
                <link href="https://fonts.googleapis.com/css2?family=Cormorant+Garamond:ital,wght@0,300;0,400;1,300;1,400&family=League+Spartan:wght@400;500;600&display=swap" rel="stylesheet">
                <style>
                body{margin:0;padding:0;background:%s;font-family:%s}
                table{border-collapse:collapse}
                img{border:0;display:block}
                a{color:%s}
                .wrap{width:100%%;background:%s;padding:0 0 48px}
                .card{width:600px;max-width:92%%;margin:0 auto;background:#FFFFFF}
                .pad{padding:0 48px}
                .eyebrow{font-family:%s;font-size:11px;letter-spacing:.22em;text-transform:uppercase;color:%s;margin:0 0 14px}
                .serif{font-family:%s;font-weight:300;color:%s;margin:0}
                .body-txt{font-family:%s;font-size:15px;line-height:1.6;color:#2D2D2D;margin:0}
                .hr{border-top:1px solid %s;font-size:0;line-height:0}
                .btn{display:inline-block;background:%s;color:#FFFFFF;font-family:%s;font-size:16px;font-weight:400;letter-spacing:.06em;text-decoration:none;padding:16px 34px}
                .footer-link{color:%s;font-size:11px;letter-spacing:.05em;text-decoration:none}
                .hl{background:#EADFF3;padding:1px 6px;color:%s;font-weight:600}
                </style>
                </head>
                <body>
                <span style="display:none;font-size:1px;color:%s;line-height:1px;max-height:0;max-width:0;opacity:0;overflow:hidden">%s</span>
                <div class="wrap"><div class="card">
                %s
                %s
                %s
                </div></div>
                </body></html>
                """.formatted(
                title, BG, SANS, ACCENT, BG, SANS, MUTED, SERIF, INK, SANS, HR_COLOR, ACCENT, SERIF,
                MUTED, ACCENT, BG, previewText, header(), bodyHtml, footer(accountUrl, ordersUrl));
    }

    private static String header() {
        return """
                <table width="100%%" role="presentation"><tr><td class="pad" style="padding-top:44px;padding-bottom:8px;text-align:center">
                <img src="data:image/png;base64,%s" width="64" alt="Enunas" style="display:block;margin:0 auto 14px;width:64px;height:auto">
                <div style="font-family:%s;font-size:24px;letter-spacing:.22em;font-weight:400;color:%s">ENUNAS</div>
                <p style="font-family:%s;font-style:italic;font-size:15px;color:%s;margin:8px 0 0">Für die, die Mode als Haltung verstehen.</p>
                </td></tr></table>
                <table width="100%%" role="presentation"><tr><td class="pad" style="padding-bottom:36px"></td></tr></table>
                <div class="hr"></div>
                """.formatted(LOGO_BASE64, SERIF, INK, SERIF, MUTED);
    }

    private static String footer(String accountUrl, String ordersUrl) {
        return """
                <div class="hr" style="margin-top:8px"></div>
                <table width="100%%" role="presentation"><tr><td class="pad" style="padding:32px 48px 44px;text-align:center">
                <p class="body-txt" style="font-size:12px;color:%s;margin:0 0 12px">Fragen? Schreib uns an <a href="mailto:contact@enunas.com" style="color:%s">contact@enunas.com</a></p>
                <table role="presentation" style="margin:0 auto"><tr>
                <td style="padding:0 10px"><a class="footer-link" href="%s">Konto</a></td>
                <td style="padding:0 10px;color:%s">|</td>
                <td style="padding:0 10px"><a class="footer-link" href="%s">Bestellungen</a></td>
                </tr></table>
                <p style="font-size:10px;color:%s;letter-spacing:.05em;margin:20px 0 0">© %d Enunas</p>
                </td></tr></table>
                """.formatted(MUTED, ACCENT, accountUrl, HR_COLOR, ordersUrl, FOOTER_MUTED, Year.now().getValue());
    }

    // ---- Components ----

    /** Eyebrow label + serif heading + optional body paragraph, centered — the standard opener
     *  for every email's main message. {@code headingHtml} may contain a {@code class="hl"} span
     *  for the highlighted word, built by the caller. */
    public static String heroSection(String eyebrow, String headingHtml, String bodyTextOrNull) {
        String body = bodyTextOrNull == null ? "" :
                "<p class=\"body-txt\" style=\"margin-top:16px\">" + bodyTextOrNull + "</p>";
        return """
                <table width="100%%" role="presentation"><tr><td class="pad" style="padding-top:8px;padding-bottom:8px;text-align:center">
                <p class="eyebrow">%s</p>
                <h1 class="serif" style="font-size:32px;line-height:1.2">%s</h1>
                %s
                </td></tr></table>
                """.formatted(eyebrow, headingHtml, body);
    }

    /** Wraps {@code word} in the lavender highlight span used inside a heading. */
    public static String highlight(String word) {
        return "<span class=\"hl\">" + word + "</span>";
    }

    public static String button(String href, String label) {
        return """
                <table width="100%%" role="presentation"><tr><td class="pad" style="padding:28px 48px 8px;text-align:center">
                <a class="btn" href="%s">%s</a>
                </td></tr></table>
                """.formatted(href, label);
    }

    /** Small centered muted note — expiry text, "ignore this email" disclaimers, etc. */
    public static String note(String text) {
        return """
                <table width="100%%" role="presentation"><tr><td class="pad" style="padding:24px 48px 8px;text-align:center">
                <p class="body-txt" style="font-size:13px;color:%s">%s</p>
                </td></tr></table>
                """.formatted(MUTED, text);
    }

    /** A shaded info panel: eyebrow label + large serif value. Doubles as the verification/reset
     *  code display and the shipment tracking-number panel — same shape, same visual weight. */
    public static String infoBox(String label, String valueHtml) {
        return """
                <table width="100%%" role="presentation"><tr><td class="pad" style="padding:8px 48px 8px">
                <table width="100%%" role="presentation" style="background:%s"><tr><td style="padding:24px 28px;text-align:center">
                <p class="eyebrow" style="margin:0 0 6px">%s</p>
                <p class="serif" style="font-size:26px;margin:0;letter-spacing:.12em;color:%s">%s</p>
                </td></tr></table>
                </td></tr></table>
                """.formatted(BOX_BG, label, ACCENT, valueHtml);
    }

    /** One order-item row: product name + a muted meta line (color/size), and an optional
     *  right-aligned price cell — null when the price isn't meaningful (a shipment lists what's in
     *  the parcel, not what was paid for it). */
    public static String itemRow(String nameHtml, String metaHtml, String priceOrNull) {
        String priceCell = priceOrNull == null ? "" : """
                <td style="padding:16px 0;border-bottom:1px solid %s;text-align:right;vertical-align:top"><p class="body-txt" style="font-weight:500;color:%s">%s</p></td>
                """.formatted(BOX_BG, INK, priceOrNull);
        return """
                <tr>
                <td style="padding:16px 0;border-bottom:1px solid %s;vertical-align:top">
                <p class="serif" style="font-size:19px;margin:0 0 4px">%s</p>
                <p class="body-txt" style="font-size:13px;color:%s;margin:0">%s</p>
                </td>
                %s
                </tr>
                """.formatted(BOX_BG, nameHtml, MUTED, metaHtml, priceCell);
    }

    public static String itemsTable(String rowsHtml) {
        return """
                <table width="100%%" role="presentation"><tr><td class="pad">
                <table width="100%%" role="presentation">%s</table>
                </td></tr></table>
                """.formatted(rowsHtml);
    }

    /** One label/value row in the order summary (Zwischensumme/Versand/Rabatt/Gesamt). */
    public static String summaryRow(String label, String value, boolean emphasize) {
        String style = emphasize ? "font-weight:600;color:" + INK : "";
        return """
                <tr><td class="body-txt" style="padding:6px 0;color:%s">%s</td><td class="body-txt" style="padding:6px 0;text-align:right;%s">%s</td></tr>
                """.formatted(MUTED, label, style, value);
    }

    public static String summaryTable(String rowsHtml) {
        return """
                <table width="100%%" role="presentation"><tr><td class="pad" style="padding:24px 48px 8px">
                <table width="100%%" role="presentation">%s</table>
                </td></tr></table>
                """.formatted(rowsHtml);
    }

    /** Single labeled block (e.g. delivery address) — full width, left-aligned. */
    public static String labeledBlock(String label, String valueHtml) {
        return """
                <table width="100%%" role="presentation"><tr><td class="pad" style="padding:28px 48px 8px">
                <p class="eyebrow">%s</p>
                <p class="body-txt">%s</p>
                </td></tr></table>
                """.formatted(label, valueHtml);
    }

    private static String loadResource(String classpathPath) {
        try (InputStream in = EmailTemplate.class.getResourceAsStream(classpathPath)) {
            if (in == null) {
                throw new IllegalStateException("Missing email template resource: " + classpathPath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
