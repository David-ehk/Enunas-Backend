package com.enunas.backend.user;

/** Result of successfully verifying a Google ID token — never constructed from unverified data. */
public record GoogleTokenPayload(
        String subject,
        String email,
        boolean emailVerified,
        String firstName,
        String lastName,
        String pictureUrl
) {}
