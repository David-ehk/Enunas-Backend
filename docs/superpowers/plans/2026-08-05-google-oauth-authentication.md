# Google OAuth Authentication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **NO GIT COMMITS during execution.** The project owner handles all `git add`/`git commit`/`git push` themselves. Every "Commit" step below is replaced by "Leave the change in the working tree" — do not stage or commit anything. Avoid running `git status`/`git log` unless genuinely necessary; prefer reading files directly.

**Goal:** Let the frontend authenticate users via Google Sign-In, with the backend independently verifying the Google ID token and issuing the exact same JWT the existing email/password login already issues — no separate authentication machinery, no duplicate accounts, and a path for a Google-only user to later add a password.

**Architecture:** A new `GoogleTokenVerifier` wraps Google's own `GoogleIdTokenVerifier` library to verify signature/issuer/audience/expiration — never hand-rolled JWKS handling. A new `OAuthAccount` entity links an external Google identity (keyed by Google's stable `sub` claim) to an existing `User`. `AuthenticationService` gains `loginWithGoogle(...)`, which resolves identity in priority order — returning Google identity first, then email match (only when Google's `email_verified` claim is true), then brand-new signup — and reuses the existing `Customer` entity for profile fields rather than duplicating them onto `User`. `User.password` becomes nullable. Everything downstream of "we have a `User`" (JWT issuance, the JWT filter, `@AuthenticationPrincipal`, every `@PreAuthorize` check) is completely unchanged and unaware of how the session started.

**Tech Stack:** Java 21, Spring Boot 4.0.5, Spring Security (already present — no OAuth2 client/resource-server starter added, this is direct ID-token verification, not a server-side redirect flow), `com.google.api-client:google-api-client` (new dependency, for `GoogleIdTokenVerifier`), `jjwt` (already present, unchanged, still issues Enunas's own JWTs), Flyway, Lombok, JUnit 5 + Mockito + AssertJ + Spring Boot Test / Testcontainers (all already in use).

## Global Constraints

- One new Maven dependency: `com.google.api-client:google-api-client` — deliberately chosen over hand-rolling JWKS/key-rotation verification, which is a real security risk to get wrong. No other new dependencies.
- `OAuthProvider` is an enum (`{ GOOGLE }`) — matches this codebase's convention for closed categorical fields (`Role`, `OrderStatus`, etc.), even though only one value exists today.
- `OAuthAccount` carries two DB-level unique constraints, not just application-level checks: `UNIQUE(provider, provider_user_id)` (one external identity, one Enunas user, ever) and `UNIQUE(user_id, provider)` (one connection per provider per user).
- Google Sign-In can only ever create `role = CUSTOMER` accounts — hardcoded, never derived from token content or any other external input. Never `BRAND_PARTNER` or `ADMIN`.
- Email is normalized (trimmed, lowercased) before every lookup/link/create in the Google flow, consistently. Auto-linking to an existing password-based account only happens when Google's `email_verified` claim is `true`.
- Profile fields (`firstName`, `lastName`, `profileImageUrl`) go on the existing `Customer` entity, populated at Google signup time — never duplicated onto `User`.
- No new authentication flow, no new JWT mechanism: `POST /auth/google` must end by calling the exact same `JwtService.generateToken(extraClaims, user)` that `AuthController.login` already calls, returning the same `LoginResponseDto`.
- No unlink-Google or remove-password endpoint in this plan — only documentation of the "never leave a user with zero auth methods" invariant, positioned so a future endpoint can enforce it (see Task 2).
- Never edit an applied Flyway migration (V0.0.1–V19 off-limits) — only a new `V20` script.
- No worktree, no git commits, no pushes during execution.

## File Structure

| File | Responsibility |
|---|---|
| `pom.xml` | New `google-api-client` dependency. |
| `application.yaml`, `application-test.yaml` | New `google.oauth.client-id` property (env-backed in prod, hardcoded test value in the test profile). |
| `user/GoogleTokenPayload.java` | Verified-token result shape (subject, email, emailVerified, firstName, lastName, pictureUrl). |
| `user/GoogleOAuthConfig.java` | Produces the `GoogleIdTokenVerifier` Spring bean from the configured client ID. |
| `user/GoogleTokenVerifier.java` | Verifies a raw ID token string, returns `GoogleTokenPayload` or throws `IllegalArgumentException`. |
| `db/migration/V20__oauth_accounts_and_optional_password.sql` | New `oauth_accounts` table + `users.password` made nullable. |
| `user/OAuthProvider.java`, `OAuthAccount.java`, `OAuthAccountRepository.java` | Data model linking an external identity to a `User`. |
| `customer/CustomerService.java` | Gains a `createForUser` overload accepting profile data. |
| `user/dto/GoogleAuthDto.java`, `SetPasswordDto.java` | New request DTOs. |
| `user/AuthenticationService.java` | Gains `loginWithGoogle(...)` and `setPassword(...)`. |
| `user/AuthController.java` | Gains `POST /auth/google` and `POST /auth/set-password`. |

---

### Task 1: Google ID token verification

**Files:**
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.yaml`
- Modify: `backend/src/test/resources/application-test.yaml`
- Create: `backend/src/main/java/com/enunas/backend/user/GoogleTokenPayload.java`
- Create: `backend/src/main/java/com/enunas/backend/user/GoogleOAuthConfig.java`
- Create: `backend/src/main/java/com/enunas/backend/user/GoogleTokenVerifier.java`
- Test: `backend/src/test/java/com/enunas/backend/user/GoogleTokenVerifierTest.java`

**Interfaces:**
- Produces: `GoogleTokenPayload(String subject, String email, boolean emailVerified, String firstName, String lastName, String pictureUrl)` — a record. Every later task that needs Google identity data uses exactly this shape.
- Produces: `GoogleTokenVerifier.verify(String idTokenString) -> GoogleTokenPayload`, throwing `IllegalArgumentException` for any invalid/unparseable/unverifiable token. Task 3 calls this directly from `AuthController`.
- Produces: a Spring-managed `GoogleIdTokenVerifier` bean (from `GoogleOAuthConfig`) — Task 5's integration tests replace this exact bean with `@MockitoBean` to simulate Google responses without calling real Google infrastructure.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/enunas/backend/user/GoogleTokenVerifierTest.java`:

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=GoogleTokenVerifierTest` (from `backend/`)

Expected: compile failure — `GoogleTokenVerifier`/`GoogleTokenPayload` don't exist yet, and `com.google.api.client.*` isn't on the classpath yet.

- [ ] **Step 3: Add the dependency**

In `backend/pom.xml`, add this dependency inside the existing `<dependencies>` block (anywhere among the other non-test dependencies, e.g. right after the `mollie` dependency):

```xml
        <dependency>
            <groupId>com.google.api-client</groupId>
            <artifactId>google-api-client</artifactId>
            <version>2.7.0</version>
        </dependency>
```

- [ ] **Step 4: Add the config property**

In `backend/src/main/resources/application.yaml`, add this new top-level section (e.g. after the existing `mollie:` block):

```yaml
google:
  oauth:
    client-id: ${GOOGLE_OAUTH_CLIENT_ID}
```

In `backend/src/test/resources/application-test.yaml`, add (e.g. after the existing `mollie:` block):

```yaml
google:
  oauth:
    client-id: test-google-client-id.apps.googleusercontent.com
```

(This test value is never validated against real Google infrastructure — Task 5's integration tests replace the `GoogleIdTokenVerifier` bean itself with a Mockito mock, so this string just needs to exist so the Spring context can construct the bean at boot. `GOOGLE_OAUTH_CLIENT_ID` has no default in `application.yaml`, matching how `DB_PASSWORD`/`JWT_SECRET`/`MOLLIE_API_KEY` are already required, no-default secrets in this codebase — add it to your local `.env` file to run the app outside tests.)

- [ ] **Step 5: Write the payload record and verifier**

Create `backend/src/main/java/com/enunas/backend/user/GoogleTokenPayload.java`:

```java
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
```

Create `backend/src/main/java/com/enunas/backend/user/GoogleOAuthConfig.java`:

```java
package com.enunas.backend.user;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;

@Configuration
public class GoogleOAuthConfig {

    @Bean
    public GoogleIdTokenVerifier googleIdTokenVerifier(@Value("${google.oauth.client-id}") String clientId) {
        return new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
                .setAudience(Collections.singletonList(clientId))
                .build();
    }
}
```

Create `backend/src/main/java/com/enunas/backend/user/GoogleTokenVerifier.java`:

```java
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
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./mvnw test -Dtest=GoogleTokenVerifierTest`

Expected: PASS (4/4 tests green)

- [ ] **Step 7: Leave the change in the working tree** (no commit — see banner)

---

### Task 2: `OAuthAccount` data model

**Files:**
- Create: `backend/src/main/java/com/enunas/backend/user/OAuthProvider.java`
- Create: `backend/src/main/java/com/enunas/backend/user/OAuthAccount.java`
- Create: `backend/src/main/java/com/enunas/backend/user/OAuthAccountRepository.java`
- Create: `backend/src/main/resources/db/migration/V20__oauth_accounts_and_optional_password.sql`

**Interfaces:**
- Produces: `OAuthProvider` enum (`GOOGLE`). `OAuthAccount { id, user, provider, providerUserId, createdAt }`, getters/setters/builder via Lombok. `OAuthAccountRepository` with `findByProviderAndProviderUserId(OAuthProvider, String)`, `findByUserAndProvider(User, OAuthProvider)`, `existsByUser(User)` — Task 3 depends on `findByProviderAndProviderUserId` and the save path; a future (not-this-plan) unlink endpoint would depend on `existsByUser`.
- Produces: `users.password` becomes nullable at the DB level — Task 3/4 rely on being able to persist a `User` with `password = null`.

No dedicated test in this task — pure schema/entity/repository, exercised end-to-end by Task 3's and Task 5's tests (there's no meaningful behavior to unit-test in a bare `@Entity`/`JpaRepository` pair beyond what those cover).

- [ ] **Step 1: Write the enum, entity, and repository**

Create `backend/src/main/java/com/enunas/backend/user/OAuthProvider.java`:

```java
package com.enunas.backend.user;

public enum OAuthProvider {
    GOOGLE
}
```

Create `backend/src/main/java/com/enunas/backend/user/OAuthAccount.java`:

```java
package com.enunas.backend.user;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Links an external OAuth identity to an Enunas {@link User}. A user must never be left without
 * an authentication method (a password AND/OR at least one OAuthAccount). Nothing in this plan
 * removes a password or an OAuthAccount — no unlink/remove-password endpoint exists yet — so
 * that invariant holds today by construction. If an unlink-provider or remove-password endpoint
 * is ever built, it MUST check {@code user.getPassword() != null || oAuthAccountRepository
 * .existsByUser(user)} before allowing the removal, or an account could end up with zero ways to
 * log in.
 */
@Entity
@Table(name = "oauth_accounts")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OAuthAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OAuthProvider provider;

    @Column(name = "provider_user_id", nullable = false)
    private String providerUserId;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
```

Create `backend/src/main/java/com/enunas/backend/user/OAuthAccountRepository.java`:

```java
package com.enunas.backend.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OAuthAccountRepository extends JpaRepository<OAuthAccount, Long> {

    Optional<OAuthAccount> findByProviderAndProviderUserId(OAuthProvider provider, String providerUserId);

    Optional<OAuthAccount> findByUserAndProvider(User user, OAuthProvider provider);

    boolean existsByUser(User user);
}
```

- [ ] **Step 2: Write the migration**

Create `backend/src/main/resources/db/migration/V20__oauth_accounts_and_optional_password.sql`:

```sql
-- =============================================================================
-- V20: oauth_accounts -- links an external OAuth identity (currently only Google)
-- to an Enunas user, and makes users.password optional for OAuth-only accounts.
--
-- UNIQUE(provider, provider_user_id): a single external identity must never be
-- linkable to two different Enunas users.
-- UNIQUE(user_id, provider): each user gets at most one connection per provider
-- (only Google exists today; still correct if a second provider is added later,
-- since the constraint is per-provider, not global).
--
-- users.password DROP NOT NULL: safe, non-destructive -- every existing row
-- already has a non-null password, this only loosens the constraint for new
-- Google-only signups going forward.
-- =============================================================================

CREATE TABLE IF NOT EXISTS oauth_accounts (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES users(id),
    provider varchar(50) NOT NULL,
    provider_user_id varchar(255) NOT NULL,
    created_at timestamp(6) NOT NULL,
    CONSTRAINT uq_oauth_accounts_provider_identity UNIQUE (provider, provider_user_id),
    CONSTRAINT uq_oauth_accounts_user_provider UNIQUE (user_id, provider)
);

ALTER TABLE users ALTER COLUMN password DROP NOT NULL;
```

- [ ] **Step 3: Verify the module compiles**

Run: `./mvnw compile -q` (from `backend/`)

Expected: clean exit, no output, no errors.

- [ ] **Step 4: Leave the change in the working tree** (no commit — see banner)

---

### Task 3: Google Sign-In endpoint — identity resolution, role safety, no duplicate accounts

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/customer/CustomerService.java` (add one overload)
- Create: `backend/src/main/java/com/enunas/backend/user/dto/GoogleAuthDto.java`
- Modify: `backend/src/main/java/com/enunas/backend/user/AuthenticationService.java` (add `loginWithGoogle`)
- Modify: `backend/src/main/java/com/enunas/backend/user/AuthController.java` (add `POST /auth/google`)
- Test: `backend/src/test/java/com/enunas/backend/user/AuthenticationServiceGoogleTest.java`

**Interfaces:**
- Consumes: `GoogleTokenPayload`/`GoogleTokenVerifier` (Task 1), `OAuthProvider`/`OAuthAccount`/`OAuthAccountRepository` (Task 2).
- Produces: `AuthenticationService.loginWithGoogle(GoogleTokenPayload payload) -> User` — Task 4's `AuthController` wiring is unaffected by this method, but any later task reusing Google login logic would call this exact method. `CustomerService.createForUser(User, String firstName, String lastName, String profileImageUrl) -> Customer` — an overload; the existing `createForUser(User)` now delegates to it with `null` profile fields, so its existing caller (`AuthenticationService.signup`) is unaffected.

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/com/enunas/backend/user/AuthenticationServiceGoogleTest.java`:

```java
package com.enunas.backend.user;

import com.enunas.backend.customer.Customer;
import com.enunas.backend.customer.CustomerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthenticationServiceGoogleTest {

    private UserRepository userRepository;
    private OAuthAccountRepository oAuthAccountRepository;
    private CustomerService customerService;
    private AuthenticationService authenticationService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        oAuthAccountRepository = mock(OAuthAccountRepository.class);
        customerService = mock(CustomerService.class);
        authenticationService = new AuthenticationService(
                userRepository,
                mock(BCryptPasswordEncoder.class),
                mock(AuthenticationManager.class),
                mock(EmailService.class),
                customerService,
                oAuthAccountRepository);
    }

    @Test
    void returningGoogleIdentity_returnsLinkedUser_noNewRowsCreated() {
        User existingUser = User.builder().id(1L).email("jane@example.com").role(Role.CUSTOMER).build();
        OAuthAccount link = OAuthAccount.builder().user(existingUser).provider(OAuthProvider.GOOGLE)
                .providerUserId("google-sub-123").build();
        when(oAuthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-123"))
                .thenReturn(Optional.of(link));

        GoogleTokenPayload payload = new GoogleTokenPayload("google-sub-123", "jane@example.com", true, "Jane", "Doe", null);
        User result = authenticationService.loginWithGoogle(payload);

        assertThat(result).isEqualTo(existingUser);
        verify(userRepository, never()).save(any());
        verify(oAuthAccountRepository, never()).save(any());
    }

    @Test
    void newGoogleIdentity_matchingExistingEmail_linksToExistingUser() {
        when(oAuthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-456"))
                .thenReturn(Optional.empty());
        User existingUser = User.builder().id(2L).email("john@example.com").password("hashed").role(Role.CUSTOMER).build();
        when(userRepository.findByEmail("john@example.com")).thenReturn(Optional.of(existingUser));

        GoogleTokenPayload payload = new GoogleTokenPayload("google-sub-456", "John@Example.com", true, "John", "Doe", null);
        User result = authenticationService.loginWithGoogle(payload);

        assertThat(result).isEqualTo(existingUser);
        verify(oAuthAccountRepository).save(argThat(a ->
                a.getUser() == existingUser && a.getProvider() == OAuthProvider.GOOGLE
                        && a.getProviderUserId().equals("google-sub-456")));
        verify(userRepository, never()).save(any());
    }

    @Test
    void newGoogleIdentity_matchingExistingEmail_unverifiedEmail_throws() {
        when(oAuthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-789"))
                .thenReturn(Optional.empty());
        User existingUser = User.builder().id(3L).email("unverified@example.com").role(Role.CUSTOMER).build();
        when(userRepository.findByEmail("unverified@example.com")).thenReturn(Optional.of(existingUser));

        GoogleTokenPayload payload = new GoogleTokenPayload("google-sub-789", "unverified@example.com", false, "A", "B", null);

        assertThatThrownBy(() -> authenticationService.loginWithGoogle(payload))
                .isInstanceOf(IllegalArgumentException.class);
        verify(oAuthAccountRepository, never()).save(any());
    }

    @Test
    void brandNewGoogleUser_createsCustomerRoleOnly_passwordNull_emailNormalized() {
        when(oAuthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, "google-sub-999"))
                .thenReturn(Optional.empty());
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(customerService.createForUser(any(User.class), any(), any(), any()))
                .thenReturn(mock(Customer.class));

        GoogleTokenPayload payload = new GoogleTokenPayload("google-sub-999", "  New@Example.com  ", true, "New", "User", "https://pic");
        User result = authenticationService.loginWithGoogle(payload);

        assertThat(result.getEmail()).isEqualTo("new@example.com");
        assertThat(result.getRole()).isEqualTo(Role.CUSTOMER);
        assertThat(result.getPassword()).isNull();
        assertThat(result.isEnabled()).isTrue();
        verify(customerService).createForUser(result, "New", "User", "https://pic");
        verify(oAuthAccountRepository).save(argThat(a ->
                a.getProvider() == OAuthProvider.GOOGLE && a.getProviderUserId().equals("google-sub-999")));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=AuthenticationServiceGoogleTest`

Expected: compile failure — `AuthenticationService.loginWithGoogle` doesn't exist yet, and its constructor doesn't yet accept an `OAuthAccountRepository`.

- [ ] **Step 3: Add the `CustomerService` overload**

In `backend/src/main/java/com/enunas/backend/customer/CustomerService.java`, replace:

```java
    /** Server-side: create the matching Customer record when a CUSTOMER user signs up. */
    @Transactional
    public Customer createForUser(User user) {
        Customer customer = Customer.builder()
                .user(user)
                .preferredStyles(new ArrayList<>())
                .favoriteBrands(new ArrayList<>())
                .favoriteCategories(new ArrayList<>())
                .build();
        return customerRepository.save(customer);
    }
```

with:

```java
    /** Server-side: create the matching Customer record when a CUSTOMER user signs up. */
    @Transactional
    public Customer createForUser(User user) {
        return createForUser(user, null, null, null);
    }

    /** Overload for signups that arrive with known profile data (e.g. Google OAuth). */
    @Transactional
    public Customer createForUser(User user, String firstName, String lastName, String profileImageUrl) {
        Customer customer = Customer.builder()
                .user(user)
                .firstName(firstName)
                .lastName(lastName)
                .profileImageUrl(profileImageUrl)
                .preferredStyles(new ArrayList<>())
                .favoriteBrands(new ArrayList<>())
                .favoriteCategories(new ArrayList<>())
                .build();
        return customerRepository.save(customer);
    }
```

- [ ] **Step 4: Add the request DTO**

Create `backend/src/main/java/com/enunas/backend/user/dto/GoogleAuthDto.java`:

```java
package com.enunas.backend.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class GoogleAuthDto {

    @NotBlank
    private String idToken;
}
```

- [ ] **Step 5: Add `loginWithGoogle` to `AuthenticationService`**

In `backend/src/main/java/com/enunas/backend/user/AuthenticationService.java`, the class currently declares these `private final` fields, in this exact order:

```java
    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final EmailService emailService;
    private final CustomerService customerService;
```

Add `private final OAuthAccountRepository oAuthAccountRepository;` as the LAST entry in this list, immediately after `customerService` — order matters here: the class is `@RequiredArgsConstructor`, so Lombok generates the constructor's parameter list in field-declaration order, and this plan's tests (Task 3's `AuthenticationServiceGoogleTest`, Task 4's `AuthenticationServicePasswordTest`) construct `AuthenticationService` positionally, expecting `oAuthAccountRepository` as the 6th and final constructor argument. Do not insert it anywhere else in the list. Add these imports: `import java.util.Optional;` (if not already present — check first, the file already imports `java.security.SecureRandom` and `java.time.LocalDateTime`, add `java.util.Optional` alongside them).

Add this method to the class body:

```java
    /**
     * Resolves a verified Google identity to a {@link User}, in priority order: (1) a returning
     * Google identity (matched by provider + providerUserId — Google's stable {@code sub} claim,
     * which never changes even if the person changes their Google email); (2) an existing
     * password-based account with a matching normalized email, ONLY when Google's own
     * {@code email_verified} claim is true (unverified emails are never trusted to auto-link);
     * (3) otherwise, a brand-new signup. Always creates {@code role = CUSTOMER}, never derived
     * from the token — Google Sign-In can never create a BRAND_PARTNER or ADMIN account.
     */
    @Transactional
    public User loginWithGoogle(GoogleTokenPayload payload) {
        Optional<OAuthAccount> existingLink =
                oAuthAccountRepository.findByProviderAndProviderUserId(OAuthProvider.GOOGLE, payload.subject());
        if (existingLink.isPresent()) {
            log.info("Google login: returning user via existing link, subject={}", payload.subject());
            return existingLink.get().getUser();
        }

        String normalizedEmail = normalizeEmail(payload.email());

        Optional<User> existingUser = userRepository.findByEmail(normalizedEmail);
        if (existingUser.isPresent()) {
            if (!payload.emailVerified()) {
                log.warn("Google login: refusing to link unverified email {}", normalizedEmail);
                throw new IllegalArgumentException(
                        "Google account email is not verified — cannot link to an existing account");
            }
            User user = existingUser.get();
            oAuthAccountRepository.save(OAuthAccount.builder()
                    .user(user)
                    .provider(OAuthProvider.GOOGLE)
                    .providerUserId(payload.subject())
                    .build());
            log.info("Google login: linked new Google identity to existing user {}", user.getEmail());
            return user;
        }

        User user = User.builder()
                .email(normalizedEmail)
                .password(null)
                .role(Role.CUSTOMER)
                .enabled(true)
                .adminApproved(true)
                .build();
        userRepository.save(user);

        customerService.createForUser(user, payload.firstName(), payload.lastName(), payload.pictureUrl());

        oAuthAccountRepository.save(OAuthAccount.builder()
                .user(user)
                .provider(OAuthProvider.GOOGLE)
                .providerUserId(payload.subject())
                .build());

        emailService.sendWelcomeEmail(user.getEmail(), user.getEmail());
        log.info("Customer registered via Google: {}", user.getEmail());
        return user;
    }

    private static String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
```

- [ ] **Step 6: Wire the endpoint**

In `backend/src/main/java/com/enunas/backend/user/AuthController.java`, add `private final GoogleTokenVerifier googleTokenVerifier;` to the field list, add the imports `import com.enunas.backend.user.dto.GoogleAuthDto;` and `import com.enunas.backend.user.GoogleTokenPayload;` (the latter is in the same package as `AuthController`, so no import is actually needed for it — only add the `GoogleAuthDto` import), then add this endpoint (e.g. right after the existing `login` method):

```java
    @PostMapping("/google")
    public ResponseEntity<LoginResponseDto> loginWithGoogle(@Valid @RequestBody GoogleAuthDto dto) {
        GoogleTokenPayload payload = googleTokenVerifier.verify(dto.getIdToken());
        User user = authenticationService.loginWithGoogle(payload);

        Map<String, Object> extraClaims = new HashMap<>();
        extraClaims.put("role", user.getRole().name());

        String token = jwtService.generateToken(extraClaims, user);
        return ResponseEntity.ok(LoginResponseDto.builder()
                .token(token)
                .expiresIn(jwtService.getExpirationTime())
                .build());
    }
```

- [ ] **Step 7: Run test to verify it passes**

Run: `./mvnw test -Dtest=AuthenticationServiceGoogleTest`

Expected: PASS (4/4 tests green)

- [ ] **Step 8: Leave the change in the working tree** (no commit — see banner)

---

### Task 4: Set password for a Google-only account

**Files:**
- Create: `backend/src/main/java/com/enunas/backend/user/dto/SetPasswordDto.java`
- Modify: `backend/src/main/java/com/enunas/backend/user/AuthenticationService.java` (add `setPassword`)
- Modify: `backend/src/main/java/com/enunas/backend/user/AuthController.java` (add `POST /auth/set-password`)
- Test: `backend/src/test/java/com/enunas/backend/user/AuthenticationServicePasswordTest.java`

**Interfaces:**
- Consumes: nothing new from Tasks 1–3 beyond what's already wired.
- Produces: `AuthenticationService.setPassword(User currentUser, SetPasswordDto dto)` — Task 5's integration test calls this indirectly via `POST /auth/set-password`.

**Design note on the existing `login()` method — read before writing code:** the design doc for this plan assumed `login()` needed a new null-password guard before calling `authenticationManager.authenticate(...)`, reasoning that matching against a `null` encoded password would throw. That assumption doesn't hold: Spring Security's `BCryptPasswordEncoder.matches(raw, encoded)` already returns `false` (not an exception) when `encoded` is `null` or empty, which `DaoAuthenticationProvider` then reports as the same `BadCredentialsException` it uses for a wrong password — already mapped to a clean 401 by the existing `GlobalExceptionHandler.handleBadCredentials`. Adding a pre-authentication check that reveals "this account has no password, it's Google-only" would tell an anonymous caller something true about an account without them ever proving they control it — a user-enumeration regression this plan doesn't need to take on for a cosmetic message improvement. **Do not modify `login()`'s logic or its authenticate-then-load-user order.** This task only adds a regression test proving the existing behavior is already safe, plus the actual new functionality (set-password).

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/com/enunas/backend/user/AuthenticationServicePasswordTest.java`:

```java
package com.enunas.backend.user;

import com.enunas.backend.customer.CustomerService;
import com.enunas.backend.user.dto.LoginUserDto;
import com.enunas.backend.user.dto.SetPasswordDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthenticationServicePasswordTest {

    private UserRepository userRepository;
    private AuthenticationManager authenticationManager;
    private BCryptPasswordEncoder passwordEncoder;
    private AuthenticationService authenticationService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        authenticationManager = mock(AuthenticationManager.class);
        passwordEncoder = mock(BCryptPasswordEncoder.class);
        authenticationService = new AuthenticationService(
                userRepository,
                passwordEncoder,
                authenticationManager,
                mock(EmailService.class),
                mock(CustomerService.class),
                mock(OAuthAccountRepository.class));
    }

    @Test
    void login_googleOnlyAccount_failsCleanlyAsBadCredentials_notAnUnexpectedException() {
        // Simulates the real AuthenticationManager/BCryptPasswordEncoder contract: a null
        // encoded password makes matches() return false, which DaoAuthenticationProvider reports
        // as the same BadCredentialsException it uses for a wrong password (already mapped to
        // 401 by GlobalExceptionHandler.handleBadCredentials). AuthenticationService must
        // propagate it as-is -- no null-password special case, since distinguishing "no
        // password" from "wrong password" pre-authentication would let a caller enumerate which
        // accounts are Google-only.
        LoginUserDto dto = new LoginUserDto();
        dto.setEmail("google-only@example.com");
        dto.setPassword("anything");
        doThrow(new BadCredentialsException("Bad credentials"))
                .when(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));

        assertThatThrownBy(() -> authenticationService.login(dto))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void setPassword_googleOnlyAccount_setsEncodedPassword() {
        User user = User.builder().id(1L).email("google-only@example.com").password(null).role(Role.CUSTOMER).build();
        SetPasswordDto dto = new SetPasswordDto();
        dto.setNewPassword("newSecurePassword1");
        when(passwordEncoder.encode("newSecurePassword1")).thenReturn("encoded-hash");

        authenticationService.setPassword(user, dto);

        assertThat(user.getPassword()).isEqualTo("encoded-hash");
        verify(userRepository).save(user);
    }

    @Test
    void setPassword_accountAlreadyHasPassword_throws() {
        User user = User.builder().id(2L).email("has-password@example.com").password("existing-hash").role(Role.CUSTOMER).build();
        SetPasswordDto dto = new SetPasswordDto();
        dto.setNewPassword("newSecurePassword1");

        assertThatThrownBy(() -> authenticationService.setPassword(user, dto))
                .isInstanceOf(IllegalStateException.class);
        verify(userRepository, never()).save(any());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=AuthenticationServicePasswordTest`

Expected: `login_googleOnlyAccount_...` already PASSES (this is the regression test proving current behavior is safe — no code change needed for it). `setPassword_...` tests FAIL to compile — `SetPasswordDto` and `AuthenticationService.setPassword` don't exist yet.

- [ ] **Step 3: Add the DTO and service method**

Create `backend/src/main/java/com/enunas/backend/user/dto/SetPasswordDto.java`:

```java
package com.enunas.backend.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SetPasswordDto {

    @NotBlank
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String newPassword;
}
```

In `backend/src/main/java/com/enunas/backend/user/AuthenticationService.java`, add this method (e.g. right after the existing `changePassword` method):

```java
    /** Lets a Google-only account (password == null) add a password, enabling email/password
     *  login alongside Google Sign-In. A user who already has a password uses changePassword
     *  instead (which requires knowing the current one — a different, already-correct flow). */
    @Transactional
    public void setPassword(User currentUser, SetPasswordDto dto) {
        if (currentUser.getPassword() != null) {
            throw new IllegalStateException(
                    "This account already has a password — use change-password to update it.");
        }
        currentUser.setPassword(passwordEncoder.encode(dto.getNewPassword()));
        userRepository.save(currentUser);
        log.info("Password set for Google-only account: {}", currentUser.getEmail());
    }
```

- [ ] **Step 4: Wire the endpoint**

In `backend/src/main/java/com/enunas/backend/user/AuthController.java`, add the import `import com.enunas.backend.user.dto.SetPasswordDto;`, then add this endpoint (e.g. right after the existing `changePassword` method — same `Authentication`-parameter pattern):

```java
    @PostMapping("/set-password")
    public ResponseEntity<Void> setPassword(
            @Valid @RequestBody SetPasswordDto dto,
            Authentication authentication) {
        User currentUser = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
        authenticationService.setPassword(currentUser, dto);
        return ResponseEntity.ok().build();
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -Dtest=AuthenticationServicePasswordTest`

Expected: PASS (3/3 tests green)

- [ ] **Step 6: Leave the change in the working tree** (no commit — see banner)

---

### Task 5: End-to-end integration tests, final full-suite run

**Files:**
- Modify: `backend/src/test/java/com/enunas/backend/discount/integration/AbstractDiscountIntegrationTest.java` (add `oauth_accounts` to the truncate list only)
- Test: `backend/src/test/java/com/enunas/backend/user/integration/GoogleAuthIntegrationTest.java`

**Interfaces:**
- Consumes: everything from Tasks 1–4 — this is the final task, exercising the whole feature end-to-end against a real (Testcontainers) Postgres, with the `GoogleIdTokenVerifier` bean replaced by a `@MockitoBean` so no real Google infrastructure is called.
- Produces: nothing consumed by a later task.

- [ ] **Step 1: Add `oauth_accounts` to the shared integration test cleanup**

In `backend/src/test/java/com/enunas/backend/discount/integration/AbstractDiscountIntegrationTest.java`, find the `TRUNCATE TABLE` call in `cleanDatabase()` (it already lists `user_addresses` from an earlier feature) and add `oauth_accounts` to the same list, e.g.:

```java
        jdbc.execute("TRUNCATE TABLE settlement_runs, ledger_entries, payments, order_items, orders, listings, " +
                "product_variants, product_colors, products, discount_codes, brand_economics, " +
                "brand_partners, user_addresses, oauth_accounts, customers, users RESTART IDENTITY CASCADE");
```

(Read the file first to get the exact current line — the table list may not be in this exact order; add `oauth_accounts` alongside `user_addresses` without removing or reordering anything else.)

- [ ] **Step 2: Write the integration tests**

Create `backend/src/test/java/com/enunas/backend/user/integration/GoogleAuthIntegrationTest.java`:

```java
package com.enunas.backend.user.integration;

import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GoogleAuthIntegrationTest extends AbstractDiscountIntegrationTest {

    @MockitoBean
    private GoogleIdTokenVerifier googleIdTokenVerifier;

    @Test
    void googleSignIn_newUser_createsCustomerAccount() throws Exception {
        stubGoogleToken("fake-token-1", "sub-1", "brandnew@example.com", true, "Brand", "New");

        ResponseEntity<Map> resp = rest.exchange("/auth/google", HttpMethod.POST,
                new HttpEntity<>(Map.of("idToken", "fake-token-1")), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("token")).isNotNull();
        var created = userRepository.findByEmail("brandnew@example.com").orElseThrow();
        assertThat(created.getRole()).isEqualTo(Role.CUSTOMER);
        assertThat(created.getPassword()).isNull();
        assertThat(created.isEnabled()).isTrue();
    }

    @Test
    void googleSignIn_existingPasswordUser_linksAccount_noDuplicate() throws Exception {
        seedCustomer(); // customer@it.local / Customer123!
        stubGoogleToken("fake-token-2", "sub-2", "customer@it.local", true, "Existing", "Customer");

        ResponseEntity<Map> resp = rest.exchange("/auth/google", HttpMethod.POST,
                new HttpEntity<>(Map.of("idToken", "fake-token-2")), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Integer userCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, "customer@it.local");
        assertThat(userCount).isEqualTo(1);
        Integer oauthCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM oauth_accounts WHERE provider_user_id = ?", Integer.class, "sub-2");
        assertThat(oauthCount).isEqualTo(1);
    }

    @Test
    void googleSignIn_returningGoogleIdentity_logsIn_noDuplicate() throws Exception {
        stubGoogleToken("fake-token-3", "sub-3", "returning@example.com", true, "Returning", "User");
        rest.exchange("/auth/google", HttpMethod.POST, new HttpEntity<>(Map.of("idToken", "fake-token-3")), Map.class);

        stubGoogleToken("fake-token-3b", "sub-3", "returning@example.com", true, "Returning", "User");
        ResponseEntity<Map> resp = rest.exchange("/auth/google", HttpMethod.POST,
                new HttpEntity<>(Map.of("idToken", "fake-token-3b")), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Integer userCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, "returning@example.com");
        assertThat(userCount).isEqualTo(1);
    }

    @Test
    void setPassword_thenPasswordLoginWorks() throws Exception {
        stubGoogleToken("fake-token-4", "sub-4", "setpw@example.com", true, "Set", "Pw");
        ResponseEntity<Map> googleResp = rest.exchange("/auth/google", HttpMethod.POST,
                new HttpEntity<>(Map.of("idToken", "fake-token-4")), Map.class);
        String googleToken = (String) googleResp.getBody().get("token");

        ResponseEntity<Void> setPwResp = rest.exchange("/auth/set-password", HttpMethod.POST,
                new HttpEntity<>(Map.of("newPassword", "brandNewPassword1"), auth(googleToken)), Void.class);
        assertThat(setPwResp.getStatusCode().value()).isEqualTo(200);

        String loginToken = login("setpw@example.com", "brandNewPassword1");
        assertThat(loginToken).isNotNull();
    }

    @Test
    void passwordLogin_googleOnlyAccount_failsCleanlyWith401() throws Exception {
        stubGoogleToken("fake-token-5", "sub-5", "googleonly@example.com", true, "Google", "Only");
        rest.exchange("/auth/google", HttpMethod.POST, new HttpEntity<>(Map.of("idToken", "fake-token-5")), Map.class);

        ResponseEntity<Map> resp = rest.exchange("/auth/login", HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "googleonly@example.com", "password", "whatever123")), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(401);
    }

    private void stubGoogleToken(String tokenString, String subject, String email, boolean emailVerified,
                                  String firstName, String lastName) throws Exception {
        GoogleIdToken idToken = mock(GoogleIdToken.class);
        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setSubject(subject);
        payload.setEmail(email);
        payload.setEmailVerified(emailVerified);
        payload.put("given_name", firstName);
        payload.put("family_name", lastName);
        when(idToken.getPayload()).thenReturn(payload);
        when(googleIdTokenVerifier.verify(tokenString)).thenReturn(idToken);
    }
}
```

Add the import `import com.enunas.backend.user.Role;` to this file (needed for the `assertThat(created.getRole()).isEqualTo(Role.CUSTOMER)` assertion).

- [ ] **Step 3: Run tests to verify they pass**

Run: `./mvnw test -Dtest=GoogleAuthIntegrationTest` (from `backend/`)

Expected: PASS (5/5 tests green). The integration test boots a full Spring context against Testcontainers — allow it real time to run.

- [ ] **Step 4: Run the full backend suite**

Run: `./mvnw test` (from `backend/`)

Expected: every test across the whole module passes, except the one pre-existing, unrelated `SettlementIntegrationTest.closedPeriodGuard_...` failure (a settlement-domain calendar-rot bug, confirmed present before this plan and every other plan this session started, and unconnected to authentication). Any other failure means something in this plan broke an existing caller — find it and fix it before finishing.

- [ ] **Step 5: Leave the change in the working tree** (no commit — see banner)
