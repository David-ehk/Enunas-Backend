# Enunas — Ownership Enforcement

## What is Ownership Enforcement?

Ownership enforcement is the set of runtime checks that prevent a valid, authenticated, correctly-roled user from accessing or modifying resources that **belong to a different user or tenant**.

It is the third layer of security (after transport security and role authorization) and specifically addresses **horizontal privilege escalation** — where User A uses their valid access rights to reach User B's data.

---

## Enforcement Architecture

```
HTTP Request
    ↓
Layer 1: JWT Authentication Filter
    → Validates token signature and expiry
    → Populates SecurityContext with authenticated user
    ↓
Layer 2: Spring Security Route Guard
    → Checks: is this role allowed on this route?
    → 403 if not
    ↓
Layer 3: @PreAuthorize Method Guard
    → Redundant role check at method level
    → 403 if not
    ↓
Layer 4: Ownership Assertion (THIS LAYER)
    → Checks: does this authenticated user OWN this specific resource?
    → 400 / 404 if not
```

---

## Enforcement Points by Resource

### Products (Brand Scope)

**Guard:** `ProductService.assertOwnership(product, authenticatedUser)`

**Triggers:**
- `PUT /products/update/{id}` — updating a product
- `DELETE /products/delete/{id}` — deleting a product
- Any media upload: `POST /media/products/{id}/images`

**Logic:**
```java
if (!product.getCreator().getId().equals(creator.getId())) {
    throw new IllegalArgumentException("You do not own this product");
}
```

**Database reference:** `products.creator_id` compared to `users.id` of authenticated user

**Failure response:** 400 Bad Request `{ "message": "You do not own this product" }`

**Admin bypass:** Calls to `/admin/products/{id}` use `AdminService` which has NO ownership check — admin has platform-wide authority over all products.

---

### Orders (Customer Scope)

**Guard:** `OrderService.assertOwnership(order, authenticatedUser)`

**Triggers:**
- `GET /orders/{id}` — viewing a specific order
- `POST /orders/{id}/return` — requesting a return

**Logic:**
```java
if (!order.getBuyer().getId().equals(buyer.getId())) {
    throw new IllegalArgumentException("Order does not belong to this user");
}
```

**Database reference:** `orders.buyer_id` compared to `users.id` of authenticated user

**Failure response:** 400 Bad Request `{ "message": "Order does not belong to this user" }`

**List query (implicit ownership):** `GET /orders/me` uses `findByBuyerId(userId)` — filtered at query level, no explicit assertion needed.

---

### Brand Orders (Brand Scope)

**Guard:** `OrderService.assertBrandOwnership(order, authenticatedBrandUser)`

**Triggers:**
- `PATCH /brand/orders/{id}/shipment` — confirming shipment
- `POST /brand/orders/{id}/shipping-problem` — reporting a problem

**Logic:**
```java
boolean hasItem = order.getOrderItems().stream()
    .anyMatch(item ->
        item.getVariant()
            .getProductColor()
            .getProduct()
            .getCreator().getId()
            .equals(brandUser.getId())
    );
if (!hasItem) {
    throw new IllegalArgumentException("Order does not contain your products");
}
```

**Database reference:** Traverses `order_items → product_variants → product_colors → products → users.creator_id` and compares to authenticated user ID

**Failure response:** 400 Bad Request `{ "message": "Order does not contain your products" }`

**Why `creator_id` not `brand_partner_id`?** The ownership assertion traverses to `product.creator.id` because the authenticated user is identified by `users.id`, not `brand_partners.id`. The brand partner entity is looked up from the user in the service layer.

---

### Wardrobe Items (Customer Scope)

**Guard:** `WardrobeService` (ownership check before write operations)

**Triggers:**
- `PATCH /wardrobe/{id}` — updating a wardrobe item
- `DELETE /wardrobe/{id}` — deleting a wardrobe item

**Logic:**
```java
if (!item.getUser().getId().equals(user.getId())) {
    throw new IllegalArgumentException("You do not own this wardrobe item");
}
```

**Database reference:** `wardrobe_items.user_id` compared to `users.id` of authenticated user

**Failure response:** 400 Bad Request

---

### Brand Profile (Self-Service)

**Pattern:** Profile is loaded FROM the authenticated user — not by ID from the URL.

```java
// BrandPartnerService.getMyProfile()
BrandPartner brand = brandPartnerRepository.findByUser(authenticatedUser)
    .orElseThrow(() -> new BrandNotFoundException(...));
return brand;
```

No explicit assertion needed — the profile is determined by the authenticated user identity. An ID parameter is never accepted on `/brandpartner/me`.

---

### Customer Profile (Self-Service)

Same pattern as brand profile:

```java
// CustomerService.getMyProfile()
Customer customer = customerRepository.findByUser(authenticatedUser)
    .orElseThrow(() -> new CustomerNotFoundException(...));
return customer;
```

No ID parameter — the customer is always the authenticated user.

---

### Listings (Brand Scope, Implicit)

Listing ownership is enforced implicitly by traversing the ownership chain:

```
ProductListing → ProductVariant → ProductColor → Product → creator_id
```

When a brand updates or deletes a listing, the service traverses this chain to confirm the listing ultimately belongs to a product owned by the authenticated user. There is no explicit `listing.owner` field — ownership is derived from the product.

---

## Ownership Enforcement at the Query Layer

For list endpoints, ownership is enforced at the SQL/JPQL query level (preventing data leakage in the result set):

| Endpoint | Query Filter |
|---------|-------------|
| `GET /products/my` | `WHERE product.creator.id = :userId` |
| `GET /orders/me` | `WHERE order.buyer.id = :userId` |
| `GET /brand/orders` | `WHERE orderItem.variant.product.creator.id = :userId` |
| `GET /wardrobe` | `WHERE wardrobeItem.user.id = :userId` |

These query-level filters are the primary defense for list endpoints. They prevent information disclosure even if the service-layer assertion were somehow bypassed.

---

## Admin Override of Ownership

The `AdminService` explicitly bypasses ownership checks:

```java
// AdminService methods use AdminController paths (/admin/**)
// These methods are only reachable with ROLE_ADMIN
// No ownership assertion is called

public AdminProductResponseDto updateProduct(Long productId, UpdateProductDto dto) {
    Product product = productRepository.findById(productId)
        .orElseThrow(() -> new ProductNotFoundException(...));
    // NO assertOwnership() call here
    applyUpdate(product, dto);
    return toResponse(product);
}
```

**Why intentional:**
- Admin has platform-wide authority over all products (content moderation)
- Admin does NOT inherit brand partner ownership rights
- Admin CANNOT modify the ownership fields (`creator_id`, `brand_id`) — these fields are not editable via any DTO
- Admin authority is operational/moderation authority, not ownership authority

---

## Ownership Fields: Immutable by Design

The following fields are NEVER updated by any API endpoint:

| Table | Column | Set By | Never Changed By |
|-------|--------|--------|-----------------|
| `products` | `creator_id` | `ProductService.createProduct()` | Anyone — no endpoint |
| `products` | `brand_id` | `ProductService.createProduct()` | Anyone — no endpoint |
| `orders` | `buyer_id` | `OrderService.createOrder()` | Anyone — no endpoint |
| `brand_partners` | `user_id` | `BrandPartnerService.applyForBrand()` | Anyone — no endpoint |
| `customers` | `user_id` | `AuthenticationService.signup()` | Anyone — no endpoint |
| `ledger_entries` | `brand_partner_id` | `LedgerService.*` | Anyone — immutable record |

These fields are never included in update DTOs. Even if an attacker submits a `creator_id` field in a JSON body, the backend ignores it because the DTO does not have that field mapped.

---

## Error Handling for Ownership Violations

All ownership violations throw `IllegalArgumentException` from the service layer:

```java
// GlobalExceptionHandler
@ExceptionHandler(IllegalArgumentException.class)
public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
    return ResponseEntity.status(400).body(new ErrorResponse(400, "Bad Request", ex.getMessage()));
}
```

This returns:
```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Order does not belong to this user"
}
```

**Note:** This returns 400 (not 404) for ownership violations. Some security standards recommend 404 ("resource not found") to avoid leaking that the resource exists. The current implementation reveals the existence of the resource but not its content. This is an acceptable trade-off for a marketplace where order numbers are already communicated to customers.
