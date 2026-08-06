# Google OAuth Authentication — Design

## Context

Sub-project 2 of 2, decomposed from a combined "Google OAuth + checkout security" request. Sub-project 1 (checkout address handling) is complete and merged. This design is independent of it — the only shared surface is the existing `User`/`Customer` entities and `AuthenticationService`/`JwtService`, none of which sub-project 1 touched.

## Goals

1. Backend-verified Google Sign-In: the frontend does Google Sign-In client-side and POSTs the resulting ID token; the backend verifies it (signature, issuer, audience, expiration, email-verified) and never trusts any frontend-supplied identity claim directly.
2. No duplicate accounts: the same email (or the same returning Google identity) must always resolve to the same `User` row, whether they first signed up with a password, with Google, or are returning via Google again.
3. Password-optional accounts: a Google-only user has `password = null` and can later add one via a "set password" endpoint, at which point they can use either method.
4. Zero new authentication machinery downstream: Google Sign-In produces the exact same JWT, through the exact same `JwtService`, that email/password login already produces. The JWT filter, `@AuthenticationPrincipal`, and every `@PreAuthorize` check remain completely unaware of how the session started.

## Non-goals

- No other OAuth providers right now (structured to add one later — see Data Model — but only Google ships).
- No unlink-Google or remove-password endpoint in this plan (see Authentication Method Safety below) — only the structuring that makes adding one later safe.
- No changes to the existing password/reset/change-password flows beyond the one guard needed for password-optional accounts (see Password-Optional Compatibility).

## Architecture

### Token verification

New `GoogleTokenVerifier` component wraps Google's own `GoogleIdTokenVerifier` (`com.google.api-client:google-api-client` — one new Maven dependency, deliberately chosen over hand-rolling JWKS verification: getting signature/key-rotation verification wrong is a real security risk, and this is exactly the kind of thing worth not reinventing). Configured with a new required env var `GOOGLE_OAUTH_CLIENT_ID` (the frontend's registered Google client ID — verified against the token's `aud` claim). Returns a small internal payload record (`email`, `emailVerified`, `subject` [Google's stable per-account `sub` claim — this is `providerUserId`], `firstName`, `lastName`, `pictureUrl`) after Google's own library has confirmed signature, issuer, and expiration.

### Email normalization

Before any lookup or linking, the verified payload's email is normalized: trimmed, lowercased. The normalized value is used consistently for every subsequent lookup, link, and create — never the raw claim value. New user rows created via Google store the normalized email in `User.email`.

Known, accepted limitation: this normalizes the *incoming Google email*, but does not retroactively normalize *already-stored* `User.email` values from the existing password-signup path (which stores whatever case the user typed). A legacy user who signed up as `John@Example.com` and later Google-signs-in as `john@example.com` would not match on a case-sensitive lookup and could get a second account. This is a pre-existing signup-flow property, not something this plan changes — flagged here rather than silently left undiscovered, but out of scope to fix (would mean auditing/migrating existing `users.email` values, a separate concern). Noting it so it's a known, accepted boundary rather than a surprise later.

### Identity resolution order

This is the core of "no duplicate accounts," using `providerUserId` (Google's `sub`) as the primary signal and email only as a fallback — realizing what `OAuthAccount.providerUserId` is *for*, not just storing it inertly:

1. Look up `OAuthAccount` by `(provider = GOOGLE, providerUserId = sub)`. If found, this exact Google identity has signed in before — return its linked `User` directly. (Google's `sub` for a given Google account never changes, even if the person changes their Google email, so this is the strongest signal available and is checked first.)
2. Otherwise, require `emailVerified == true` on the token (reject with a clear error if false — rare, but Google does allow unverified-email accounts in some flows, and only a verified email is safe to auto-link against an existing password-based account), then look up `User` by the normalized email. If found: this is the "signed up with a password first, now uses Google" case — create and link a new `OAuthAccount` row to that existing `User` (this is the only place a new `OAuthAccount` gets created for an already-existing `User`).
3. Otherwise (no `OAuthAccount` match, no `User` match): brand-new signup. Create `User` (`role = CUSTOMER`, `password = null`, `enabled = true`, `adminApproved = true` — immediately active, since Google already verified the email, mirroring today's email/password signup which also activates immediately) + `Customer` (populated from the payload's `firstName`/`lastName`/`pictureUrl`, reusing the existing `Customer` entity rather than duplicating those fields onto `User` — `Customer` already carries `firstName`/`lastName`/`profileImageUrl` today and is already created alongside `User` at signup) + `OAuthAccount`, all in one transaction, exactly like `AuthenticationService.signup` already does for `User` + `Customer`.

### Role safety

`role = CUSTOMER` and `enabled = true` are hardcoded literals in the Google signup path — never derived from, or influenced by, anything in the verified token payload or any other external input. Google Sign-In can never create a `BRAND_PARTNER` or `ADMIN` account; brand applications still only go through `POST /brandpartner/apply`, exactly as today. A test asserts this explicitly (create via Google, assert role is CUSTOMER) rather than relying on it being true by omission.

### Data model

```
OAuthAccount { id, user (FK), provider (enum OAuthProvider { GOOGLE }), providerUserId, createdAt }
```

Database-level constraints (not just application-level checks):
- `UNIQUE (provider, provider_user_id)` — a single external Google identity must never be linkable to two different Enunas users, enforced at the DB, not just by the lookup-then-create logic above (which is itself race-safe in the common case, but the constraint is the actual guarantee under concurrent requests).
- `UNIQUE (user_id, provider)` — each user gets at most one Google connection (matches the "only one Google connection per user" fact that's true today; trivially still correct if a second provider is added later, since the constraint is per-provider, not global).

Migration `V20`: create `oauth_accounts` table with both constraints, plus `ALTER TABLE users ALTER COLUMN password DROP NOT NULL` (loosening a constraint — safe, non-destructive, no data migration needed since every existing row already has a non-null password).

### JWT / session

`POST /auth/google` (new, under the already-`permitAll()` `/auth/**` prefix — no `SecurityConfiguration` change needed) takes `{ "idToken": "..." }`. Flow: `GoogleTokenVerifier.verify(idToken)` → `AuthenticationService.loginWithGoogle(payload)` (implements the identity-resolution order above, returns a `User`) → the SAME `JwtService.generateToken(extraClaims, user)` call `AuthController.login` already uses, same `LoginResponseDto` shape. No new JWT logic, no new filter, no new principal type — this is the whole point of "the rest of the application must remain provider-agnostic."

### Password-optional compatibility

`AuthenticationService.login` (existing email/password path) must check `user.getPassword() != null` before calling `BCryptPasswordEncoder.matches(...)` — matching against a `null` encoded password throws `IllegalArgumentException` today, not a clean auth failure. A Google-only user attempting password login gets an explicit, clear error ("this account uses Google Sign-In — set a password first to also enable email login") instead of a 500.

### Set password

New authenticated endpoint `POST /auth/set-password` (same `Authentication`-parameter pattern as the existing `/auth/change-password`), usable only when `user.getPassword() == null`. A user who already has a password uses the existing `/auth/change-password` instead (which requires knowing the current one — a different, already-correct flow that this doesn't touch). Same `@Size(min=8)` validation as signup's password field.

### Authentication method safety (future-safe structuring, no new endpoint)

Documented invariant, not a currently-enforced one, since nothing in this plan removes an authentication method: **a user must never be left with neither a password nor an `OAuthAccount`.** No unlink-Google or remove-password endpoint exists in this plan — this is a forward-looking design note, not a feature. It's satisfied by construction right now (the only two things that touch authentication methods are `signup`/`loginWithGoogle`, which only ever *add* one, never remove one) — no helper method is added for a check nothing calls yet (that would be dead code). Instead, the invariant is documented clearly in javadoc on `OAuthAccount` and near `AuthenticationService.setPassword`, so that if a future unlink-Google or remove-password endpoint is ever built, its implementer sees the rule and can check `user.getPassword() != null || oAuthAccountRepository.existsByUser(user)` before allowing the removal — `OAuthAccountRepository.existsByUser` already exists naturally from other needs, so no speculative repository method is needed today either.

## Testing

- Unit tests for `GoogleTokenVerifier` — mocking the underlying `GoogleIdTokenVerifier`, covering valid token, expired token, wrong audience, `email_verified = false`.
- Unit tests for the identity-resolution order in `AuthenticationService.loginWithGoogle` — returning-by-`providerUserId`, linking-by-email for an existing password user, brand-new signup, and the explicit CUSTOMER-role-always assertion.
- Bean-validation tests for the new DTOs (Google ID-token request, set-password request) in the established style (direct `Validator`, no Spring context).
- Integration tests: `POST /auth/google` end-to-end for all three resolution paths (new user, link-to-existing, returning Google user), `POST /auth/set-password` success + already-has-password rejection, and a password-login attempt against a Google-only account returning a clean error rather than a 500.

## Open items intentionally deferred (not in scope for this plan)

- Unlink-Google / remove-password endpoints (structurally prepared for, not built).
- Retroactive normalization of existing `users.email` casing.
- Any provider other than Google.
