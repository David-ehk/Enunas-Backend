# Enunas — Ownership Boundaries

## Philosophy

Ownership in Enunas is not just an access control concept — it is a **financial and audit integrity concept**. Every resource on the platform has an immutable owner, and that ownership relationship is preserved across the full lifecycle of the platform, including after products are deleted, brands are suspended, or orders are refunded.

The guiding principle: **ownership cannot be transferred because transferring ownership would corrupt the financial audit trail.**

---

## Complete Resource Ownership Map

| Resource | Owner | Assigned By | Immutable After Creation? | Enforcement Location |
|---------|-------|------------|:---:|---------------------|
| `User` account | The user themselves | `/auth/signup` or `DataInitializer` | ✅ | No modification endpoint |
| `Customer` profile | The `User` (CUSTOMER role) | `AuthenticationService.signup()` | ✅ (link) | `CustomerService` — loaded from auth user |
| `BrandPartner` profile | The `User` (BRAND_PARTNER role) | `BrandPartnerService.applyForBrand()` | ✅ (link) | `BrandPartnerService` — loaded from auth user |
| `BrandEconomics` | The `BrandPartner` | `BrandPartnerService.applyForBrand()` | ✅ | Admin-read-only; never modified by brand |
| `BrandShippingProfile` | The `BrandPartner` | Admin-managed | ✅ (admin manages) | Admin-only write |
| `BrandPayoutProfile` | The `BrandPartner` | Admin-managed | ✅ (admin manages) | Admin-only write; fraud prevention |
| `Product` | The `BrandPartner` who created it | `ProductService.createProduct()` | ✅ (`creator` field) | `ProductService.assertOwnership()` |
| `ProductColor` | Same as parent `Product` | Within product creation transaction | ✅ (inherits) | Via Product ownership |
| `ProductVariant` | Same as parent `Product` | Within product creation transaction | ✅ (inherits) | Via Product ownership |
| `ProductListing` | Same as parent `Product` | `ProductListingService` | ✅ (inherits) | Via ProductVariant → Product → BrandPartner |
| `ProductImage` / `ProductVideo` | Same as parent `Product` | `MediaService` | ✅ (inherits) | Via Product ownership check |
| `Order` | The `Customer` who placed it | `OrderService.createOrder()` | ✅ (`buyer` field) | `OrderService.assertOwnership()` |
| `OrderItem` | Same as parent `Order` | Same transaction as Order | ✅ (inherits) | Via Order ownership |
| `ReturnOrder` | The `Customer` who requested it | `OrderService.requestReturn()` | ✅ (via Order ownership) | Via Order.buyer |
| `Payment` | System-managed | `OrderService.createOrder()` | ✅ | No customer/brand write access |
| `LedgerEntry` | System-managed (append-only) | `LedgerService` | ✅ (immutable) | INSERT only — no UPDATE/DELETE ever |
| `Payout` | System-managed for a `BrandPartner` | Admin-initiated | ✅ | Admin-only lifecycle |
| `WardrobeItem` | The `Customer` who created it | `WardrobeService.createItem()` | ✅ (`user` field) | `WardrobeService` — ownership check on all writes |

---

## Ownership Enforcement Code Patterns

### Pattern 1 — Product Ownership (Brand)

```java
// ProductService.updateProduct()
if (!product.getCreator().getId().equals(creator.getId())) {
    throw new IllegalArgumentException("You do not own this product");
}
```

**Trigger:** Any PUT `/products/update/{id}` or DELETE `/products/delete/{id}`  
**Check:** `product.creator_id == authenticated_user_id`  
**Failure:** 400 Bad Request

---

### Pattern 2 — Order Ownership (Customer)

```java
// OrderService.assertOwnership()
if (!order.getBuyer().getId().equals(buyer.getId())) {
    throw new IllegalArgumentException("Order does not belong to this user");
}
```

**Trigger:** GET `/orders/{id}`, POST `/orders/{id}/return`  
**Check:** `order.buyer_id == authenticated_user_id`  
**Failure:** 400 Bad Request

---

### Pattern 3 — Brand Order Ownership (Brand Partner)

```java
// OrderService.assertBrandOwnership()
boolean hasItem = order.getOrderItems().stream()
    .anyMatch(item -> item.getVariant().getProductColor().getProduct()
        .getCreator().getId().equals(brandUser.getId()));
if (!hasItem) {
    throw new IllegalArgumentException("Order does not contain your products");
}
```

**Trigger:** PATCH `/brand/orders/{id}/shipment`, POST `/brand/orders/{id}/shipping-problem`  
**Check:** At least one `OrderItem.variant.product.creator_id == authenticated_brand_user_id`  
**Failure:** 400 Bad Request

---

### Pattern 4 — Wardrobe Ownership (Customer)

```java
// WardrobeService
if (!item.getUser().getId().equals(user.getId())) {
    throw new IllegalArgumentException("You do not own this wardrobe item");
}
```

**Trigger:** PATCH `/wardrobe/{id}`, DELETE `/wardrobe/{id}`  
**Check:** `wardrobe_item.user_id == authenticated_user_id`  
**Failure:** 400 Bad Request

---

## Why Ownership Cannot Be Transferred

### 1. Product Ownership Transfer — BLOCKED

**Scenario:** Admin tries to reassign Product X from Brand A to Brand B.  
**Why blocked:** No endpoint exists. This is an intentional design constraint.

**Reasons:**
- Commission history is snapshotted on `OrderItem` at purchase time using `brandSnapshotName` and `commissionRate`. These records point to Brand A.
- `LedgerEntry` records are indexed by `brandPartnerId = Brand_A.id`. If Product X is moved to Brand B, historical ledger entries still reference Brand A.
- Financial reconciliation (`ReconciliationService`) would produce incorrect results because it rebuilds `BrandEconomics` from `LedgerEntries`. Moving the product does not move ledger entries.
- Result: **irreconcilable financial history and audit trail corruption**.

---

### 2. User Role Transfer — BLOCKED

**Scenario:** An admin tries to change a CUSTOMER to BRAND_PARTNER via API.  
**Why blocked:** No role-change endpoint exists.

**Reasons:**
- The `User ↔ BrandPartner` and `User ↔ Customer` OneToOne relationships are created once at account creation.
- A CUSTOMER has a `Customer` entity. A BRAND_PARTNER has a `BrandPartner` entity. Changing the role without migrating the associated entity would leave orphaned or missing data.
- Role change via API would bypass the brand partner application and email verification flow, which is the platform's brand quality gate.

---

### 3. Order Ownership Transfer — BLOCKED

**Scenario:** An admin tries to transfer Order Y from Customer A to Customer B.  
**Why blocked:** No endpoint exists.

**Reasons:**
- Order records include payment records with Mollie `transactionId`. The `transactionId` is tied to the Mollie customer payment — it belongs to the original payer.
- The shipping address is embedded in the order. Transferring ownership while the physical goods are shipping to Address A but ownership is Customer B creates a logical inconsistency.
- Order snapshots (`productSnapshotName`, `priceAtPurchase`) are immutable records of the original transaction.

---

## Admin Override Boundaries

Admin has elevated authority but is **not** exempt from immutability constraints:

| Action | Admin Authority | Reason for Limitation |
|--------|:---:|----------------------|
| Update product content (name, desc, images) | ✅ | Content correction, compliance |
| Delete product | ✅ | Irreversible — hard delete with cascade |
| Update order status | ✅ | Operational control |
| Process refund | ✅ | Financial settlement authority |
| **Transfer product to another brand** | ❌ | Financial audit integrity |
| **Change user role** | ❌ | Entity relationship integrity |
| **Modify LedgerEntry** | ❌ | Financial compliance — append-only |
| **Impersonate a user** | ❌ | No mechanism exists |

---

## Ownership Snapshot Pattern

When an order is placed, the following data is **snapshotted** into `OrderItem` to decouple order history from live catalog changes:

| Snapshot Field | Captured From | Why Snapshotted |
|---------------|-------------|-----------------|
| `productSnapshotName` | `Product.name` at purchase time | Product may be renamed or deleted later |
| `variantSnapshotSku` | `ProductColor.sku` | SKU may change via product update |
| `variantSnapshotColor` | `ProductColor.color` | Color name may be edited |
| `variantSnapshotSize` | `ProductVariant.size` | Size label may change |
| `brandSnapshotName` | `BrandPartner.brandName` | Brand may be suspended or renamed |
| `priceAtPurchase` | `ProductListing.getCurrentPrice()` | Price may change after purchase |
| `commissionRate` | `BrandEconomics.defaultCommissionRate` | Commission rate may be renegotiated |
| `platformFeeAmount` | Computed at checkout | Amount is definitively locked at checkout |
| `brandPayoutAmount` | Computed at checkout | Amount is definitively locked at checkout |

This pattern ensures that **order history is always accurate** regardless of subsequent catalog or commercial changes.

---

## Multi-Brand Order Ownership

When a customer places an order containing items from multiple brand partners:

```
Order (buyer = Customer X)
├── OrderItem 1 (product from Brand A) → Brand A sees this in /brand/orders
├── OrderItem 2 (product from Brand B) → Brand B sees this in /brand/orders
└── OrderItem 3 (product from Brand A) → Brand A sees this too

Brand A: sees full order (all items visible) but ONLY confirms shipment for its own items
Brand B: sees full order (all items visible) but ONLY confirms shipment for its own items
```

**Key rule:** Shared order visibility is intentional (both brands need to know the order number for coordination). However, each brand can ONLY act on items they own. Cross-brand actions fail the `assertBrandOwnership()` check.
