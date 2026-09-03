package com.enunas.backend.user;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailService {

    private final JavaMailSender mailSender;

    // Deliberately NOT spring.mail.username: that's the SMTP AUTH identity, which is a different
    // concept from the sender address — they were coincidentally the same string under Gmail, but
    // Resend's SMTP auth username is the literal string "resend", not an address. No default here:
    // a wrong-but-present from-address would fail silently at every provider that checks sender
    // domain verification, which is worse than refusing to start.
    @Value("${enunas.mail.from-address}")
    private String fromEmail;

    // Only for the footer's "Konto"/"Bestellungen" links — same property OrderService already uses
    // to build the order-confirmation link, so both stay pointed at the same frontend.
    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    public void sendVerificationEmail(String to, String verificationCode) {
        sendHtmlEmail(to, "Enunas – E-Mail-Adresse bestätigen", buildVerificationHtml(verificationCode));
    }

    // ✅ Generische HTML-Methode (aus Enunas)
    public void sendHtmlEmail(String to, String subject, String htmlContent) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);
            mailSender.send(message);
            log.info("✅ Email sent to: {}", to);
        } catch (MessagingException e) {
            log.error("❌ Failed to send email to {}: {}", to, e.getMessage());
            throw new RuntimeException("Failed to send email", e);
        }
    }

    // `name` is unused in the current template — the welcome design (matching the brand's reference
    // file) is deliberately impersonal, not "Welcome, X!". Kept in the signature so callers/tests
    // that already pass one (today, the caller's own email — see WelcomeEmailListener) don't need
    // to change; a future personalized redesign has somewhere to put it.
    public void sendWelcomeEmail(String to, String name) {
        sendHtmlEmail(to, "Willkommen bei Enunas", buildWelcomeHtml());
    }

    public void sendPendingApprovalEmail(String to) {
        sendHtmlEmail(to, "Enunas – Bewerbung wird geprüft", buildPendingApprovalHtml());
    }

    public void sendAccountApprovedEmail(String to) {
        sendHtmlEmail(to, "Enunas – Dein Account ist freigeschaltet", buildAccountApprovedHtml());
    }

    public void sendPlainTextEmail(String to, String subject, String content) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(content);
            mailSender.send(message);
            log.info("✅ Plain text email sent to: {}", to);
        } catch (Exception e) {
            log.error("❌ Failed to send plain text email to {}: {}", to, e.getMessage());
            throw new RuntimeException("Failed to send email", e);
        }
    }

    public void sendPasswordResetEmail(String to, String code) {
        sendHtmlEmail(to, "Enunas – Passwort zurücksetzen", buildPasswordResetHtml(code));
    }

    // verificationCode/code are server-generated 6-digit strings (see BrandPartnerService /
    // AuthenticationService) — never user input, so no HtmlUtils.htmlEscape needed on them here,
    // unlike the order emails below which do carry customer/brand-entered text.

    private String buildVerificationHtml(String verificationCode) {
        String body = EmailTemplate.heroSection(
                "E-Mail-Adresse bestätigen",
                EmailTemplate.highlight("E-Mail-Adresse") + " bestätigen",
                "Danke für deine Bewerbung. Nutze den Code unten, um deine E-Mail-Adresse zu bestätigen.")
                + EmailTemplate.infoBox("Dein Code", verificationCode)
                + EmailTemplate.note("Der Code ist 15 Minuten gültig. Falls du kein Konto erstellt hast, kannst du diese E-Mail ignorieren.");
        return EmailTemplate.shell("Enunas — E-Mail-Adresse bestätigen",
                "Bestätige deine E-Mail-Adresse.", body, frontendBaseUrl);
    }

    private String buildPasswordResetHtml(String code) {
        String body = EmailTemplate.heroSection(
                "Passwort zurücksetzen",
                EmailTemplate.highlight("Passwort") + " zurücksetzen",
                "Nutze den Code unten, um ein neues Passwort zu vergeben.")
                + EmailTemplate.infoBox("Dein Code", code)
                + EmailTemplate.note("Der Code ist 15 Minuten gültig. Falls du das nicht warst, kannst du diese E-Mail ignorieren.");
        return EmailTemplate.shell("Enunas — Passwort zurücksetzen",
                "Setze dein Passwort zurück.", body, frontendBaseUrl);
    }

    private String buildWelcomeHtml() {
        String body = EmailTemplate.heroSection(
                "Willkommen",
                "Willkommen bei " + EmailTemplate.highlight("Enunas"),
                "Du bist jetzt Teil einer kuratierten Auswahl unabhängiger Labels — Streetwear, Experimental, Athleisure, Culture.")
                + EmailTemplate.button(frontendBaseUrl, "Kollektion entdecken");
        return EmailTemplate.shell("Willkommen bei Enunas",
                "Willkommen in der Enunas Community.", body, frontendBaseUrl);
    }

    private String buildPendingApprovalHtml() {
        String body = EmailTemplate.heroSection(
                "Bewerbung eingegangen",
                "Deine " + EmailTemplate.highlight("Bewerbung") + " wird geprüft",
                "Deine E-Mail-Adresse wurde erfolgreich bestätigt. Dein Brand-Partner-Konto wird jetzt von unserem Team geprüft — du bekommst eine weitere E-Mail, sobald es freigeschaltet ist.")
                + EmailTemplate.infoBox("Status", "In Prüfung");
        return EmailTemplate.shell("Enunas — Bewerbung wird geprüft",
                "Deine Bewerbung wird geprüft.", body, frontendBaseUrl);
    }

    private String buildAccountApprovedHtml() {
        String body = EmailTemplate.heroSection(
                "Account freigeschaltet",
                "Dein Konto ist " + EmailTemplate.highlight("freigeschaltet"),
                "Dein Brand-Partner-Konto wurde freigegeben. Du kannst dich jetzt einloggen und deine Produkte auf Enunas verwalten.")
                + EmailTemplate.button(frontendBaseUrl + "/login", "Jetzt einloggen");
        return EmailTemplate.shell("Enunas — Account freigeschaltet",
                "Dein Account ist freigeschaltet.", body, frontendBaseUrl);
    }
}
