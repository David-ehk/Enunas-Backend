package com.enunas.backend.brandpartner;

/**
 * Published inside the brand-application transaction; handled AFTER_COMMIT so the (best-effort)
 * verification email is sent only once the brand is durably persisted and a send failure can never
 * roll back the application. The verification token still travels, but nothing gates on it.
 */
public record BrandApplicationSubmittedEvent(String email, String verificationCode) {
}
