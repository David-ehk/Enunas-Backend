# Enunas — Tenant Isolation

## Tenancy Model

Enunas is a **multi-vendor marketplace** with **logical tenant isolation** at the application layer. All brand partners (vendors) share the same PostgreSQL database schema. Isolation is achieved through:

1. **Data scoping** — every resource carries an owner FK (`creator_id`, `brand_partner_id`, `buyer_id`, `user_id`)
2. **Query filtering** — repository queries include `WHERE creator_id = ?` / `WHERE brand_partner_id = ?`
3. **Service-layer ownership assertions** — write operations call `assertOwnership()` before proceeding
4. **Route-level guards** — Spring Security limits which role can reach which endpoint prefix

This is **logical multi-tenancy**, not physical (no separate schemas, no separate databases per brand). The trade-off is that database-level bypass (e.g., SQL injection, direct DB access) could break isolation — mitigated by parameterized JPA queries and restricted DB access.

---

## Isolation by Domain

### Product Catalog

**Shared access (intentional):**
- `GET /products` — returns ALL ACTIVE products across all brands
- `GET /products/search` — returns across all brands
- `GET /products/category/{cat}` — returns across all brands
- Reason: Customers need to discover products from all brands — this is the core marketplace function

**Isolated access:**
- `GET /products/my` — returns ONLY `WHERE product.creator_id = authenticated_user_id`
- `PUT /products/update/{id}` — asserts `product.creator.id == user.id` before proceeding
- `DELETE /products/delete/{id}` — asserts `product.creator.id == user.id` before proceeding

**Admin exception:** `GET /admin/products` returns all products across all brands — cross-tenant administrative privilege by design.

---

### Order Data

**Isolated by buyer (customer perspective):**
- `GET /orders/me` — `WHERE buyer_id = authenticated_user_id`
- `GET /orders/{id}` — loads order then checks `order.buyer.id == user.id`

**Isolated by brand (brand perspective):**
- `GET /brand/orders` — JPQL: `findByBrandPartnerCreatorId(authenticated_brand_user_id)` — returns only orders containing items created by this brand's user
- Multi-brand orders: both Brand A and Brand B can SEE the same order object (it appears in both filtered lists)
  - This is intentional — each brand needs the order number for shipment coordination
  - However, Brand A can only CONFIRM shipment for Brand A's items (`assertBrandOwnership()`)
  - Brand B can only CONFIRM shipment for Brand B's items

**Admin exception:** `GET /admin/orders` — all orders across all customers.

---

### Financial Data

**Fully isolated — brand has NO access to financial data:**

| Data | Access Level |
|------|-------------|
| `BrandEconomics` (balances, commission rate) | Admin-only (no brand-facing endpoint) |
| `LedgerEntry` records | Admin-only (no brand-facing endpoint) |
| `Payout` records | Admin-only (no brand-facing endpoint) |

**Why:** Financial data isolation prevents brands from:
- Comparing their economics to other brands
- Knowing other brands' commission rates
- Understanding platform-level financial positions
- Self-generating payouts

---

### Customer Data

**Isolated by user:**
- `GET /customer/me` — loaded from authenticated user context (no ID parameter)
- `PATCH /customer/me` — same
- `GET /wardrobe` — `WHERE user_id = authenticated_user_id`
- No endpoint exists to read another customer's profile

**Brand-customer isolation:**
- Brand partners cannot query customer profiles
- In orders, brands see shipping address and order snapshot — this is the minimum PII needed for fulfillment
- No endpoint exposes customer PII to brands beyond what's embedded in order data

---

## Isolation Mechanisms in Detail

### 1. JWT Claims (Identity Propagation)

The JWT payload contains:
```json
{
  "sub": "user@example.com",
  "role": "BRAND_PARTNER",
  "iat": 1716000000,
  "exp": 1716086400
}
```

**Note:** The JWT does NOT contain a `brandPartnerId` or `tenantId`. Tenant resolution happens on every request via a DB lookup:

```
authenticated user email
    → UserRepository.findByEmail(email)
    → BrandPartnerRepository.findByUser(user)
    → tenantId = brandPartner.getId()
```

This adds one DB query per brand-scoped request but is safe — the tenant identity is resolved from the database, not trusted from the token.

---

### 2. JPQL Query Filtering

Brand-scoped queries use parameterized JPQL with the brand's `user_id` (not `brandPartnerId`) as the isolation key:

```java
// OrderRepository — brand order query
@Query("SELECT DISTINCT o FROM Order o JOIN o.orderItems oi " +
       "WHERE oi.variant.productColor.product.creator.id = :creatorId")
Page<Order> findByBrandPartnerCreatorId(@Param("creatorId") Long creatorId, Pageable pageable);
```

```java
// ProductRepository — own products query
@Query("SELECT p FROM Product p WHERE p.creator.id = :creatorId")
List<Product> findByCreatorId(@Param("creatorId") Long creatorId);
```

All queries use JPA parameterized queries, preventing SQL injection at the query level.

---

### 3. Service-Layer Ownership Assertions

Every write operation to a brand-owned resource calls an ownership assertion:

```java
public void assertOwnership(Product product, User creator) {
    if (!product.getCreator().getId().equals(creator.getId())) {
        throw new IllegalArgumentException("You do not own this product");
    }
}
```

This check runs AFTER JWT validation and role authorization — it is the third layer of defense.

---

### 4. No Tenant ID in URLs

Brand-facing endpoints do not use a tenant-identifying path segment like `/brand/{brandId}/products`. Instead:

```
GET /products/my      ← no ID in URL — always returns current user's products
GET /brand/orders     ← no ID in URL — always returns current brand's orders
GET /brandpartner/me  ← no ID in URL — always returns current brand's profile
```

This design prevents **insecure direct object reference (IDOR)** attacks where a brand could swap their ID for another brand's ID in the URL.

---

## Cross-Tenant Access Rules

### Allowed Cross-Tenant Access

| Scenario | Allowed Because |
|---------|----------------|
| Customer browses all brands' products | Core marketplace function — product catalog is shared |
| Customer places order with items from multiple brands | Single checkout UX — multi-brand cart is supported |
| Admin views all brands' data | Platform oversight — administrative privilege |
| Admin approves/rejects any brand's products | Content moderation authority |
| Multi-brand order visible to all involved brands | Each brand needs order coordination context |

### Blocked Cross-Tenant Access

| Attempted Action | Blocking Mechanism | Response |
|-----------------|-------------------|---------|
| Brand A reads Brand B's products via `/products/my` | JPQL filter by `creator.id` | Empty result set |
| Brand A updates Brand B's product | Service-layer ownership assertion | 400 Bad Request |
| Brand A reads Brand B's order via `/brand/orders` | JPQL filter by brand's `creator.id` | Not included in results |
| Brand A ships Brand B's order items | `assertBrandOwnership()` check | 400 Bad Request |
| Brand A reads Brand B's economics | No endpoint exposed to brand role | 403 or 404 |
| Customer A reads Customer B's orders | Service-layer ownership assertion | 400 Bad Request |
| Customer A modifies Customer B's wardrobe | Service-layer ownership assertion | 400 Bad Request |

---

## Tenant Lifecycle

### Brand Suspension

When `BrandPartner.status = SUSPENDED`:
- Brand cannot log in (login gate checks `adminApproved` — suspension is handled at status level; brand's `adminApproved` remains true but login is additionally blocked by `status != ACTIVE` check)
- Existing products remain in the database (not cascade-deleted)
- Existing orders continue to be fulfilled (order history intact)
- Products may still be visible in admin view

### Brand Rejection

When `BrandPartner.status = REJECTED`:
- Brand cannot log in (`adminApproved = false` set by rejection flow)
- Same data retention as suspension

### No Tenant Deletion

There is no endpoint to delete a brand partner or its associated data. This is intentional:
- Orders placed through a brand contain financial records (`LedgerEntry`) that must be preserved
- OrderItems contain snapshot data linking to the brand
- Deleting a brand would corrupt order history

---

## Isolation Gaps and Known Risks

| Gap | Risk Level | Mitigation |
|----|-----------|-----------|
| No database-level row security (RLS) | Medium — requires app bypass to exploit | Parameterized JPA queries prevent SQLi; DB access restricted to app user |
| Shared product browsing exposes all brand names/details | Low — intentional marketplace behavior | By design |
| Multi-brand orders contain all items visible to both brands | Low — intentional, limited to order context | Each brand can only act on own items |
| No tenant ID in JWT — extra DB lookup per request | Performance (not security) | Acceptable overhead — prevents IDOR |
| Admin has full cross-tenant read/write | Admin-only risk | Admin account is single seeded account; no self-registration |
