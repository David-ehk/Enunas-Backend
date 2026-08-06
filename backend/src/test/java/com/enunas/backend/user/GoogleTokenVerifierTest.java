package com.enunas.backend.user;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.security.GeneralSecurityException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GoogleTokenVerifierTest {

    private GoogleIdTokenVerifier mockVerifier;
    private GoogleTokenVerifier tokenVerifier;

    @BeforeEach
    void setUp() {
        mockVerifier = mock(GoogleIdTokenVerifier.class);
        tokenVerifier = new GoogleTokenVerifier(mockVerifier);
    }

    @Test
    void validToken_returnsPayload() throws GeneralSecurityException, IOException {
        GoogleIdToken idToken = mock(GoogleIdToken.class);
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setSubject("google-sub-123");
        payload.setEmail("jane@example.com");
        payload.setEmailVerified(true);
        payload.put("given_name", "Jane");
        payload.put("family_name", "Doe");
        payload.put("picture", "https://example.com/pic.jpg");
        when(idToken.getPayload()).thenReturn(payload);
        when(mockVerifier.verify("valid-token")).thenReturn(idToken);

        GoogleTokenPayload result = tokenVerifier.verify("valid-token");

        assertThat(result.subject()).isEqualTo("google-sub-123");
        assertThat(result.email()).isEqualTo("jane@example.com");
        assertThat(result.emailVerified()).isTrue();
        assertThat(result.firstName()).isEqualTo("Jane");
        assertThat(result.lastName()).isEqualTo("Doe");
        assertThat(result.pictureUrl()).isEqualTo("https://example.com/pic.jpg");
    }

    @Test
    void nullVerificationResult_throwsIllegalArgumentException() throws GeneralSecurityException, IOException {
        when(mockVerifier.verify("bad-token")).thenReturn(null);

        assertThatThrownBy(() -> tokenVerifier.verify("bad-token"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void verifierThrows_wrapsAsIllegalArgumentException() throws GeneralSecurityException, IOException {
        when(mockVerifier.verify("malformed-token")).thenThrow(new GeneralSecurityException("bad signature"));

        assertThatThrownBy(() -> tokenVerifier.verify("malformed-token"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emailNotVerified_stillReturnsPayload_callerDecidesWhatToDo() throws GeneralSecurityException, IOException {
        GoogleIdToken idToken = mock(GoogleIdToken.class);
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setSubject("google-sub-456");
        payload.setEmail("unverified@example.com");
        payload.setEmailVerified(false);
        when(idToken.getPayload()).thenReturn(payload);
        when(mockVerifier.verify("unverified-token")).thenReturn(idToken);

        GoogleTokenPayload result = tokenVerifier.verify("unverified-token");

        assertThat(result.emailVerified()).isFalse();
    }
}
