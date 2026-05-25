# Enunas — Security Boundaries

## Security Architecture Overview

Enunas implements a **defense-in-depth** security model with six independent layers. Compromising any single layer does not expose the system — an attacker must defeat multiple independent controls.

```
Layer 1: Transport Security        (HTTPS, CORS)
Layer 2: JWT Authentication Filter (token validation on every request)
Layer 3: Route-Level Authorization (Spring Security config)
Layer 4: Method-Level Authorization (@PreAuthorize on service/controller)
Layer 5: Ownership Validation      (service-layer business logic assertions)
Layer 6: Input Validation          (@Valid DTO annotations)
```

---

## Layer 1: Transport Security

### HTTPS

- HTTPS enforced in production at infrastructure level (Vercel for frontend, load balancer/reverse proxy for backend)
- Not enforced at Spring application level — relies on infrastructure

### CORS

Configured in `SecurityConfiguration`:

```java
configuration.setAllowedOrigins(Arrays.asList(
    "http://localhost:3000",       // development
    "https://deine-domain.com"    // production placeholder
));
configuration.setAllowedMethods(Arrays.asList("GET","POST","PUT","PATCH","DELETE","OPTIONS"));
configuration.setAllowedHeaders(List.of("*"));
configuration.setAllowCredentials(true);
```

**Security note:** Production `allowedOrigins` must be updated to the actual Vercel domain. The current placeholder `deine-domain.com` must NOT be deployed to production as-is.

### CSRF

CSRF protection is **disabled** because:
- All state changes require `Authorization: Bearer <JWT>` in the header
- JWT is stored in memory / localStorage (not a cookie)
- Stateless APIs have no cookie-based session token to forge
- Browsers do not auto-include `Authorization` headers in cross-site requests

---

## Layer 2: JWT Authentication Filter

`JwtAuthenticationFilter` intercepts every request:

```
Request
  ↓
Extract "Authorization: Bearer <token>"
  ↓
JwtService.extractUsername(token) → email
  ↓
SecurityContextHolder already populated?
  → YES: skip (already authenticated in this request chain)
  → NO: continue
  ↓
JwtService.isTokenValid(token, userDetails)
  → validates HMAC-SHA256 signature
  → validates exp claim (not expired)
  ↓
VALID: set UsernamePasswordAuthenticationToken in SecurityContextHolder
       (principal = UserDetails, credentials = null, authorities = [ROLE_X])
INVALID: pass request unauthenticated
         → Spring Security blocks at route level
```

**JWT Structure:**

| Claim | Value | Security Role |
|-------|-------|--------------|
| `sub` | user email | Identity binding |
| `role` | CUSTOMER / BRAND_PARTNER / ADMIN | Role authorization |
| `iat` | issued-at timestamp | Audit / anti-replay |
| `exp` | expiry timestamp | Token invalidation |

**Algorithm:** HMAC-SHA256 (`HS256`) — symmetric signature using `JWT_SECRET` env var (≥32 bytes)

**Token storage (frontend):** `localStorage["enunas_token"]`
- **Risk:** XSS vulnerability — JavaScript on the page can read localStorage
- **Planned migration:** httpOnly cookie (noted as TODO in `lib/api/auth.ts`)

**No refresh token mechanism.** When a token expires, the client must re-authenticate via `POST /auth/login`.

---

## Layer 3: Route-Level Authorization

Defined in `SecurityConfiguration.securityFilterChain()`. Applied before the request reaches any controller.

| Route Pattern | Authorization Level | Reason |
|-------------|--------------------|----|
| `/auth/**` | Public | Login and signup must be unauthenticated |
| `/public/**` | Public | Public catalog browsing |
| `/error` | Public | Spring error endpoint |
| `/webhooks/**` | Public | Mollie calls without user context |
| `/brandpartner/apply` | Public | Brand application before account exists |
| `/brandpartner/verify` | Public | Email verification before login |
| `/brandpartner/resend-verification` | Public | Resend before login |
| `/admin/**` | `ADMIN` role only | Admin-only operations |
| `/brandpartner/**` (other) | `BRAND_PARTNER` or `ADMIN` | Brand profile management |
| `/brand/**` | `BRAND_PARTNER` or `ADMIN` | Brand order management |
| `/products/create` (POST) | `BRAND_PARTNER` | Only brands list products |
| `/products/update/**` (PUT) | `BRAND_PARTNER` | Only brands update their products |
| `/products/delete/**` (DELETE) | `BRAND_PARTNER` | Only brands delete their products |
| `/customer/**` | `CUSTOMER` | Customer profile operations |
| `/orders/**` | `CUSTOMER` | Order management by buyer |
| `/checkout/**` | `CUSTOMER` | Checkout operations |
| `/wardrobe/**` | `CUSTOMER` | Wardrobe feature |
| All other authenticated | Any authenticated user | General authenticated access |

**Security failure modes:**

| Scenario | Behavior |
|---------|---------|
| Missing JWT | 401 Unauthorized (Spring Security before controller) |
| Expired JWT | 401 Unauthorized (JwtService.isTokenValid = false) |
| Invalid JWT signature | 401 Unauthorized |
| Valid JWT, wrong role for route | 403 Forbidden (Spring Security) |
| Valid JWT, correct role, wrong ownership | 400 Bad Request (service layer) |
| Brand not email-verified | 409 Conflict (AuthenticationService login gate) |
| Brand not admin-approved | 409 Conflict (AuthenticationService login gate) |

---

## Layer 4: Method-Level Authorization (@PreAuthorize)

Applied as AOP interceptors on service and controller methods. Provides defence in depth redundant with route guards.

| Class / Method | Annotation | Effect |
|--------------|-----------|--------|
| All `AdminService` methods | `@PreAuthorize("hasRole('ADMIN')")` | Entire service class |
| `BrandPartnerController` endpoints | `@PreAuthorize("hasRole('BRAND_PARTNER')")` | Endpoint-level |
| `ProductController.createProduct()` | `@PreAuthorize("hasRole('BRAND_PARTNER')")` | Method-level |
| `OrderController.createOrder()` | `@PreAuthorize("hasRole('CUSTOMER')")` | Method-level |

**Why both route guards and method guards?**
- Route guards: fast, applied before request routing
- Method guards: protection if routing is misconfigured; independent of HTTP layer
- Together they form two independent authorization checkpoints

---

## Layer 5: Ownership Validation

The most granular security layer — prevents **horizontal privilege escalation** between users of the same role.

### Product Ownership

```java
// ProductService — called before any product mutation
public void assertOwnership(Product product, User creator) {
    if (!product.getCreator().getId().equals(creator.getId())) {
        throw new IllegalArgumentException("You do not own this product");
    }
}
```

Prevents: Brand A editing Brand B's products (same BRAND_PARTNER role)

### Order Ownership

```java
// OrderService — called on all customer-facing order access
public void assertOwnership(Order order, User buyer) {
    if (!order.getBuyer().getId().equals(buyer.getId())) {
        throw new IllegalArgumentException("Order does not belong to this user");
    }
}
```

Prevents: Customer A reading Customer B's order (same CUSTOMER role)

### Brand Order Ownership

```java
// OrderService — called before shipment confirmation
public void assertBrandOwnership(Order order, User brandUser) {
    boolean hasItem = order.getOrderItems().stream()
        .anyMatch(item -> item.getVariant().getProductColor().getProduct()
            .getCreator().getId().equals(brandUser.getId()));
    if (!hasItem) throw new IllegalArgumentException("Order does not contain your products");
}
```

Prevents: Brand A shipping Brand B's items in a multi-brand order

---

## Layer 6: Input Validation

All API endpoints with request bodies apply Jakarta Bean Validation:

| Annotation | Used For | Endpoints |
|-----------|---------|----------|
| `@NotBlank` | Required string fields | All creation DTOs |
| `@Email` | Email format validation | signup, login, brandpartner/apply |
| `@Size(min=8)` | Password minimum length | signup, login |
| `@NotNull` | Required object fields | All DTOs |
| `@Min`, `@Max` | Numeric range validation | Quantity, stock fields |
| `@Valid` | DTO-level trigger | All controller methods with request body |

Validation failures return `400 Bad Request` with field-level error messages via `GlobalExceptionHandler`.

---

## Password Security

- Stored as **BCrypt** hashes only (salted, adaptive cost factor)
- `BCryptPasswordEncoder` bean in `ApplicationConfiguration`
- Minimum 8 characters enforced at DTO level
- Plaintext passwords are never logged or stored
- No password history check (not implemented)

---

## Brand Partner Email Verification (Two-Gate System)

```
Brand applies → User.enabled=false, User.adminApproved=false
    ↓
6-digit code emailed (15-minute TTL)
    ↓
Brand submits code → User.enabled=true
    ↓
Admin receives notification email
    ↓
Admin approves → BrandPartner.status=ACTIVE, User.adminApproved=true
    ↓
Brand can now log in
```

**Both gates must pass.** A brand with `enabled=true` but `adminApproved=false` cannot log in. This prevents unauthorized vendors from accessing the platform.

---

## Webhook Security

`POST /webhooks/mollie` is a public endpoint (no JWT). Security relies on:

1. **Mollie server-to-server call** — Mollie calls this URL from their infrastructure (IP filtering can be added at infrastructure level)
2. **Payment verification round-trip** — the backend immediately calls `GET /v2/payments/{id}` back to Mollie to verify the payment status (not trusted from the webhook body)
3. **Idempotency guard** — `Payment.status == PAID` check prevents double processing on retry
4. **No sensitive data returned** — webhook response is always `200 OK` with no body

**Known gap:** No IP allowlist implemented at application level for Mollie's known IP ranges.

---

## Sensitive Data Handling

| Data | Storage | Risk Notes |
|------|---------|-----------|
| Passwords | BCrypt hash only — never plaintext | Low |
| JWT secret | Environment variable `JWT_SECRET` — never in code | Medium (env var security depends on hosting) |
| Mollie API key | Environment variable `MOLLIE_API_KEY` | Medium |
| IBAN (brand) | Stored plaintext in `BrandPayoutProfile` | Admin-facing only; not customer-facing |
| Payment transaction IDs | Mollie `tr_xxx` IDs stored in `payments.transaction_id` | No card data ever touches backend |
| Card/payment details | Never stored — Mollie handles PCI compliance | None |
| Email/password in transit | HTTPS only in production | Low |

---

## Known Security Gaps

| Gap | Severity | Mitigation Path |
|----|---------|----------------|
| JWT in localStorage (XSS risk) | Medium | Migrate to httpOnly cookie (noted as TODO) |
| No server-side middleware route protection (frontend) | Low | Backend is authoritative — frontend guards are UX only |
| No rate limiting on `/auth/login` | Medium | Add Spring Security rate limiting or API gateway throttling |
| CORS `allowedOrigins` has placeholder domain | High in prod | Update before production deployment |
| No Mollie IP allowlist at app level | Low | Add infrastructure-level IP filtering |
| No login audit trail | Low | No persistent logging of successful/failed logins |
| No MFA | Low-Medium | Add TOTP or SMS factor for admin account specifically |
| Admin is single shared account | Medium | Multi-admin requires DB-level creation |
