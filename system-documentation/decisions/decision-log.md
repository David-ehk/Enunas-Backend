# Architecture Decision Log

This file records all significant architecture decisions made for the Enunas platform. Each entry follows the Architecture Decision Record (ADR) format.

**Rule:** Never delete an ADR. If a decision is reversed, mark it `SUPERSEDED` and add a new ADR referencing the original.

**Related docs:**
- Ownership consequences → `../roles/ownership-boundaries.md`
- Security consequences → `../security/security-boundaries.md`
- Permissions consequences → `../roles/platform-permissions.md`
- Entity consequences → `../database/entity-boundaries.md`

---

## ADR-001 — Stateless JWT Authentication

**Date:** 2025 (initial architecture)  
**Status:** Accepted

### Decision
Use HMAC-SHA256 signed JSON Web Tokens (JWT) for authentication. No server-side session. Token TTL: 24 hours. No refresh token mechanism.

### Reason
- Horizontal scalability: any backend instance can validate a JWT without a shared session store
- Frontend compatibility: JWT suits SPAs and mobile clients (no cookie dependency)
- Operational simplicity: no Redis or sticky sessions required at MVP scale

### Consequences
- **Positive:** Stateless — scales without session affinity
- **Positive:** Self-contained — role claim in token avoids DB lookup per request (one lookup at filter for signature validation)
- **Negative:** Tokens cannot be revoked server-side until expiry (24h window after logout/password change)
- **Negative:** No refresh token means re-login after 24h
- **Negative:** XSS risk if token stored in localStorage (current implementation) — see ADR-013

### Constraints
- JWT secret must be ≥ 32 bytes when base64-decoded (`JWT_SECRET` env var)
- Token contains: `sub` (email), `role`, `iat`, `exp`
- Frontend currently stores in `localStorage["enunas_token"]` — planned migration to httpOnly cookie (ADR-013)

---

## ADR-002 — Ownership Is Immutable After Creation

**Date:** 2025 (initial architecture)  
**Status:** Accepted

### Decision
Resource ownership fields (`creator_id`, `brand_id`, `buyer_id`, `user_id`) are set at creation time and never changed via any API endpoint. No ownership transfer mechanism will be built.

### Reason
1. **Financial audit integrity**: `LedgerEntry` records are indexed by `brandPartnerId`. Moving a product to Brand B does not relocate its ledger history — financial reconciliation would produce incorrect results.
2. **Snapshot immutability**: `OrderItem` captures `brandSnapshotName`, `commissionRate`, and `brandPayoutAmount` at checkout time. These are legal/financial records tied to the original owner.
3. **Entity relationship integrity**: The `User ↔ BrandPartner` and `User ↔ Customer` OneToOne associations are defined once. No safe migration path exists without a dedicated data migration tool.

### Consequences
- **Positive:** Financial audit trail is tamper-evident — `ReconciliationService` can always rebuild from immutable ledger
- **Positive:** Simplifies service-layer ownership checks — `creator_id == user.id` is always definitive
- **Negative:** Admin cannot correct ownership assignment errors (e.g., product created under wrong brand) — requires direct DB intervention
- **Negative:** Brand mergers or acquisitions cannot be modeled within the current system

### Enforcement
- No ownership-transfer endpoint exists
- `ProductService.assertOwnership()`, `OrderService.assertOwnership()`, `WardrobeService.assertOwnership()` enforce read-time isolation
- **Reference:** `../roles/ownership-boundaries.md`, `../security/ownership-enforcement.md`

---

## ADR-003 — LedgerEntry Is Append-Only (Financial Audit Log)

**Date:** 2025 (initial architecture)  
**Status:** Accepted

### Decision
The `ledger_entries` table is INSERT-only. No UPDATE or DELETE operations are ever performed on it. `BrandEconomics` is a denormalized read model that can be rebuilt from the ledger at any time via `ReconciliationService.rebuildBrandEconomics()`.

### Reason
- Financial compliance: an immutable event log provides tamper-evident proof of all financial transactions
- Auditability: every commission calculation, refund, and payout can be traced back to individual ledger entries with external reference IDs (Mollie transaction IDs, bank transfer refs)
- Resilience: if `BrandEconomics` balances drift due to a bug, the ledger is the source of truth — no data loss occurs during repair

### Consequences
- **Positive:** Full financial audit trail — every euro can be traced
- **Positive:** Drift detection and repair possible without data loss (`ReconciliationService`)
- **Positive:** Refunds create new `REFUND_REVERSAL` entries linked to original `ORDER_PAYMENT` — original entries preserved
- **Negative:** `BrandEconomics` must be kept in sync with ledger (or rebuilt) — two sources of truth for balance state
- **Negative:** Querying current balance requires joining across multiple entry types

### Entry Types
| Type | Trigger |
|------|---------|
| `ORDER_PAYMENT` | Payment confirmed via webhook or admin |
| `REFUND_REVERSAL` | Admin processes refund |
| `PAYOUT_TRANSFER` | Admin marks payout as paid |

**Reference:** `../security/audit-system.md`

---

## ADR-004 — Stock Decrement on Payment Confirmation (Not at Order Creation)

**Date:** 2025 (initial architecture)  
**Status:** Accepted

### Decision
`ProductVariant.stockQuantity` is NEVER decremented at order creation. It is decremented atomically only when `OrderService.confirmPaymentByWebhook()` is called (payment confirmed via Mollie webhook or admin state override).

### Reason
- Prevents stock reservation for abandoned carts (customer creates order but never pays)
- Simplifies the order creation path (no rollback needed if payment fails)
- Mollie webhook is the authoritative payment confirmation signal

### Consequences
- **Positive:** No ghost reservations for unpaid orders
- **Positive:** Simpler order creation (no stock lock/unlock mechanism needed)
- **Negative:** Race condition: two customers can order the last item simultaneously. Only the first webhook to complete the atomic `UPDATE WHERE stock >= qty` succeeds — the second triggers automatic order cancellation and a manual refund flag. **This is an accepted MVP trade-off.**
- **Negative:** Between order creation and payment confirmation, the frontend may show stock available when it is temporarily committed

### Atomic Decrement Pattern
```sql
UPDATE product_variants
SET stock_quantity = stock_quantity - :qty
WHERE id = :id AND stock_quantity >= :qty
```
Returns rows updated. If `0`, stock depletion race detected → restore previous decrements → cancel order.

**Reference:** `../flows/complete-checkout-flow.puml`, `../architecture/service-boundaries.md` → Order Service Boundary

---

## ADR-005 — Logical Multi-Tenancy (Not Physical)

**Date:** 2025 (initial architecture)  
**Status:** Accepted

### Decision
All brand partners (tenants) share the same PostgreSQL database schema. Isolation is enforced at the application layer via query filtering, service-layer ownership assertions, and route-level role guards. No separate schemas or databases per tenant.

### Reason
- MVP simplicity: physical isolation requires schema routing logic or separate DB clusters
- Scale: Enunas starts with a small number of brands — physical isolation overhead not justified
- Operational: single schema easier to maintain, migrate, and back up at MVP scale

### Consequences
- **Positive:** Simple database management — single Flyway migration set, single backup
- **Positive:** Cross-tenant queries (admin dashboard, reconciliation) are straightforward SQL joins
- **Negative:** Database-level bypass (SQL injection, direct DB access) breaks isolation — mitigated by JPA parameterized queries
- **Negative:** If a tenant requires physical isolation for compliance reasons, significant refactoring is required
- **Negative:** Noisy neighbor problem (one brand's heavy query load affects all brands) — not a concern at current scale

### Isolation Mechanisms
1. JPQL query filters: `WHERE creator_id = ?`, `WHERE brand_partner_id = ?`
2. Service assertions: `assertOwnership()`, `assertBrandOwnership()`
3. Spring Security route guards: `/brand/**` requires BRAND_PARTNER role
4. No cross-tenant endpoint: `/products/my` only returns authenticated brand's products

**Reference:** `../security/tenant-isolation.md`

---

## ADR-006 — PaymentProvider Interface Abstraction

**Date:** 2025 (initial architecture)  
**Status:** Accepted

### Decision
All payment operations are behind a `PaymentProvider` interface with two implementations:
- `MolliePaymentService` — activated by default (`!mock-payments` Spring profile)
- `MockPaymentService` — activated by `mock-payments` profile for local development

### Reason
- Testability: local development does not require a Mollie account or public webhook URL
- Replaceability: switching payment providers requires only a new implementation of the interface
- Profile-based activation: no code changes required between environments

### Consequences
- **Positive:** Local development works without external dependencies
- **Positive:** Payment provider can be replaced without touching business logic
- **Negative:** Mock implementation must be kept in sync with Mollie behavior (risk of mock/prod divergence)

### Interface Contract
```java
PaymentResult createPayment(CreatePaymentCommand cmd);
RefundResult refundPayment(RefundCommand cmd);
PaymentDetails getPaymentDetails(String paymentId);
```

---

## ADR-007 — SKU Assigned at Color Level (Not Variant Level)

**Date:** 2025 (post-MVP refactor, see Flyway V1)  
**Status:** Accepted

### Decision
The SKU identifier lives on `ProductColor`, not `ProductVariant`. All size variants (S, M, L, XL) of the same color share a single 8-character hex SKU.

### Reason
The original design placed SKU on `ProductVariant` (each size had its own SKU). This was refactored because:
- Retail convention: SKU typically identifies a style × color, not style × color × size
- Frontend simplicity: one SKU per color allows URL-based product lookup by SKU without specifying size
- Ordering: customers identify by color ("Black Hoodie"), then choose size — the SKU maps to this mental model

### Consequences
- **Positive:** Frontend can look up products by SKU without knowing the size: `GET /products/sku/{sku}`
- **Positive:** Fewer SKUs to manage per product
- **Negative:** Cannot uniquely identify a specific size variant by SKU alone — need SKU + size
- **Negative:** Flyway migration required for the refactor (V1 migration script)

### Migration
`V1__product_domain_refactor.sql` handles upgrade from per-variant SKU to per-color SKU.

---

## ADR-008 — Commission Rate Snapshotted at OrderItem Level

**Date:** 2025 (initial architecture)  
**Status:** Accepted

### Decision
At checkout, the current `BrandEconomics.defaultCommissionRate` is captured into `OrderItem.commissionRate`. All downstream calculations (`platformFeeAmount`, `brandPayoutAmount`) use the snapshotted rate, not the live rate.

### Reason
- Commission rates may be renegotiated with brands over time
- Past orders must reflect the rate that was in effect when the sale occurred
- Legal and financial audit requirement: the split at time of sale is the definitive record

### Consequences
- **Positive:** Order history is always accurate regardless of future rate changes
- **Positive:** Ledger entries capture the snapshotted rate — reconciliation is always correct
- **Negative:** If a rate is applied in error, past orders cannot be "corrected" via rate change — requires admin intervention at ledger level (currently not supported)

---

## ADR-009 — Three Flat Roles, No Hierarchy (RBAC)

**Date:** 2025 (initial architecture)  
**Status:** Accepted

### Decision
Three roles: `CUSTOMER`, `BRAND_PARTNER`, `ADMIN`. Flat model — no role hierarchy, no permission inheritance, no sub-roles.

### Reason
- Three clearly distinct actor types with non-overlapping responsibilities
- No partial permissions needed within a role at MVP
- Simplicity: Spring Security `hasRole()` checks over custom permission evaluators
- ADMIN does not inherit BRAND_PARTNER restrictions — platform-wide authority is intentionally different from vendor authority

### Consequences
- **Positive:** Simple, auditable — every decision is "can this role do X? yes/no"
- **Positive:** Adding a new endpoint requires only deciding which role constant to use
- **Negative:** No moderator role (admin handles all moderation) — see ADR-014
- **Negative:** If a brand partner account also needs admin access for internal team, they need a separate admin account (no multi-role)
- **Negative:** Adding a fourth role requires changes to SecurityConfiguration, relevant @PreAuthorize annotations, and the permissions matrix

**Reference:** `../roles/platform-permissions.md`

---

## ADR-010 — Admin Is a User Role (No Separate Entity)

**Date:** 2025 (initial architecture)  
**Status:** Accepted

### Decision
There is no `Admin` entity or separate admin table. Admin is identified purely by `User.role = ADMIN`. The initial admin account is seeded at startup by `DataInitializer` using `ADMIN_EMAIL` + `ADMIN_PASSWORD` env vars.

### Reason
- Simplicity: admin is a user with elevated permissions, not a fundamentally different type of actor
- Single admin at MVP: no multi-admin management needed
- Authentication is identical to other roles (JWT-based login)

### Consequences
- **Positive:** Simple — one user model for all actors
- **Positive:** Admin uses the same login endpoint as everyone else
- **Negative:** Creating additional admin accounts requires direct database insertion — no API endpoint
- **Negative:** No admin-to-admin management or audit log of who has admin access
- **Negative:** Single shared admin account — no individual admin action attribution beyond email fields on specific entities

---

## ADR-011 — BrandEconomics as Denormalized Read Model

**Date:** 2025 (initial architecture)  
**Status:** Accepted

### Decision
`BrandEconomics` stores denormalized balance state (`pendingBalance`, `payoutBalance`, `lifetimeRevenue`, `outstandingDebt`). The authoritative source is the `ledger_entries` table. `ReconciliationService` can rebuild `BrandEconomics` from ledger at any time.

### Reason
- Performance: querying live balance from ledger requires summing across potentially thousands of entries per brand — impractical for dashboard rendering
- Simplicity: a single row per brand with current balances enables fast reads
- Safety: the ledger is the source of truth — `BrandEconomics` is always rebuildable

### Consequences
- **Positive:** Fast balance reads (single row lookup)
- **Positive:** Repairable: `POST /admin/reconciliation/rebuild/{id}` restores correctness without data loss
- **Negative:** Two sources of truth — must keep in sync on every financial event
- **Negative:** Bugs that update ledger but not BrandEconomics (or vice versa) create drift — detected by ReconciliationService

**Reference:** `../security/audit-system.md` → Reconciliation System

---

## ADR-012 — No Token Refresh Mechanism (MVP)

**Date:** 2025 (MVP scope decision)  
**Status:** Accepted (MVP), **Planned for revision**

### Decision
No refresh token endpoint exists. When the 24-hour JWT expires, the user must re-authenticate via `POST /auth/login`.

### Reason
- MVP scope reduction
- Refresh tokens add complexity: rotation, revocation, storage
- 24-hour TTL is sufficient for current use patterns

### Consequences
- **Positive:** Simpler authentication system
- **Negative:** Users are logged out after 24h with no seamless re-authentication
- **Negative:** No silent session renewal on mobile apps
- **Planned:** Implement refresh token endpoint with secure httpOnly cookie storage in a future release

---

## ADR-013 — JWT Stored in localStorage (Known Risk, Planned Migration)

**Date:** 2025 (MVP implementation)  
**Status:** Accepted (MVP), **Planned for revision**

### Decision
The frontend stores the JWT in `localStorage["enunas_token"]`. This is injected into all API requests via the `fetcher.ts` wrapper.

### Reason
- MVP simplicity: localStorage is easy to implement and debug
- No cookie setup required at frontend level for initial launch

### Consequences
- **Negative (HIGH PRIORITY):** `localStorage` is accessible to any JavaScript running on the page. An XSS vulnerability would allow an attacker to steal the JWT.
- **Negative:** The JWT can be read by third-party scripts (analytics, widgets) running in the browser context.
- **Planned migration:** Move JWT storage to an `httpOnly` cookie, which is inaccessible to JavaScript. A `TODO` comment exists in `lib/api/auth.ts`. This requires:
  1. Backend to set `Set-Cookie: enunas_token=<jwt>; HttpOnly; Secure; SameSite=Strict`
  2. Frontend to stop using `Authorization` header and rely on automatic cookie inclusion
  3. CSRF protection re-evaluation (SameSite=Strict provides significant mitigation)

### Risk Level
**Medium** in development. **High** in production if platform runs any third-party JavaScript without a Content Security Policy (CSP).

**Reference:** `../security/security-boundaries.md` → Known Security Gaps

---

## ADR-014 — No Moderator Role (Admin Handles All Moderation)

**Date:** 2025 (MVP scope decision)  
**Status:** Accepted (MVP), **Pending review at scale**

### Decision
There is no `MODERATOR` role. All content moderation (product approval/rejection, brand approval) is performed by the `ADMIN` role.

### Reason
- MVP: single admin handles moderation volume
- Adds complexity to add a fourth role (security config, permission matrix, capability docs)
- Moderator vs admin permission split is non-trivial (moderator needs brand/product access but not financial access)

### Consequences
- **Positive:** Simpler permission model
- **Negative:** Admin is a bottleneck for all moderation decisions
- **Negative:** No separation of duties between financial admin and content moderator
- **If MODERATOR role is added:** See `backend-documentation/roles/moderator-capabilities.md` for the proposed capability split

---

## ADR-015 — Product Status ACTIVE by Default (Post-Creation Moderation)

**Date:** 2025 (initial architecture)  
**Status:** Accepted

### Decision
Products are created with `status = ACTIVE` and are immediately visible in the public catalog. Admin can retroactively reject or hide products. There is no pre-publication moderation gate.

### Reason
- MVP speed: requiring admin approval before listing would bottleneck brand onboarding
- Admin workload: pre-moderation of every product is unsustainable at scale
- Trust model: brand partners have already been admin-approved — their products receive initial trust

### Consequences
- **Positive:** Brands can list products immediately after creation
- **Positive:** No admin bottleneck for catalog growth
- **Negative:** Non-compliant products may be briefly visible before admin review
- **Negative:** Platform bears reputational risk for the window between product creation and admin review
- **Mitigation:** Admin can monitor new products via `GET /admin/products` and react quickly to flag violations

**Reference:** `../flows/vendor-product-lifecycle.puml`

---

## ADR-016 — Cart Is Client-Side Only (localStorage)

**Date:** 2025 (MVP scope decision)  
**Status:** Accepted (MVP)

### Decision
The shopping cart is stored exclusively in `localStorage` on the frontend. There is no `cart` table, no `cart` API endpoint, and no server-side cart persistence.

### Reason
- MVP simplicity: server-side cart requires cart entity, expiry logic, user association
- No cart abandonment analytics needed at MVP
- Cart items reference listings — prices are validated fresh at checkout, not at add-to-cart time

### Consequences
- **Positive:** Zero backend complexity for cart management
- **Positive:** Cart works for unauthenticated users (add items, then login at checkout)
- **Negative:** Cart is lost when localStorage is cleared or on different devices
- **Negative:** No cart abandonment email campaigns possible
- **Negative:** Price shown in cart may be stale if listing price changes before checkout — price is validated fresh at `POST /orders` creation

---

## ADR-017 — Product Catalog Open to Unauthenticated Users (Frontend Public, Backend Requires JWT)

**Date:** 2025 (initial architecture)  
**Status:** Accepted

### Decision
The frontend product catalog pages (`/bekleidung`, product detail pages, brand pages) are publicly accessible without login. However, the corresponding backend endpoints (`GET /products`, `GET /products/{id}`, etc.) require a valid JWT.

### Reason
- SEO: product pages should be crawlable by search engines
- UX: unauthenticated browsing before registration is a standard e-commerce pattern
- Security: backend JWT requirement is maintained as the authoritative guard

### Consequences
- **Positive:** Public catalog browsing works without an account
- **Positive:** SEO-friendly server-rendered pages (Next.js App Router RSC)
- **Negative:** Frontend makes API calls using a "public" or unauthenticated fetch path — backend must accommodate this. **Currently, backend requires JWT for GET /products — this creates a tension with the frontend's public browsing goal.**
- **Gap / TODO:** The backend `GET /products` endpoint requires authentication but the frontend wants to serve it publicly. Resolution options: (1) move catalog endpoints to `/public/**` prefix, (2) issue a guest token, (3) use Next.js server-side fetch with a service account token. See `decisions/architecture-gaps.md` → GAP-015.
