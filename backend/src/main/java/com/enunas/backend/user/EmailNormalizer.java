package com.enunas.backend.user;

/** Single canonical form for user-supplied emails across the whole auth surface (signup, login,
 *  Google Sign-In, brand-partner applications): trimmed and lowercased. Every write to
 *  {@code users.email} and every lookup by email MUST go through this, or a user registered
 *  through one path can become unreachable or duplicated through another. */
public final class EmailNormalizer {

    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }

    private EmailNormalizer() {}
}
