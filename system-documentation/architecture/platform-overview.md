# Enunas — Platform Architecture Overview

## Platform Summary

Enunas is a **multi-vendor fashion marketplace** operating as a fully integrated e-commerce platform. It connects brand partners (vendors) with end customers through a curated digital storefront, with platform-level moderation, financial settlement, and logistics coordination managed by a single administrative actor.

The platform is **EUR-only**, operates in German-speaking and Benelux markets (DE, AT, CH, NL, BE, LU), and is architected for maintainability, marketplace integrity, and financial auditability.

---

## Technology Stack

| Layer | Technology | Hosting |
|-------|-----------|---------|
| Frontend | Next.js 15 (App Router) · TypeScript · Tailwind CSS v4 | Vercel |
| Backend | Spring Boot 4 (Java 21) · Spring Security · JJWT | AWS (Docker / EC2) |
| Database | PostgreSQL (Flyway migrations, Hibernate ORM) | AWS (sidecar or RDS) |
| Asset Storage | AWS S3 (product images/videos, brand media) | AWS |
| Payment Provider | Mollie (payment links, webhooks, refunds) | External SaaS |
| Email | Spring Mail via SMTP (Gmail or custom) | External SMTP |
| Build System | Maven 3 (backend) · npm/Next.js (frontend) | — |

---

## Architectural Style

### Backend

**Layered Monolith** with domain-oriented package structure:

```
HTTP Request
    ↓
Spring Security Filter Chain
    (CORS → JWT Validation → Route Authorization)
    ↓
@RestController  (input validation, HTTP mapping, DTO transformation)
    ↓
@Service         (business logic, @Transactional, ownership enforcement)
    ↓
@Repository      (Spring Data JPA, JPQL/native queries)
    ↓
PostgreSQL       (entities, constraints, optimistic locking, sequences)
```

External integrations at the service layer:
- **Mollie API** — synchronous payment creation; asynchronous webhook receipt
- **SMTP Server** — asynchronous email notifications
- **Flyway** — database migration at startup

### Frontend

**Next.js App Router (RSC + Client Components)** with a clear separation:
- Server-rendered page shells for SEO-critical routes
- Client-side API calls via `fetcher.ts` wrapper using localStorage JWT
- React Context for authentication and cart state
- No Redux, Zustand, or React Query — direct `useEffect` + `useState`

---

## Domain Architecture

### Backend Domain Packages

| Domain | Responsibility | Key Entities |
|--------|--------------|-------------|
| `auth` | Login, signup, JWT issuance | — |
| `user` | User accounts and role management | `User`, `Role` |
| `brandpartner` | Brand profiles, economics, shipping, payouts | `BrandPartner`, `BrandEconomics`, `BrandShippingProfile`, `BrandPayoutProfile` |
| `product` | Product catalog, variants, colors, analytics, media | `Product`, `ProductColor`, `ProductVariant`, `ProductImage`, `ProductVideo`, `ProductAnalytics` |
| `listing` | Price configurations per variant | `ProductListing` |
| `order` | Order lifecycle, returns, state machine | `Order`, `OrderItem`, `ReturnOrder`, `ReturnItem` |
| `payment` | Payment records, webhook handling, refund processing | `Payment`, `PaymentProvider` (interface) |
| `ledger` | Immutable financial event log | `LedgerEntry` |
| `payout` | Brand payout management and disbursement | `Payout` |
| `customer` | Customer profiles and preferences | `Customer` |
| `wardrobe` | Customer wardrobe/wishlist items | `WardrobeItem` |
| `media` | Product image and video assets | `ProductImage`, `ProductVideo` |
| `admin` | Cross-domain administration and moderation | `AdminService`, `AdminController` |
| `config` | Security, application beans, startup initialization | `SecurityConfiguration`, `DataInitializer` |
| `exception` | Global error handling | `GlobalExceptionHandler` |

### Frontend Route Groups

| Route Group | Shell | Providers | Purpose |
|------------|-------|-----------|---------|
| `app/(root)` | Customer-facing shell | `AuthProvider > CartProvider` | Storefront, catalog, checkout, account |
| `app/(root)/(footer)` | Static page shell | (none additional) | Legal and informational pages |
| `app/(dashboard)` | Minimal dashboard shell | (none) | Admin and vendor portals |

---

## Authentication Model

- **Stateless JWT** — no server-side session
- Algorithm: HMAC-SHA256 (`HS256`)
- Token payload: `sub` (email), `role`, `iat`, `exp`
- Default TTL: 24 hours (`JWT_EXPIRATION` env var)
- Storage: `localStorage["enunas_token"]` (planned migration to httpOnly cookie)
- Refresh tokens: **not implemented** — re-login required on expiry

### Role System (RBAC)

| Role | Created By | Login Gate | Primary Function |
|------|-----------|-----------|-----------------|
| `CUSTOMER` | `/auth/signup` | email exists + enabled | Purchase products, manage orders |
| `BRAND_PARTNER` | `/brandpartner/apply` | email verified + admin approved | Sell products, manage inventory |
| `ADMIN` | `DataInitializer` (startup seed) | email + enabled + adminApproved | Platform administration |

---

## Payment Architecture

```
Customer Checkout
    → OrderService.createOrder()
        → Validate listings + stock (read-only check)
        → Build OrderItems with financial snapshots
        → PaymentProvider.createPayment()
            → Mollie API → checkoutUrl
        → Save Order (PENDING) + Payment (PENDING)
        → Return checkoutUrl

Customer Pays on Mollie
    → Mollie → POST /webhooks/mollie (async)
        → MollieWebhookController
        → OrderService.confirmPaymentByWebhook()
            → Decrement stock atomically
            → LedgerService.recordOrderPayment()
            → Order.status = PAID
```

---

## Financial Flow

```
Payment Confirmed
    → LedgerEntry (ORDER_PAYMENT, PENDING_RELEASE)
    → BrandEconomics.pendingBalance += brandPayoutAmount
    → 7-day payout hold begins

Scheduled Job (after hold period)
    → LedgerEntry.status → AVAILABLE
    → BrandEconomics.pendingBalance → payoutBalance

Admin Initiates Payout
    → Payout record PENDING
    → Admin approves → APPROVED
    → Admin marks paid → PAID
    → LedgerEntry (PAYOUT_TRANSFER) recorded
    → BrandEconomics.payoutBalance decremented
```

---

## Multi-Tenant Model

Enunas operates as a **logically isolated multi-tenant marketplace**:

- All brand partners share the same database schema
- Isolation is enforced at the application layer via:
  1. Query filtering by `creator_id` / `brand_partner_id`
  2. Service-layer ownership assertions before every write
  3. Route-level role guards at Spring Security
  4. Method-level `@PreAuthorize` annotations

The platform admin has cross-tenant visibility but cannot transfer ownership between tenants.

---

## Known Platform Gaps (MVP State)

| Gap | Location | Impact |
|----|---------|--------|
| Vendor dashboard not implemented | `app/(dashboard)/dashboard/vendor/` | Brand partners cannot self-serve via UI |
| Token in localStorage (not httpOnly cookie) | `lib/api/auth.ts` | XSS risk — planned migration |
| No Next.js `middleware.ts` | Frontend root | Admin route HTML served before JS auth check |
| No token refresh mechanism | Backend `auth` package | 24h expiry requires re-login |
| No MFA / OAuth | Backend `auth` package | Basic credential security only |
| Phase 2 admin tabs (Analytics, Content, Support) | Admin dashboard | Not operational |
| Password reset not implemented | Backend `auth` package | No self-service recovery |
| No rate limiting on auth endpoints | `SecurityConfiguration` | Login brute-force risk |
| Stock reservation gap | `OrderService` | Race condition on last-item concurrent orders |
