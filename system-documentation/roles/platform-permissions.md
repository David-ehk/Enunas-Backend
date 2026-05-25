# Enunas — Platform Permissions

## Overview

This document is the unified permissions reference for the Enunas platform, synthesizing both frontend UI access controls and backend API authorization into a single source of truth.

Authorization is enforced at **four independent layers**, each escalating specificity:

```
Layer 1: Frontend Route Guard         (client-side UX convenience)
Layer 2: Spring Security Route Guard  (definitive route-level access)
Layer 3: @PreAuthorize Method Guard   (defence in depth at method level)
Layer 4: Service Ownership Check      (horizontal privilege isolation)
```

---

## Role Definitions

### CUSTOMER

A registered end-user who shops on the platform.

**Created by:** `POST /auth/signup`
**Login gate:** `User.enabled = true` (set automatically at signup)
**Frontend entry point:** `/` → `/account`

### BRAND_PARTNER

A vetted vendor who lists and sells products on the marketplace.

**Created by:** `POST /brandpartner/apply`
**Login gate:** `User.enabled = true` (email verified) AND `User.adminApproved = true` (admin approved)
**Frontend entry point:** `/dashboard/vendor` (not yet implemented)

### ADMIN

A platform operator with cross-tenant authority.

**Created by:** `DataInitializer` on application startup (from `ADMIN_EMAIL` + `ADMIN_PASSWORD` env vars)
**Login gate:** Same as BRAND_PARTNER (enabled + adminApproved, seeded true)
**Frontend entry point:** `/dashboard/admin`

---

## Public (Unauthenticated) Permissions

| Action | Endpoint | Frontend Route |
|--------|---------|----------------|
| Browse homepage | — | `/` |
| Browse product catalog | `GET /products`, `/products/search`, `/products/category/**` | `/bekleidung` |
| View product detail | `GET /products/{id}` | `/bekleidung/[brand]/[slug]` |
| View brands directory | `GET /brandpartner/{id}` | `/marken` |
| Browse static/legal pages | — | `/faqs`, `/agbs`, `/impressum`, etc. |
| Add to cart | — (cart is localStorage only) | All product pages |
| View cart | — | `/cart` |
| Register as customer | `POST /auth/signup` | Registration form |
| Login | `POST /auth/login` | Login form / `/dashboard/login` |
| Apply as brand | `POST /brandpartner/apply` | Brand application form |
| Verify brand email | `POST /brandpartner/verify` | Verification form |
| Resend verification | `POST /brandpartner/resend-verification` | Verification form |
| Receive Mollie webhook | `POST /webhooks/mollie` | (server-to-server) |

---

## CUSTOMER Permissions

### Can Do

| Domain | Action | API Endpoint | Frontend Route/Component |
|--------|--------|-------------|-------------------------|
| Auth | View own user record | `GET /users/me` | AuthContext (all pages) |
| Profile | View own customer profile | `GET /customer/me` | `/account` |
| Profile | Update own customer profile | `PATCH /customer/me` | `/account` |
| Products | Browse active product catalog | `GET /products` | `/bekleidung` |
| Products | Search products | `GET /products/search` | Search bar |
| Products | Filter by category | `GET /products/category/{cat}` | Filter sidebar |
| Products | View product detail | `GET /products/{id}`, `/products/sku/{sku}` | `/bekleidung/[brand]/[slug]` |
| Products | View brand profile | `GET /brandpartner/{id}` | Brand pages |
| Products | Add to cart | localStorage only | ProductDetail, CartSidebar |
| Orders | Create order | `POST /orders` | `/checkout` |
| Orders | View own orders | `GET /orders/me` | `/account` |
| Orders | View own order by ID | `GET /orders/{id}` | Order detail |
| Orders | Request return (DELIVERED orders) | `POST /orders/{id}/return` | Order detail |
| Wardrobe | Create wardrobe item | `POST /wardrobe` | `/saved-lists` |
| Wardrobe | View own wardrobe | `GET /wardrobe` | `/saved-lists` |
| Wardrobe | Update wardrobe item | `PATCH /wardrobe/{id}` | `/saved-lists` |
| Wardrobe | Delete wardrobe item | `DELETE /wardrobe/{id}` | `/saved-lists` |
| Media | View product images/videos | `GET /media/products/{id}/images` | Product pages |

### Cannot Do

| Restricted Action | Why | Enforcement |
|------------------|-----|------------|
| Create, update, or delete products | Product management is brand-exclusive | Route guard: BRAND_PARTNER only |
| Access admin dashboard | Role restriction | Frontend guard + Spring Security |
| View other customers' orders | Privacy isolation | OrderService.assertOwnership() |
| View other customers' profiles | Privacy isolation | No cross-customer endpoint |
| Modify other customers' wardrobe | Privacy isolation | WardrobeService.assertOwnership() |
| Approve or reject returns | Admin authorization required | @PreAuthorize ADMIN |
| Process refunds | Admin financial operation | @PreAuthorize ADMIN |
| View brand economics, ledger, or payouts | Financial isolation | No endpoint exposed |
| Access brand partner routes | Role restriction | Spring Security BRAND_PARTNER |
| Create multiple accounts with same email | DB unique constraint | Unique index on users.email |

---

## BRAND_PARTNER Permissions

### Can Do

| Domain | Action | API Endpoint | Notes |
|--------|--------|-------------|-------|
| Auth | View own user record | `GET /users/me` | All authenticated |
| Brand | View own brand profile | `GET /brandpartner/me` | Own brand via authenticated user |
| Brand | Update own profile | `PATCH /brandpartner/me` | description, logoUrl, socialHandles, contactEmail |
| Brand | View any brand by ID | `GET /brandpartner/{id}` | Public brand profiles |
| Products | Create product | `POST /products/create` | Sets creator=user, brand=brandPartner |
| Products | View own products | `GET /products/my` | Filtered to creator=user |
| Products | Update own product | `PUT /products/update/{id}` | Ownership check enforced |
| Products | Delete own product | `DELETE /products/delete/{id}` | Ownership check enforced |
| Products | Browse all active products | `GET /products`, etc. | Same as customer |
| Variants | Manage product variants | Via product update DTO | Sizes, colors, stock quantities |
| Media | Upload product images | `POST /media/products/{id}/images` | Ownership check via product |
| Media | Upload product videos | `POST /media/products/{id}/videos` | Ownership check via product |
| Listings | Create listing | `POST /listings` | Sets price/discount/availability |
| Listings | Update listing | `PUT /listings/{id}` | Ownership via variant → product |
| Listings | Delete listing | `DELETE /listings/{id}` | Ownership via variant → product |
| Orders | View brand's orders | `GET /brand/orders` | Filtered to brand's product items |
| Orders | Confirm shipment | `PATCH /brand/orders/{id}/shipment` | Brand item ownership enforced |
| Orders | Report shipping problem | `POST /brand/orders/{id}/shipping-problem` | Brand item ownership enforced |

### Cannot Do

| Restricted Action | Reason | Enforcement |
|------------------|--------|------------|
| Edit another brand's products | Tenant isolation — marketplace integrity | ProductService.assertOwnership() |
| View another brand's orders | Commercial confidentiality | JPQL filter: brand creator ID |
| View own brand economics / balances | Admin-only financial visibility | No endpoint exposed to brand |
| Set own payout profile (IBAN) | Fraud prevention — admin manages banking | Admin-only endpoint |
| Generate own payouts | Admin-initiated financial operation | @PreAuthorize ADMIN |
| Create orders (shop) | Brands are sellers, not buyers | @PreAuthorize CUSTOMER |
| Approve own brand application | Conflict of interest | Admin-only endpoint |
| Change own brand name or slug | Immutable after creation — financial lineage | No editable field in DTO |
| Access customer PII beyond order snapshot | Customer data protection | No endpoint exposed |
| Access admin dashboard | Role restriction | Frontend guard + Spring Security |
| Modify stock atomically post-payment | Stock decrements are atomic/idempotent | Only admin state machine can reverse |

### Brand Status States

| Status | Login Allowed | Dashboard Access | Products Visible |
|--------|:---:|:---:|:---:|
| `PENDING_REVIEW` | ❌ | ❌ | ✅ (pre-existing) |
| `ACTIVE` | ✅ | ✅ | ✅ |
| `SUSPENDED` | ❌ | ❌ | ✅ (unaffected) |
| `REJECTED` | ❌ | ❌ | ✅ (unaffected) |

---

## ADMIN Permissions

### Can Do

| Domain | Action | API Endpoint |
|--------|--------|-------------|
| Users | List all users | `GET /users` |
| Brands | List all brands | `GET /admin/brands` |
| Brands | Approve brand | `PATCH /admin/brands/{id}/approve` |
| Brands | Reject brand | `PATCH /admin/brands/{id}/reject` |
| Brands | Suspend brand | `PATCH /admin/brands/{id}/suspend` |
| Brands | Set payout profile (IBAN) | `PATCH /admin/brands/{id}/payout-profile` |
| Products | List all products (all statuses) | `GET /admin/products` |
| Products | Update any product | `PUT /admin/products/{id}` |
| Products | Delete any product | `DELETE /admin/products/{id}` |
| Products | Approve product | `PATCH /admin/products/{id}/approve` |
| Products | Reject product with reason | `PATCH /admin/products/{id}/reject` |
| Products | Hide product | `PATCH /admin/products/{id}/hide` |
| Orders | List all orders | `GET /admin/orders` |
| Orders | Filter orders by status | `GET /admin/orders?status=X` |
| Orders | Update order status | `PATCH /admin/orders/{id}/status` |
| Orders | Cancel PENDING order | `POST /admin/orders/{id}/cancel` |
| Returns | Approve return | `POST /admin/orders/{id}/return/approve` |
| Returns | Confirm goods received | `POST /admin/orders/{id}/return/receive` |
| Returns | Process refund via Mollie | `POST /admin/orders/{id}/return/refund` |
| Payouts | Generate payout records | `POST /admin/payouts/generate` |
| Payouts | List payouts | `GET /admin/payouts` |
| Payouts | Approve payout | `PATCH /admin/payouts/{id}/approve` |
| Payouts | Mark payout as paid | `POST /admin/payouts/{id}/mark-paid` |
| Payouts | Cancel payout | `PATCH /admin/payouts/{id}/cancel` |
| Payouts | View payout dashboard | `GET /admin/payouts/dashboard` |
| Customers | List all customers | `GET /admin/customers` |
| Customers | View customer by ID | `GET /admin/customers/{id}` |
| Customers | Update customer profile | `PATCH /admin/customers/{id}` |
| Customers | View brand spending analytics | `GET /admin/customers/{id}/spending` |
| Reconciliation | Check all brands for ledger drift | `GET /admin/reconciliation` |
| Reconciliation | Check single brand | `GET /admin/reconciliation/brand/{id}` |
| Reconciliation | Rebuild brand economics from ledger | `POST /admin/reconciliation/rebuild/{id}` |

### Cannot Do

| Restricted Action | Reason |
|------------------|--------|
| Transfer product ownership between brands | Breaks ledger/financial lineage — no endpoint by design |
| Change a user's role | Role is immutable after creation — no endpoint |
| Modify `LedgerEntry` records | Append-only — financial compliance requirement |
| Log in as another user | No impersonation mechanism |
| Create additional admin accounts via API | Must be done directly in database |
| Reassign `Order.buyer` | Order snapshot integrity |

---

## Access Summary Matrix

| Feature / Resource | Unauth | CUSTOMER | BRAND_PARTNER | ADMIN |
|-------------------|:---:|:---:|:---:|:---:|
| Browse catalog | ✅ | ✅ | ✅ | ✅ |
| View product detail | ✅ | ✅ | ✅ | ✅ |
| Add to cart | ✅ | ✅ | ✅ | — |
| Place order | ❌ | ✅ | ❌ | ❌ |
| View own orders | ❌ | ✅ | ❌ | — |
| Request return | ❌ | ✅ | ❌ | ❌ |
| Manage wardrobe | ❌ | ✅ | ❌ | ❌ |
| Create products | ❌ | ❌ | ✅ | — |
| Manage own products | ❌ | ❌ | ✅ | — |
| Manage own listings | ❌ | ❌ | ✅ | — |
| View own brand's orders | ❌ | ❌ | ✅ | — |
| Confirm shipment | ❌ | ❌ | ✅ | ❌ |
| Admin dashboard | ❌ | ❌ | ❌ | ✅ |
| Approve/reject brands | ❌ | ❌ | ❌ | ✅ |
| Moderate products | ❌ | ❌ | ❌ | ✅ |
| Manage all orders | ❌ | ❌ | ❌ | ✅ |
| Process refunds | ❌ | ❌ | ❌ | ✅ |
| Manage payouts | ❌ | ❌ | ❌ | ✅ |
| Manage all customers | ❌ | ❌ | ❌ | ✅ |
| Run reconciliation | ❌ | ❌ | ❌ | ✅ |
