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

    @Value("${spring.mail.username}")
    private String fromEmail;

    // ✅ Spezifische Verifizierungs-Email (aus Enunas)
    public void sendVerificationEmail(String to, String verificationCode) {
        String subject = "Enunas – Verify your account";
        String html = buildVerificationHtml(verificationCode);
        sendHtmlEmail(to, subject, html);
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

    public void sendWelcomeEmail(String to, String name) {
        sendHtmlEmail(to, "Welcome to Enunas! 🎉", buildWelcomeHtml(name));
    }

    public void sendPendingApprovalEmail(String to) {
        sendHtmlEmail(to, "Enunas – Account Pending Approval", buildPendingApprovalHtml());
    }

    public void sendAccountApprovedEmail(String to) {
        sendHtmlEmail(to, "Enunas – Your Account is Approved! ✅", buildAccountApprovedHtml());
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

    private String buildVerificationHtml(String verificationCode) {
        return """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <style>
                        body { font-family: Arial, sans-serif; background-color: #f4f4f4; margin: 0; padding: 0; }
                        .container { max-width: 600px; margin: 40px auto; background: #ffffff; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.1); }
                        .header { background-color: #1a1a2e; color: white; padding: 30px; text-align: center; }
                        .header h1 { margin: 0; font-size: 28px; letter-spacing: 2px; }
                        .body { padding: 40px 30px; text-align: center; }
                        .body p { color: #555; font-size: 16px; line-height: 1.6; }
                        .code { display: inline-block; background-color: #f0f0f0; border: 2px dashed #1a1a2e; border-radius: 8px; padding: 16px 40px; font-size: 36px; font-weight: bold; letter-spacing: 8px; color: #1a1a2e; margin: 24px 0; }
                        .expiry { color: #999; font-size: 13px; margin-top: 16px; }
                        .footer { background-color: #f4f4f4; text-align: center; padding: 16px; color: #aaa; font-size: 12px; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <div class="header"><h1>ENUNAS</h1></div>
                        <div class="body">
                            <p>Thank you for registering. Use the code below to verify your account:</p>
                            <div class="code">%s</div>
                            <p class="expiry">This code expires in <strong>15 minutes</strong>.</p>
                            <p>If you did not create an account, you can safely ignore this email.</p>
                        </div>
                        <div class="footer">&copy; 2025 Enunas. All rights reserved.</div>
                    </div>
                </body>
                </html>
                """.formatted(verificationCode);
    }

    private String buildWelcomeHtml(String name) {
        return """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8">
                <style>
                    body { font-family: Arial, sans-serif; background-color: #f4f4f4; margin: 0; padding: 0; }
                    .container { max-width: 600px; margin: 40px auto; background: #ffffff; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.1); }
                    .header { background-color: #1a1a2e; color: white; padding: 30px; text-align: center; }
                    .header h1 { margin: 0; font-size: 28px; letter-spacing: 2px; }
                    .body { padding: 40px 30px; text-align: center; color: #555; font-size: 16px; line-height: 1.6; }
                    .footer { background-color: #f4f4f4; text-align: center; padding: 16px; color: #aaa; font-size: 12px; }
                </style>
                </head>
                <body>
                    <div class="container">
                        <div class="header"><h1>ENUNAS</h1></div>
                        <div class="body">
                            <p>Welcome to Enunas, <strong>%s</strong>! 🎉</p>
                            <p>Your account has been created and is ready to use.</p>
                            <p>Start exploring products and placing orders right away.</p>
                        </div>
                        <div class="footer">&copy; 2025 Enunas. All rights reserved.</div>
                    </div>
                </body>
                </html>
                """.formatted(name);
    }

    private String buildPendingApprovalHtml() {
        return """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8">
                <style>
                    body { font-family: Arial, sans-serif; background-color: #f4f4f4; margin: 0; padding: 0; }
                    .container { max-width: 600px; margin: 40px auto; background: #ffffff; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.1); }
                    .header { background-color: #1a1a2e; color: white; padding: 30px; text-align: center; }
                    .header h1 { margin: 0; font-size: 28px; letter-spacing: 2px; }
                    .body { padding: 40px 30px; text-align: center; color: #555; font-size: 16px; line-height: 1.6; }
                    .badge { display: inline-block; background-color: #fff3cd; border: 1px solid #ffc107; border-radius: 6px; padding: 10px 24px; font-weight: bold; color: #856404; margin: 16px 0; }
                    .footer { background-color: #f4f4f4; text-align: center; padding: 16px; color: #aaa; font-size: 12px; }
                </style>
                </head>
                <body>
                    <div class="container">
                        <div class="header"><h1>ENUNAS</h1></div>
                        <div class="body">
                            <p>Your email has been verified successfully! ✅</p>
                            <div class="badge">⏳ Pending Admin Approval</div>
                            <p>Your Brand Partner account is currently under review. Our team will approve your account shortly.</p>
                            <p>You will receive another email as soon as your account is activated.</p>
                        </div>
                        <div class="footer">&copy; 2025 Enunas. All rights reserved.</div>
                    </div>
                </body>
                </html>
                """;
    }

    private String buildAccountApprovedHtml() {
        return """
                <!DOCTYPE html>
                <html>
                <head><meta charset="UTF-8">
                <style>
                    body { font-family: Arial, sans-serif; background-color: #f4f4f4; margin: 0; padding: 0; }
                    .container { max-width: 600px; margin: 40px auto; background: #ffffff; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 8px rgba(0,0,0,0.1); }
                    .header { background-color: #1a1a2e; color: white; padding: 30px; text-align: center; }
                    .header h1 { margin: 0; font-size: 28px; letter-spacing: 2px; }
                    .body { padding: 40px 30px; text-align: center; color: #555; font-size: 16px; line-height: 1.6; }
                    .badge { display: inline-block; background-color: #d4edda; border: 1px solid #28a745; border-radius: 6px; padding: 10px 24px; font-weight: bold; color: #155724; margin: 16px 0; }
                    .footer { background-color: #f4f4f4; text-align: center; padding: 16px; color: #aaa; font-size: 12px; }
                </style>
                </head>
                <body>
                    <div class="container">
                        <div class="header"><h1>ENUNAS</h1></div>
                        <div class="body">
                            <div class="badge">✅ Account Approved</div>
                            <p>Congratulations! Your Brand Partner account has been approved.</p>
                            <p>You can now log in and start managing your products on Enunas.</p>
                        </div>
                        <div class="footer">&copy; 2025 Enunas. All rights reserved.</div>
                    </div>
                </body>
                </html>
                """;
    }
}
