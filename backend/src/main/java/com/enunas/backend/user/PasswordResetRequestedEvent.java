package com.enunas.backend.user;

/** Published after a password-reset code is durably persisted onto the user's account. */
public record PasswordResetRequestedEvent(String email, String code) {
}
