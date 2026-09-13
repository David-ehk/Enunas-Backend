# Order-Item Image Snapshot — Thumbnail on `OrderItemResponseDto`

**Status:** Settled — architecture agreed, implementation questions only from here.
**Trigger:** Order views (customer `GET /orders/*`, brand `GET /brand/orders`, admin `GET /admin/orders`)
have no image for their line items — `OrderItemResponseDto` carries only text
(`productName`, `variantSku`, `variantColor`, `variantSize`, prices). The frontend can't render an
order thumbnail. `OrderItem` has never had a media field; this is a new capability, not a regression.

**Depends on:** the colourway-image work (`V32__product_image_colour.sql`, `ProductImage.productColor`,
`Product.getColors()` / per-colour primary). This spec's migration is **`V33`** and its thumbnail
selection reads `ProductImage.productColor`. Sequence after that work is in the tree.

## Confirmed decisions (product owner)

- **Snapshot, not live resolution.** The thumbnail is frozen at order creation, exactly like
  `productSnapshotName` / `variantSnapshotColor` / `listingIdSnapshot`. A brand later changing the
  colourway cover, or deleting the product, does not change what an existing order shows.
- **Store the S3 key, resolve at read time.** New column holds `products/<id>/images/<uuid>.jpg`;
  `OrderItemResponseDto` resolves it to a URL via `MediaUrlResolver`, the same as
  `ProductImageResponseDto`. The media design doc's rule ("keys in columns, never URLs") holds.
- **No backfill.** Existing `order_items` rows get `NULL`; the frontend shows a placeholder for
  pre-`V33` orders. Filling them from *today's* product images would be a plausible-looking lie —
  same call as `listingIdSnapshot` being left null for pre-`V28` rows.
- **One DTO, lights up everywhere.** `OrderItemResponseDto` feeds customer, brand and admin order
  views through `OrderResponseDto`; the field appears on all three at once.

## Non-goals

- **Cart / checkout preview** (`OrderPreviewResponseDto`) — pre-order, no `OrderItem` yet; the
  frontend resolves those thumbnails from product data it already has.
- **Order confirmation / shipment emails** (`OrderItemLine` + templates) — text-only today; adding
  an `<img>` there is a separate, later change.
- **Returns** (`ReturnSummaryDto`, `OrderItemLine`) — unchanged.
- Re-snapshotting an existing order's thumbnail — snapshots are immutable, no exception here.

## 1. Schema — `V33__order_item_image_snapshot.sql`

```sql
-- Order line items gain a purchase-time thumbnail. Nullable, no backfill: pre-V33 rows and any
-- line whose product had no image at purchase stay NULL, and the frontend placeholders them.
-- Same snapshot discipline as product_snapshot_name / listing_id_snapshot on this table.
-- V0.0.1..V32 are left untouched (editing an applied migration breaks its Flyway checksum).

ALTER TABLE order_items
    ADD COLUMN IF NOT EXISTS variant_snapshot_image_key varchar(512);
```

No index — it is never a query predicate, only selected alongside the row.

## 2. Entity — `OrderItem`

Add alongside the other `*Snapshot*` fields (after `variantSnapshotSize`):

```java
    /**
     * S3 key of the line's thumbnail, frozen at order creation: the ordered colourway's cover
     * image, or the product's shared cover — see {@code OrderService.resolveThumbnailKey}. Null
     * when neither exists (never falls back to a different colourway's image), when the product
     * had no image at all at purchase, and on every row created before V33. Resolved to a URL by
     * {@code OrderItemResponseDto}.
     */
    private String variantSnapshotImageKey;
```

Nullable — no `@Column(nullable = false)`. `ddl-auto: validate` — the migration is the source of
truth.

## 3. Order creation — `OrderService.buildPricingDraft`

At the `OrderItem.builder()` site (currently `OrderService.java:343-356`), add:

```java
    .variantSnapshotImageKey(resolveThumbnailKey(pl.getProduct(), variant))
```

New private helper on `OrderService`:

```java
/**
 * The line's purchase-time thumbnail key, mirroring what the storefront shows for the ordered
 * colourway: the colour's own cover, else the product's shared cover, else null. "Cover" within a
 * group = primary, then lowest displayOrder, then lowest id.
 *
 * <p>Deliberately NOT three-tiered to "any image regardless of colour": showing a customer a
 * different colourway's photo (a red line item with a blue product shot) reads as a picking error,
 * which is worse than a placeholder. No image is preferable to the wrong image — product owner's
 * call.
 */
private String resolveThumbnailKey(Product product, ProductVariant variant) {
    List<ProductImage> images = product.getImages();
    if (images.isEmpty()) {
        return null;
    }
    Long colourId = variant.getProductColor() != null ? variant.getProductColor().getId() : null;

    return pickCover(images, img ->
                    colourId != null && img.getProductColor() != null
                            && img.getProductColor().getId().equals(colourId))
            .or(() -> pickCover(images, img -> img.getProductColor() == null))
            .map(ProductImage::getStorageKey)
            .orElse(null);
}

private Optional<ProductImage> pickCover(List<ProductImage> images, Predicate<ProductImage> inGroup) {
    return images.stream()
            .filter(inGroup)
            .min(Comparator.comparing((ProductImage i) -> !i.isPrimary())
                    .thenComparingInt(ProductImage::getDisplayOrder)
                    .thenComparing(ProductImage::getId));
}
```

`product.getImages()` is a lazy collection; `buildPricingDraft` runs inside the order-creation
`@Transactional`, so the load is safe. `variant.getProductColor()` is likewise lazy and in-tx.

**Assumption this code relies on:** every product has at least one shared (untagged,
`product_color_id IS NULL`) image as a baseline — colour-specific images are additive on top of
that baseline. This spec does not enforce that; it is expected to be enforced upstream, at product
creation / vendor image upload (a product must have ≥1 shared image before it can go live). If that
invariant is ever violated — a product exists with only colour-tagged images and a gap for some
colourway — `resolveThumbnailKey` still does the right thing (resolves to `null` rather than
showing the wrong colour, per the ruling above), but the gap itself is a data problem upstream, not
something this spec fixes.

> **Follow-up, not part of this spec:** is "every product has ≥1 shared image" currently enforced
> anywhere — product-creation validation, a DB constraint, a vendor-facing UI requirement? If not,
> it should become an explicit validation rule (e.g. reject product publish/activation if
> `product_images` has zero rows with `product_color_id IS NULL`), so this spec's assumption is
> guaranteed rather than hoped for. Not investigated or built here.

## 4. Read path — `OrderItemResponseDto`

Add the field and resolve it:

```java
    private String imageUrl;   // resolved from variantSnapshotImageKey; null when no snapshot
```

`from` gains a `MediaUrlResolver` parameter:

```java
    public static OrderItemResponseDto from(OrderItem item, MediaUrlResolver resolver) {
        return OrderItemResponseDto.builder()
                // ...existing fields unchanged...
                .imageUrl(resolver.resolve(item.getVariantSnapshotImageKey()))
                .build();
    }
```

No ternary needed: `MediaUrlResolver.resolve` already returns `null` for a `null`/blank key (see its
own javadoc) — that is the null guard, not a new one written here.

## 5. Threading `MediaUrlResolver` through `OrderResponseDto`

`OrderItemResponseDto.from` now needs a resolver, so every factory that builds items passes one:
`OrderResponseDto.from(Order)` → `from(Order, MediaUrlResolver)`, `OrderResponseDto.withReturns(Order,
List<ReturnOrder>)` (takes the entity list, not `List<ReturnSummaryDto>` — it maps to that DTO
internally) and `OrderResponseDto.from(Order, String checkoutUrl)` (the create-order overload) each
gain the same parameter.

**Do not trust any line-number list in this spec — key on method names instead, and re-verify
before writing code.** This spec has now gotten its own call-site count wrong three times running:
"all 7, in `OrderService`" (missed `RefundPersistenceHelper` entirely), then a "6" tally that
silently dropped a site its own prose named correctly, then a "7"-labelled `OrderService` bullet
list whose own entries summed to 8. Line numbers drift with every unrelated change to
`OrderService.java`; method names don't. This enumeration is keyed on method name for that reason,
and the arithmetic is spelled out so the next reader can check it rather than trust it:

`OrderService` — **8 call sites in 5 methods** (inject `MediaUrlResolver` as a constructor field,
`@RequiredArgsConstructor`, singleton bean from `com.enunas.backend.media.storage`; pass it at
every one of the 8). One row per invocation that needs its own `mediaUrlResolver` argument added —
not per source line: `toDto`'s ternary holds two invocations on one line, so it gets two rows, not
one row with a count in it.

| # | Method | Factory call |
|---|---|---|
| 1 | `createOrder` | `OrderResponseDto.from(saved, paymentResult.checkoutUrl())` — the create-order overload |
| 2 | `updateOrderStatus` (idempotent early-return branch) | `OrderResponseDto.from(order)`, wrapped by the private `withPaymentId` helper |
| 3 | `updateOrderStatus` (final return) | `OrderResponseDto.from(saved)`, wrapped by `withPaymentId` |
| 4 | `cancelOrder` | `OrderResponseDto.from(order)`, wrapped by `withPaymentId` |
| 5 | `toDto(Order, OrderRelations)` — ternary, true branch | `OrderResponseDto.withReturns(order, returns)` |
| 6 | `toDto(Order, OrderRelations)` — ternary, false branch | `OrderResponseDto.from(order)` — same line as #5, a distinct edit |
| 7 | `toDto(Order, OrderRelations)` — outer else | `OrderResponseDto.from(order)` — a different branch from #5/#6, not the same call |
| 8 | `toBrandScopedDto` | `.items(ownItems.stream().map(OrderItemResponseDto::from).toList())` |
| **Total in `OrderService`** | | **8** |

`withPaymentId(OrderResponseDto, Long)` itself is unaffected — it mutates an already-built DTO; the
resolver goes into the `from(...)`/`withReturns(...)` call each of its 3 callers wraps, not into
`withPaymentId`.

**`toBrandScopedDto` needs a lambda, not a parameter add.** Its call is a bare method reference,
`OrderItemResponseDto::from` — you cannot append an argument to a method reference. Rewrite it as
`.map(item -> OrderItemResponseDto.from(item, mediaUrlResolver))`. Every other site above is a
direct call and takes the parameter addition normally.

**`RefundPersistenceHelper` — verify independently, do not carry a line number forward for it.**
It is a separate `@Component` (deliberately not `OrderService` itself, so its own `@Transactional`
proxy applies — see its class javadoc) that also calls `OrderResponseDto.withReturns(...)`, and it
needs its **own** injected `MediaUrlResolver` field. `MediaUrlResolver` is a stateless `@Component`
singleton, so Spring wires the *same bean* into both classes — "own" means own field and own
wiring, not a separate instance. What is genuinely independent is the wiring itself: forgetting the
field (and the argument at the call site) on this class would have been a miss no `OrderService`
test could catch, which is why it gets verified on its own rather than assumed correct by proxy.
Confirmed at implementation time (grep on `RefundPersistenceHelper.java`): exactly 1 call site
(`persist`, `OrderResponseDto.withReturns`). Cross-class total: **9** — 8 in `OrderService` +
1 in `RefundPersistenceHelper`.

`OrderPreviewResponseDto` is **not** touched — it builds preview lines, not `OrderItem`s, and is a
non-goal.

## 6. Tests

**Unit — `OrderItemResponseDtoTest`** (create if absent, else extend):
- key present → `imageUrl` is `resolver.resolve(key)`.
- `variantSnapshotImageKey == null` → `imageUrl == null`, no NPE.

**Implemented as integration tests, not unit tests — a deliberate, documented deviation.**
`OrderService` has roughly 20 constructor dependencies (`@RequiredArgsConstructor`); hand-building a
mock for a test that only exercises one private method is disproportionate, and no `OrderServiceTest`
exists to extend. `OrderItemImageSnapshotIntegrationTest` covers `resolveThumbnailKey` by seeding
real data and placing a real order via HTTP, then asserting the persisted
`order_items.variant_snapshot_image_key` — the same pattern `OrderItemListingIdIntegrationTest`
already uses for `listingIdSnapshot`. Cases covered:
- product with a BLACK cover, a WHITE cover and a shared image; order a BLACK variant →
  snapshot key == BLACK cover's key.
- order a variant whose colour has no own image, but the product has a shared image → snapshot
  key == shared cover's key.
- **Assumption-violated case, not expected steady state** (see the assumption note above — a
  product this shape shouldn't exist once the upstream invariant is enforced, but the code must
  still behave correctly if it does): product has **only colour-tagged images, no shared image**,
  and the ordered variant's colour has no image of its own (e.g. it has only a WHITE cover, order
  is BLACK) → snapshot key is **`null`**, never WHITE's key. This is the case the old three-tier
  fallback got wrong (it would have returned some other colourway's photo) and this test pins the
  fix.
- product with zero images → `null`.
- "cover" tiebreak, both tiers: within a group, `primary` wins over a lower `displayOrder`; and —
  since V32's partial unique indexes allow at most one primary per group, so a *primary* fixture
  can never reach the second and third tiers — a separate case with **zero primaries** in the group,
  proving `displayOrder` (then `id`) actually breaks the tie rather than being dead code.

**Integration — extend an existing order-creation integration test** (e.g. the one behind
`OrderItemListingIdIntegrationTest` / the media-aware order flow):
- seed a product with a colour-tagged primary image, place an order for that colour, `GET
  /orders/{id}` → `items[0].imageUrl` is the resolved CDN/S3 URL of that image.
- place an order for a product with no images → `items[0].imageUrl` is `null`, response is still 200.

**Integration — `RefundPersistenceHelper`'s wiring path, separately.** It gets its own injected
`MediaUrlResolver` instance, not `OrderService`'s — that is a genuinely separate piece of Spring
wiring, and nothing above exercises it. Relying on the `OrderService`-side tests to imply this class
is also wired correctly is exactly the kind of gap this feature's own review process (see the
colourway-images fix loop) exists to catch. Add a case: place an order for a product with a colour-tagged image, request a return, then drive
the refund flow (`POST /admin/orders/{orderId}/return/refund?refundAmount=`) through to
`RefundPersistenceHelper.persist` — using the same `test` + `mock-payments` active-profiles setup
the existing order/return integration tests already use for deterministic payment confirmation —
and assert the returned `OrderResponseDto.items[].imageUrl` resolves correctly: non-null and
matching the expected CDN/S3 URL, not merely present. This is the one path in this spec that a
passing `OrderService` test suite cannot vouch for.

**Migration:** `V33` applies against a non-empty `order_items` table; existing rows are
`variant_snapshot_image_key = NULL`; context loads under `ddl-auto: validate`.

## Acceptance criteria

- [ ] `V33` adds a nullable `variant_snapshot_image_key`; no backfill; no index.
- [ ] `OrderItem.variantSnapshotImageKey` maps; nullable.
- [ ] `OrderService.buildPricingDraft` snapshots the key via `resolveThumbnailKey`; selection order
      is colour-cover → shared-cover → null; a colour with no cover and no shared fallback resolves
      to null, never another colour's image. Tiebreak within a group is primary/displayOrder/id.
- [ ] `OrderItemResponseDto` exposes `imageUrl`, resolved from the key, `null` when the key is null.
- [ ] `MediaUrlResolver` threaded through all `OrderResponseDto` factories, the brand builder, and
      `RefundPersistenceHelper`; both `OrderService` and `RefundPersistenceHelper` have their own
      injected bean; every call site in both classes passes it.
- [ ] `OrderPreviewResponseDto`, confirmation emails, returns — untouched.
- [ ] Tests in §6 green; full suite green.
- [ ] No change to `MediaPurpose`, presign, S3 CORS, or any product-media endpoint.

## Out of scope (deferred, not forgotten)

- Preview/cart thumbnails, email thumbnails, return-flow thumbnails.
- Backfilling pre-`V33` orders.
- A dedicated order-thumbnail size/derivative — the full product image is served as-is, same as
  everywhere else on the storefront today.
