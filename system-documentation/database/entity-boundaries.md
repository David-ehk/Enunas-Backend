# Enunas — Entity Boundaries

## Entity Boundary Philosophy

An entity boundary defines: what a domain entity owns, what it references externally, and where its data should not bleed into another domain. In Enunas, entity boundaries correspond to service-layer package boundaries.

---

## Domain Boundaries Map

```
┌──────────────────────────────────────────────────────────────────────┐
│  IDENTITY DOMAIN                                                      │
│  users                                                                │
│  ├── user_id (PK) — central identity anchor                          │
│  └── role — CUSTOMER | BRAND_PARTNER | ADMIN                         │
│                                                                       │
│  Owned by: AuthService, UserService                                   │
│  Never modified by: any domain service (immutable after creation)     │
└──────────────────────────────────────────────────────────────────────┘
          ↓ OneToOne                    ↓ OneToOne
┌──────────────────────┐    ┌──────────────────────────────────────────┐
│  CUSTOMER DOMAIN      │    │  BRAND DOMAIN                             │
│  customers            │    │  brand_partners                           │
│  ├── user_id (FK)     │    │  ├── user_id (FK)                         │
│  ├── profile data     │    │  ├── brand_name, slug (UNIQUE, IMMUTABLE) │
│  ├── sizing prefs     │    │  ├── status (lifecycle)                   │
│  ├── total_orders     │    │  └── contact/social fields                │
│  └── total_spent      │    │                                           │
│                       │    │  brand_economics (1:1)                    │
│  wardrobe_items       │    │  ├── commission_rate                      │
│  ├── user_id (FK)     │    │  ├── pending/payout/lifetime balances     │
│  └── item fields      │    │  └── outstanding_debt                     │
│                       │    │                                           │
│  Owned by:            │    │  brand_shipping_profiles (1:N)            │
│  CustomerService      │    │  brand_payout_profiles (1:1)              │
│  WardrobeService      │    │                                           │
│                       │    │  Owned by: BrandPartnerService            │
│  Read by: AdminService│    │  Financial fields: Admin-only             │
└──────────────────────┘    └──────────────────────────────────────────┘
                                        ↓ 1:N
              ┌──────────────────────────────────────────────────────┐
              │  CATALOG DOMAIN                                        │
              │  products                                              │
              │  ├── brand_id (FK → brand_partners)                   │
              │  ├── creator_id (FK → users) — IMMUTABLE              │
              │  ├── status (ACTIVE/SUSPENDED/REJECTED)               │
              │  ├── category, gender, product_type                   │
              │  └── moderation fields (moderatedBy, rejectionReason) │
              │                                                        │
              │  product_colors (1:N per product)                     │
              │  ├── sku (UNIQUE platform-wide)                       │
              │  └── color name                                        │
              │                                                        │
              │  product_variants (1:N per color)                     │
              │  ├── size                                              │
              │  └── stock_quantity                                    │
              │                                                        │
              │  product_images, product_videos (1:N per product)     │
              │  listings (1:N per variant — price configs)           │
              │                                                        │
              │  Owned by: ProductService, MediaService               │
              │  Listings: ProductListingService                      │
              └──────────────────────────────────────────────────────┘
                                        ↓ references via listing
              ┌──────────────────────────────────────────────────────┐
              │  ORDER DOMAIN                                          │
              │  orders                                                │
              │  ├── buyer_id (FK → users) — IMMUTABLE               │
              │  ├── status (state machine)                           │
              │  ├── financial totals                                 │
              │  └── shipping address (embedded)                      │
              │                                                        │
              │  order_items (1:N per order) — SNAPSHOT              │
              │  ├── variant_id (FK → product_variants)              │
              │  ├── productSnapshotName, variantSnapshot*           │
              │  ├── brandSnapshotName                               │
              │  ├── priceAtPurchase (IMMUTABLE snapshot)           │
              │  ├── commissionRate (IMMUTABLE snapshot)             │
              │  └── platformFee, brandPayout (IMMUTABLE)            │
              │                                                        │
              │  payments (1:1 per order)                             │
              │  ├── status (PENDING/PAID/FAILED/REFUNDED)           │
              │  └── transactionId (Mollie tr_xxx)                   │
              │                                                        │
              │  return_orders (0:1 per order)                        │
              │  return_items (1:N per return)                        │
              │                                                        │
              │  Owned by: OrderService                               │
              └──────────────────────────────────────────────────────┘
                                        ↓ indexed
              ┌──────────────────────────────────────────────────────┐
              │  FINANCIAL DOMAIN (Admin-Only)                         │
              │  ledger_entries — APPEND-ONLY                         │
              │  ├── brandPartnerId (tenant isolation key)            │
              │  ├── orderId, orderItemId (references)                │
              │  ├── financial amounts (totalAmount, platformFee,     │
              │  │   brandPayout, commissionRate) — all IMMUTABLE     │
              │  ├── entryType (ORDER_PAYMENT/REFUND_REVERSAL/etc.)   │
              │  ├── status (PENDING_RELEASE/AVAILABLE/etc.)          │
              │  └── externalReferenceId (idempotency key)            │
              │                                                        │
              │  payouts                                               │
              │  ├── brandPartnerId                                   │
              │  ├── amount, iban, status                             │
              │  └── audit fields (approvedBy, paidBy, timestamps)   │
              │                                                        │
              │  Owned by: LedgerService, PayoutService              │
              │  Accessible by: AdminService only                     │
              └──────────────────────────────────────────────────────┘
```

---

## Entity Boundary Rules

### Rule 1 — Identity Domain is Immutable

The `users` table is the identity anchor. Once created:
- `users.role` is never changed
- `users.email` is never changed
- Associated `brand_partners.user_id` or `customers.user_id` is never changed

**Why:** Role change would require migrating associated entities (Customer/BrandPartner). No safe migration path exists without a dedicated admin migration tool.

---

### Rule 2 — Order Items are Snapshots

`order_items` captures point-in-time data at checkout:
- `productSnapshotName`, `variantSnapshotSku`, `variantSnapshotColor`, `variantSnapshotSize`, `brandSnapshotName`
- `priceAtPurchase`, `commissionRate`, `platformFeeAmount`, `brandPayoutAmount`

These fields are **never updated after insertion**. This decouples order history from future catalog changes — a product can be renamed, repriced, or deleted without affecting order records.

**Risk:** `order_items.variant_id` (FK) may reference a deleted variant if the product is hard-deleted. The snapshot fields provide resilience, but the FK join to live variant data will fail. **This is why `DELETE /admin/products/{id}` should only be used on products with no associated orders.**

---

### Rule 3 — LedgerEntries are Append-Only

The `ledger_entries` table is the financial source of truth:
- INSERT only — no UPDATE or DELETE
- Status changes in `BrandEconomics` are the read model (denormalized for performance)
- `ReconciliationService` can verify and rebuild `BrandEconomics` from `LedgerEntries`

**Why:** Immutable ledger entries provide tamper-evident financial history. Any discrepancy between `BrandEconomics` and `ledger_entries` is detectable and repairable without data loss.

---

### Rule 4 — Financial Data Does Not Leak Across Domains

`BrandEconomics`, `LedgerEntries`, and `Payouts` are:
- Only accessible via admin endpoints
- Never returned in brand-facing API responses
- Never visible to customer-facing endpoints

The `products` and `orders` domains contain their own financial snapshots (`order_items.priceAtPurchase`, `order_items.commissionRate`) but do not reference the financial domain entities.

---

### Rule 5 — Catalog Domain References Identity but Not Financial

The `products` table references:
- `brand_partners.id` (FK: `brand_id`) — catalog ownership
- `users.id` (FK: `creator_id`) — user-level ownership
- `users.id` (FK: `moderated_by_id`) — audit trail

But products do NOT reference `brand_economics` or `ledger_entries`. Financial calculations happen at order creation time using the brand's commission rate from `BrandEconomics`, which is passed to `OrderService` at checkout and snapshotted into `order_items.commissionRate`.

---

## Cascade Boundaries

Understanding where cascade deletes propagate is critical for safe operations:

| Parent | Child | Cascade Behavior |
|--------|-------|-----------------|
| Product | ProductColor, ProductVariant | CASCADE ALL + orphanRemoval |
| Product | ProductImage, ProductVideo | CASCADE ALL + orphanRemoval |
| Product | ProductEconomics, ProductAnalytics | CASCADE ALL + orphanRemoval |
| Order | OrderItem | CASCADE ALL + orphanRemoval |
| ReturnOrder | ReturnItem | CASCADE ALL + orphanRemoval |
| BrandPartner | BrandEconomics | CASCADE (OneToOne) |
| User | Customer | CASCADE (OneToOne) |

**Danger zones:**
1. `DELETE /admin/products/{id}` — cascades to all variants, colors, images, videos. If any `order_item.variant_id` references a variant under this product, that FK becomes dangling (not caught by DB constraint if ON DELETE is not RESTRICT for this FK).
2. No user deletion endpoint — users are never deleted (protecting order/wardrobe history).

---

## Optimistic Locking

Two entities use `@Version` for concurrent modification protection:

| Entity | Column | Protects Against |
|--------|--------|-----------------|
| `Order` | `version` | Concurrent webhook calls processing the same order |
| `Payment` | `version` | Concurrent payment confirmation (double webhook retry) |

When two requests try to update the same `Order` or `Payment` simultaneously, the second one will receive an `OptimisticLockingFailureException`, preventing data corruption.
