# Enunas — Role Capabilities Reference

## CUSTOMER

### Identity & Lifecycle

- Created via `POST /auth/signup` (public endpoint)
- Login available immediately after signup (`enabled = true` set at creation)
- Has a linked `Customer` entity created automatically at signup
- Role is permanent — cannot be upgraded to BRAND_PARTNER via API
- No admin approval required

### Core Capabilities

**Shopping**
- Browse the full active product catalog
- Search products by keyword across name, brand, description
- Filter products by category (CLOTHING, SHOES, ACCESSORIES, PERFUME) and gender
- View full product detail including variants, images, videos, complete-the-look
- Add items to cart (client-side, persisted in localStorage)
- Remove items from cart, adjust quantities
- Proceed to checkout (inline login prompt if unauthenticated)

**Ordering**
- Submit orders with shipping address across 6 supported countries (DE, AT, CH, NL, BE, LU)
- Each order item references a `listingId` — price is snapshotted at checkout
- Stock is checked (not reserved) at order creation
- Payment is processed via Mollie hosted checkout page (redirect)
- View own full order history
- View own order detail including status, tracking, items
- Request a return for any DELIVERED order (reasons: DEFECTIVE, WRONG_SIZE, WRONG_COLOR, NOT_AS_DESCRIBED, CHANGED_MIND, DUPLICATE)

**Account**
- View and update own customer profile (name, sizing preferences, style, location)
- Manage own wardrobe/wishlist items (create, view, update, delete)
- View saved items at `/saved-lists`

### Restrictions (with Reasoning)

| Cannot Do | Reason |
|-----------|--------|
| Create/edit products | Product management is brand-exclusive — marketplace separation of buyer/seller roles |
| Access other customers' orders or profiles | Privacy and permission isolation — horizontal privilege prevention |
| Approve or reject own return | Admin authorization required to prevent self-service abuse |
| Process own refund | Financial operations require platform oversight to prevent fraud |
| Access brand economics, ledger data | Commercial confidentiality and financial isolation |
| Access admin dashboard | Role restriction — admin authority requires specific account type |
| Create multiple accounts with same email | Unique email constraint prevents duplicate identity abuse |

---

## BRAND_PARTNER

### Identity & Lifecycle

- Created via `POST /brandpartner/apply` (public endpoint)
- **Two-gate access control:**
  1. Email verification (6-digit code, 15-minute TTL) → `User.enabled = true`
  2. Admin approval → `User.adminApproved = true` and `BrandPartner.status = ACTIVE`
- Both gates must pass before login is permitted
- Has a linked `BrandPartner` entity with associated `BrandEconomics` (default 18% commission)
- Role and brand identity are permanent after creation

### Brand Status Lifecycle

```
PENDING_REVIEW  ──(admin approves)──→  ACTIVE
PENDING_REVIEW  ──(admin rejects)───→  REJECTED
ACTIVE          ──(admin suspends)──→  SUSPENDED
SUSPENDED       ──(admin approves)──→  ACTIVE (re-activation)
```

### Core Capabilities

**Profile Management**
- View own brand profile including status, economics (admin-only), social links
- Update own profile: description, logoUrl, websiteUrl, instagramHandle, tiktokHandle, country, contactEmail
- `brandName` and `slug` are **immutable** after creation (financial lineage)

**Product Management**
- Create products with full variant structure (colors × sizes × stock quantities)
- Each product creation auto-generates unique 8-char hex SKU per color group
- Update own products (ownership enforced: `product.creator_id == user.id`)
- Delete own products (ownership enforced; cascades to variants, colors, media)
- View own products via `/products/my` (filtered to own creator ID)
- Enable "Complete the Look" feature (link 1-4 related products)
- Browse full active catalog (same as customer)

**Listing Management**
- Create price configurations per variant (price, discountPrice, currency, availableFrom, availableUntil, dropDate, region)
- Update and delete listings
- Listings control when and at what price a variant is purchasable

**Media Management**
- Upload product images (primary flag, display order)
- Upload product videos (title, thumbnail)
- Ownership enforced via parent product

**Order Fulfillment**
- View orders containing own brand's products (filtered query — brand cannot see other brands' items)
- Confirm shipment: set carrier + tracking number → Order.status: PAID → SHIPPED
- Report shipping problems: set problem description → Order.status: PAID → SHIPPING_PROBLEM
- Multi-brand orders: brand sees full order but can only act on own items

### Restrictions (with Reasoning)

| Cannot Do | Reason |
|-----------|--------|
| Edit another brand's products | Tenant isolation — marketplace integrity |
| View another brand's orders | Commercial confidentiality — prevents competitive intelligence |
| View own financial balances or ledger | Admin-only financial visibility — prevents disputes over commission calculations |
| Set own payout profile (IBAN) | Fraud prevention — admin manages bank details to prevent self-dealing |
| Generate own payouts | Admin-initiated financial operation — prevents unauthorized fund extraction |
| Create orders | Brands are sellers, not buyers in this marketplace |
| Approve own brand application | Conflict of interest — undermines the quality gate |
| Stock adjustment after payment webhook | Atomic stock decrements cannot be reversed by brand — only admin state machine |
| Access customer PII beyond order snapshot | Customer data protection |

---

## ADMIN

### Identity & Lifecycle

- Created at application startup by `DataInitializer` (not via API)
- Uses `ADMIN_EMAIL` and `ADMIN_PASSWORD` environment variables
- No Admin entity — identified purely by `User.role = ADMIN`
- Additional admin accounts must be created directly in the database
- Cannot be created via any API endpoint
- Login gate: same as BRAND_PARTNER (enabled + adminApproved), seeded as true

### Core Capabilities

**Brand Governance**
- View all brand applications (paginated)
- Approve brand: sets `status=ACTIVE`, `adminApproved=true`, sends approval email
- Reject brand: sets `status=REJECTED`, no email
- Suspend brand: sets `status=SUSPENDED`, blocks login, leaves products/orders intact
- Set brand payout profile: creates/updates IBAN and bank holder name

**Product Moderation**
- View all products across all brands (including REJECTED, SUSPENDED)
- Approve product: `status=ACTIVE`, records `moderatedBy`, `moderatedAt`
- Reject product: `status=REJECTED`, stores `rejectionReason`, records moderation
- Hide product: `status=SUSPENDED`, hidden from customer catalog, reversible
- Update any product: no ownership check (platform authority for content correction)
- Delete any product: hard delete with cascade — **irreversible**

**Order Operations**
- View all orders across all customers (paginated, filterable by status)
- Force status transitions (state machine validates allowed transitions)
- Cancel PENDING orders: sends cancellation email to customer
- Manage full return lifecycle: approve → receive → refund

**Financial Operations**
- Generate payout records for all brands with available balance
- Approve and mark payouts as paid (two-step authorization)
- Cancel payouts at any pre-PAID state
- View payout dashboard (counts, amounts, negative balance alerts)
- Run reconciliation: detect drift between ledger and BrandEconomics
- Rebuild brand economics from ledger (non-destructive repair)

**Customer Management**
- List all customers (paginated)
- View customer profile including preferences, sizing, spending history
- Update customer profile
- View brand spending analytics per customer

### Restrictions (with Reasoning)

| Cannot Do | Reason |
|-----------|--------|
| Transfer product ownership between brands | Breaks financial audit trail — ledger entries are brand-indexed |
| Change a user's role | Role is immutable; associated entities (Customer/BrandPartner) would become inconsistent |
| Modify LedgerEntry records | Append-only immutability is a financial compliance requirement |
| Log in as other users | No impersonation mechanism — security boundary |
| Create additional admin accounts via API | Must go through database to prevent unauthorized admin escalation |
| Assign Brand A's products to Brand B | Ownership boundary integrity — equivalent to ownership transfer |

---

## Moderator Role (Not Implemented)

There is no dedicated `MODERATOR` role in the current implementation. All moderation is performed by the `ADMIN` role.

**If added in future, a MODERATOR role should:**
- Have access to `/admin/products/**` (product moderation queue)
- Have access to `/admin/brands/**` (brand application review)
- NOT have access to `/admin/payouts/**` (financial operations)
- NOT have access to `/admin/reconciliation/**` (financial audit)
- NOT have access to order cancellation or refund endpoints

**Implementation path:**
1. Add `MODERATOR` to `Role` enum
2. Update `SecurityConfiguration` to allow MODERATOR on `/admin/products/**` and `/admin/brands/**`
3. Update `@PreAuthorize` on relevant `AdminService` methods
4. Create moderator-specific response DTOs if needed (exclude financial data)
