package com.enunas.backend.user;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * Verifies a raw Google ID token string (signature, issuer, audience, expiration — all handled
 * by Google's own {@link GoogleIdTokenVerifier}, never hand-rolled). Takes the verifier as a
 * constructor dependency (a Spring bean from {@link GoogleOAuthConfig}) rather than constructing
 * one internally, so tests can substitute a mock without needing a real Google client ID or
 * network access.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleTokenVerifier {

    private final GoogleIdTokenVerifier verifier;

    public GoogleTokenPayload verify(String idTokenString) {
        GoogleIdToken idToken;
        try {
            idToken = verifier.verify(idTokenString);
        } catch (GeneralSecurityException | IOException | IllegalArgumentException e) {
            log.warn("Google ID token verification failed: {}", e.getMessage());
            throw new IllegalArgumentException("Invalid Google ID token");
        }
        if (idToken == null) {
            log.warn("Google ID token rejected: signature, audience, issuer or expiry check failed");
            throw new IllegalArgumentException("Invalid Google ID token");
        }
        GoogleIdToken.Payload payload = idToken.getPayload();
        return new GoogleTokenPayload(
                payload.getSubject(),
                payload.getEmail(),
                Boolean.TRUE.equals(payload.getEmailVerified()),
                (String) payload.get("given_name"),
                (String) payload.get("family_name"),
                (String) payload.get("picture"));
    }
}
