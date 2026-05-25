# Enunas — Service Boundaries

## Overview

This document defines the boundaries between the major service areas of the Enunas platform: where each service's responsibility begins and ends, what it exposes, what it consumes, and why the boundary is drawn where it is.

---

## Boundary Map

```
┌────────────────────────────────────────────────────────────────────┐
│  FRONTEND (Next.js / Vercel)                                       │
│  Responsibility: UI rendering, client-side state, API orchestration │
│  Owns: AuthContext, CartContext, UI components, route guards        │
│  Does NOT own: authorization, business logic, financial operations  │
└───────────────────────────────┬────────────────────────────────────┘
                                │ HTTPS REST / JSON
                                │ Authorization: Bearer JWT
                                ↓
┌────────────────────────────────────────────────────────────────────┐
│  SPRING SECURITY FILTER CHAIN (Backend Entry Gate)                  │
│  Responsibility: CORS, JWT validation, route-level authorization    │
│  Owns: JwtAuthenticationFilter, SecurityConfiguration              │
│  Does NOT own: business logic, ownership checks, payment           │
└───────────────────────────────┬────────────────────────────────────┘
                                │
                    ┌───────────┴──────────┐
                    ↓                      ↓
        ┌──────────────────┐   ┌─────────────────────┐
        │  PUBLIC ZONE     │   │  AUTHENTICATED ZONE  │
        │  /auth/**        │   │  All other routes    │
        │  /brandpartner/  │   │  Role-gated          │
        │    apply/verify  │   │                      │
        │  /public/**      │   │                      │
        │  /webhooks/**    │   │                      │
        └──────────────────┘   └──────────┬──────────┘
                                           │
                          ┌────────────────┴──────────────────────┐
                          ↓                ↓                       ↓
              ┌─────────────────┐ ┌──────────────────┐ ┌─────────────────┐
              │  CUSTOMER ZONE  │ │  BRAND ZONE       │ │  ADMIN ZONE     │
              │  /orders/**     │ │  /products/create │ │  /admin/**      │
              │  /customer/**   │ │  /products/update │ │                 │
              │  /wardrobe/**   │ │  /products/delete │ │  No ownership   │
              │  /checkout/**   │ │  /brand/orders/** │ │  checks —       │
              │                 │ │  /brandpartner/me │ │  platform-wide  │
              │  Ownership:     │ │                   │ │  authority      │
              │  buyer=user     │ │  Ownership:       │ └─────────────────┘
              └─────────────────┘ │  creator=user     │
                                  └──────────────────┘
```

---

## Service Boundaries by Domain

### 1. Authentication Service Boundary

**Owns:**
- User account creation (customer + brand partner)
- Credential validation (BCrypt)
- JWT generation and signing
- Brand email verification (6-digit code, 15-min TTL)

**Does NOT own:**
- Token storage (frontend responsibility)
- Session management (stateless — no sessions exist)
- Brand approval (AdminService owns this)
- Password reset emails (not yet implemented)

**Boundary enforcement:**
- Public endpoints: `/auth/signup`, `/auth/login`
- No authentication required to reach these endpoints
- Backend does not validate JWT before these calls

---

### 2. Product Service Boundary

**Owns:**
- Product creation, update, and deletion (brand-scoped)
- Variant and color management
- SKU generation (platform-unique 8-char hex)
- Complete-the-look associations
- Product status lifecycle (ACTIVE → SUSPENDED/REJECTED via admin)

**Does NOT own:**
- Product pricing (ListingService owns this)
- Media file storage (S3/CDN — MediaService stores URLs only)
- Product moderation decisions (AdminService initiates; ProductService executes)
- Stock level tracking beyond read/write of `stockQuantity`

**Key boundary rule:**
- `ProductService.createProduct()` sets `creator=authenticatedUser` and `brand=brandPartner`
- This assignment is permanent — no reassignment endpoint exists
- All write operations after creation validate `product.creator.id == user.id`

---

### 3. Order Service Boundary

**Owns:**
- Complete order lifecycle state machine
- Order creation (listing validation, snapshot capture, shipping calculation)
- Stock decrement (atomic, on payment confirmation only)
- Stock restore (on cancellation or return received)
- Return request management
- Shipment confirmation coordination

**Does NOT own:**
- Payment processing (PaymentProvider/Mollie owns this)
- Financial ledger entries (LedgerService owns this)
- Customer notifications (EmailService owns this)
- Refund API calls (delegated to PaymentProvider)

**Key boundary rule:**
- Stock is NEVER decremented at order creation
- Stock is ONLY decremented when `confirmPaymentByWebhook()` is called
- This creates a race condition window, intentionally accepted as an MVP trade-off

---

### 4. Payment / Mollie Boundary

**Owns:**
- Payment link generation (`PaymentProvider.createPayment()`)
- Webhook receipt and validation (`MollieWebhookController`)
- Refund API calls (`PaymentProvider.refundPayment()`)

**Does NOT own:**
- Customer funds (Mollie holds and transfers these)
- Payment method selection (Mollie's hosted checkout page)
- Transaction security (Mollie's PCI compliance)

**Key boundary rule:**
- `/webhooks/mollie` is a PUBLIC endpoint (no JWT)
- Security relies on Mollie's origin verification + idempotency guards
- All payment state changes pass through `Payment.status` with optimistic locking

---

### 5. Ledger Service Boundary

**Owns:**
- All financial event creation (ORDER_PAYMENT, REFUND_REVERSAL, PAYOUT_TRANSFER)
- BrandEconomics balance updates (pendingBalance, payoutBalance, outstandingDebt)
- Idempotency enforcement (duplicate entry prevention)

**Does NOT own:**
- Payout disbursement (AdminService orchestrates, PayoutService manages records)
- Bank transfer execution (external — admin marks paid manually)
- Commission rate setting (BrandEconomics owns rate; Ledger reads it at time of sale)

**Key boundary rule:**
- LedgerEntries are APPEND-ONLY (INSERT only, no UPDATE/DELETE)
- BrandEconomics is a denormalized read model that can be rebuilt from LedgerEntries
- `ReconciliationService.rebuildBrandEconomics()` is the repair path

---

### 6. Admin Service Boundary

**Owns:**
- Brand application approval/rejection/suspension
- Product moderation (approve/reject/hide/delete)
- Order status overrides
- Return management
- Payout generation and approval workflow
- Customer profile management
- Reconciliation execution

**Does NOT own:**
- Ownership fields (cannot reassign `creator`, `brand`, `buyer`)
- Role assignments (roles are immutable after creation)
- Ledger modification (append-only)
- Individual user impersonation

**Key boundary rule:**
- Admin has no ownership restriction — platform-wide authority on all resources EXCEPT the immutable fields listed above
- This is intentional: "Admin moderation authority" ≠ "Ownership transfer authority"

---

### 7. Frontend Boundary

**Owns:**
- UI rendering and user interaction
- Client-side cart state (localStorage)
- Token storage and injection (localStorage → Authorization header)
- Route guards (client-side only, UX convenience)
- Form validation (client-side, advisory only)

**Does NOT own:**
- Authorization decisions (backend is authoritative)
- Business logic (all enforced server-side)
- Financial calculations (backend computes all amounts)
- Data persistence (except cart + token in localStorage)

**Key boundary rule:**
- Frontend route guards exist for UX (prevent unnecessary API calls, avoid UI flicker)
- ALL authorization is enforced server-side — bypassing frontend guards does not grant access
- The backend returns 401/403 for any unauthorized API call regardless of UI state

---

## Cross-Boundary Communication Rules

| From | To | Protocol | Auth |
|------|----|---------|------|
| Browser | Next.js Frontend | HTTPS (Vercel CDN) | None (public) |
| Frontend | Spring Boot | HTTPS REST/JSON | `Authorization: Bearer JWT` |
| Spring Boot | PostgreSQL | JDBC TCP:5432 | DB credentials (env var) |
| Spring Boot | Mollie API | HTTPS | `Authorization: Bearer MOLLIE_API_KEY` |
| Mollie | Spring Boot webhook | HTTPS POST | Public URL — no JWT; idempotency guard |
| Spring Boot | SMTP | SMTP/TLS:587 | MAIL_USERNAME/PASSWORD (env var) |
| Admin Browser | Spring Boot /admin/** | HTTPS REST/JSON | `Authorization: Bearer ADMIN JWT` |

---

## Boundary Violation Scenarios and Guards

| Attempted Violation | Guard | Response |
|--------------------|-------|---------|
| Customer calls `/admin/**` | Spring Security route guard + `@PreAuthorize` | 403 Forbidden |
| Brand A updates Brand B's product | `ProductService.assertOwnership()` | 400 Bad Request |
| Customer reads another customer's order | `OrderService.assertOwnership()` | 400 Bad Request |
| Expired JWT used | `JwtAuthenticationFilter.isTokenValid()` | 401 Unauthorized |
| Brand attempts to log in before admin approval | `AuthenticationService` login gate | 409 Conflict |
| Mollie webhook processed twice (retry) | `Payment.status == PAID` idempotency check | No-op, 200 OK |
| Concurrent stock depletion | Atomic SQL `WHERE stock >= qty` + row count check | Auto-cancel + manual refund flag |
