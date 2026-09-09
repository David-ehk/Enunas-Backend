# Colourway-Specific Product Images — Optional `ProductImage → ProductColor` Tag

**Status:** Settled — architecture agreed, implementation questions only from here.
**Trigger:** Empirical test on the "Nomad Jacket" (SKU `HW6O0GC2` / `QMXL03JP`): adding a WHITE
colourway alongside BLACK changes SKU, price, stock and listing on the PDP, but the photo gallery
is identical for both. `ProductImage` belongs to `Product` and has no colour dimension, so
"one connected product, WHITE and BLACK share everything except the photos" is not expressible.

## Confirmed decisions (product owner)

- **One connected product.** Colour-specific imagery is a tag on the existing per-product gallery,
  not a new product per colourway and not a gallery moved under `ProductColor`.
- **Optional tag, untagged = shared.** An image may be assigned to exactly one colourway or left
  untagged. The PDP gallery for a selected colour = *its tagged images* **plus** *all untagged
  images*. This lets a brand share studio shots and only vary the on-model photos.
- **Primary is per-colourway.** The listing-card / search-result thumbnail follows the selected
  swatch. Today's "one primary per product" becomes "one primary per (product, colour-group)",
  where the untagged set is its own group.
- **Re-tag without re-upload is in scope now** — a `PATCH` endpoint for image metadata
  (`productColorId`, `primary`, `altText`, `displayOrder`). Without it, every "this photo should
  be WHITE, not BLACK" correction means deleting and re-uploading the original bytes to S3.
- **"Shared" is a permanently valid state.** No backend rule forces a colourway to own at least
  one image before it is listable/buyable. Reversible product decision — see §8.
- **Frontend is a separate Next.js repo.** This spec defines the backend contract and a
  frontend-integration section (§7); the vendor-dashboard / PDP implementation happens there.

## Non-goals

- Per-colourway **videos** — images only. `ProductVideo` stays per-product.
- A hard "each colour needs its own photo" validation (see §8).
- Bulk / multi-select re-tag in a single call.
- Colour-aware image in order snapshots — orders do not snapshot product images today, nothing
  to change.
- The frontend implementation itself.

## 1. Schema — `V32__product_image_colour.sql`

```sql
ALTER TABLE product_images
    ADD COLUMN product_color_id BIGINT
    REFERENCES product_colors (id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_product_images_color
    ON product_images (product_color_id);

-- "One primary per colour-group" as two partial unique indexes — the house idiom
-- (cf. V6 uq_ledger_order_payment, V15 uq_returns_active_per_brand). Two indexes,
-- not one over COALESCE(product_color_id, 0): a sentinel would silently couple index
-- correctness to "no product_colors.id is ever 0", which a manual sequence reset or a
-- seed script can break without any error surfacing.

-- (a) at most one primary among a colourway's own images
CREATE UNIQUE INDEX IF NOT EXISTS uq_product_images_primary_per_colour
    ON product_images (product_id, product_color_id)
    WHERE is_primary AND product_color_id IS NOT NULL;

-- (b) at most one primary among a product's shared (untagged) images
CREATE UNIQUE INDEX IF NOT EXISTS uq_product_images_primary_shared
    ON product_images (product_id)
    WHERE is_primary AND product_color_id IS NULL;
```

- **No backfill.** Existing `product_images` rows keep `product_color_id = NULL` — i.e. every
  current image becomes a shared image, which is exactly today's behaviour (shown for every
  colourway). Nothing to migrate.
- `ON DELETE SET NULL`, not `CASCADE`: removing a colourway demotes its photos to shared (a
  recoverable state a vendor can fix in the UI) rather than destroying real photography.
- **Product deletion deletes `ProductColor` rows.** `ProductService.purgeProduct` (reached from the
  brand's `DELETE /products/{id}` and `AdminService.deleteProduct`) deletes a product's variants,
  then its colours, then the product. With `ON DELETE SET NULL` that would collapse every
  colourway's primary into the shared group, where two or more collide on
  `uq_product_images_primary_shared` and abort the delete with a bare integrity error *outside* the
  `ProductDeletionBlockedException` translation. So `purgeProduct` must delete the product's images
  (`imageRepository.deleteByProductId(id); flush()`) **before** the colours — the product delete
  would cascade them away anyway, and their S3 objects are already orphaned by this path today.
  Any *other* future `ProductColor` delete path (e.g. a per-colour delete endpoint) must bring an
  explicit test that its images fall back to shared and stay renderable.
- New migration, no edit to an applied one (`V31` is currently the latest).

## 2. Entities

**`ProductImage`** gains:

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "product_color_id")   // nullable — null = shared across all colourways
private ProductColor productColor;
```

**`Product`** gains a read-only association mirroring how `variants` / `images` already work
(no cascade, no setter — DTO-building convenience and the single source for the colour list):

```java
@OneToMany(mappedBy = "product")
@Builder.Default
@Getter(AccessLevel.NONE) @Setter(AccessLevel.NONE)
private List<ProductColor> colors = new ArrayList<>();

public List<ProductColor> getColors() {
    return colors == null ? List.of() : Collections.unmodifiableList(colors);
}
```

`ddl-auto: validate` — the migration is the source of truth; these annotations only need to match
it.

## 3. Write path — confirm step

`POST /products/{productId}/media/images` (unchanged route). `ProductImageDto` gains:

```java
private Long productColorId;   // optional; null = shared
```

`MediaService.addImage`:

1. If `productColorId != null`: load the `ProductColor`, and reject with `SecurityException`
   (→ 403, same handler as the existing cross-tenant `storageKey` rejection) unless
   `productColor.getProduct().getId().equals(productId)`.
2. Primary handling becomes colour-group scoped: when `dto.isPrimary()`, unset the current
   primary **of that same group** —
   `imageRepository.findByProductIdAndProductColorIdAndPrimary(productId, productColorId, true)`.
   Spring Data renders a `null` argument as `product_color_id IS NULL`, so the untagged group is
   handled by the same method.

**Presign is unchanged.** The S3 key stays `products/{productId}/images/<uuid>.<ext>` — colour is
DB metadata, not part of the key. `MediaPurpose`, the presigned PUT, `x-amz-tagging`, and the CORS
requirements are all untouched.

## 4. New — re-tag / edit image metadata

```
PATCH /products/{productId}/media/images/{imageId}    BRAND_PARTNER, must own the product
  body: { productColorId?, unassignColor?, primary?, altText?, displayOrder? }   // all optional
  200:  ProductImageResponseDto
  403:  image not on this product, or productColorId belongs to another product
  404:  no such image
```

Partial-update semantics, matching `updateMyProfile` / `updateVariant` elsewhere in the codebase:
an **omitted** field is left unchanged. Those endpoints have *no* way to null a field out (null ==
unchanged), so clearing the colour needs an explicit flag rather than a magic value — `0` as
"untag" would be a brand-new convention a frontend dev can misread as "invalid id":

| body | Effect on colour |
|---|---|
| neither `productColorId` nor `unassignColor` | unchanged |
| `productColorId: <positive id>` | assign to that colourway (ownership-checked as in §3) |
| `unassignColor: true` | move to the shared group (`productColorId` ignored if also sent) |

`primary: true` promotes this image and demotes the current primary **of its (post-update) colour
group**. `primary: false` just clears the flag. Changing an image's colour (`productColorId` or
`unassignColor`) to a *different* group **drops its `primary` flag** unless `primary: true` is also
sent in the same call — otherwise a primary image dragged into a group that already has a cover
would collide. The demoting UPDATE is flushed to the DB before the promoted row is written, because
the two `uq_product_images_primary_*` partial indexes are enforced per statement (not deferrable);
they are the backstop.

Route lives on the existing `MediaController` (`/products/{productId}/media`); no
`SecurityConfiguration` change — `POST/PATCH /products/**` is already `hasRole('BRAND_PARTNER')`.

## 5. Read path

`GET /products/{productId}/media/images` — **unchanged**, returns *all* images (the vendor
dashboard needs the full set). Gains an **optional** query param:

```
GET /products/{productId}/media/images?colorId={id}
  → images where product_color_id = :id OR product_color_id IS NULL
    ordered by is_primary DESC,
               (within the primary block: colour-tagged before shared),
               display_order ASC, id ASC
```

The colour-tagged-before-shared tiebreak applies to the unfiltered GET too: it only
reorders *within* the primary block, so `images[0]` is a colourway's own cover rather than a
shared cover whenever both are primary.

`ProductImageResponseDto` gains:

```java
private Long productColorId;   // null = shared
private String color;          // convenience, null when shared
```

## 6. PDP / card payload

`ProductResponseDto` gains:

```java
private List<ProductColorDto> colors;   // { id, color, colorFamily, sku }
```

- **New DTO** `ProductColorDto` (`product.dto`) with `from(ProductColor)`. This is the single
  source for the PDP swatch strip *and* the vendor's image-colour picker. Built from
  `product.getColors()`.
- Each entry in `ProductResponseDto.images` already carries `productColorId` + `primary` (§5), so
  the PDP filters client-side: `img.productColorId === selectedColorId || img.productColorId == null`.
- `ProductVariantResponseDto` and `ListingResponseDto` each gain `Long colorId`, so the frontend
  can correlate a variant / listing to its colour group (and thus to its images) without matching
  on the colour name string.
- `CompleteTheLookCardDto.image` is **unchanged** — "first primary in any group, else first image"
  degrades to a sensible shared/any photo with no code change.
- `AdminProductResponseDto.images` reuses `ProductImageResponseDto`, so each entry now additively
  carries `productColorId` + `color` (nullable). Behaviour is unchanged; the payload shape is a
  superset of before.

## 7. Frontend UX contract (Next.js vendor dashboard + PDP)

Binding expectations for the frontend team — so the UX is specified, not reinvented downstream.

### 7.1 Upload & assignment flow

The backend supports **both** pre-tagging (send `productColorId` in the confirm call, §3) and
upload-then-tag (`PATCH`, §4). Specified UX:

- The "Produktbilder" section renders as **colour groups**: `Alle Farben (geteilt)` plus one
  group per colourway (from `ProductResponseDto.colors`).
- An upload targets the **currently active group** — dropping a file into the WHITE group confirms
  it with `productColorId = <white id>`; dropping into `Alle Farben` confirms it untagged.
- Moving an image between groups (drag, or a dropdown) calls `PATCH` — `productColorId: <id>` for
  a colourway, `unassignColor: true` for `Alle Farben`.
- Each group has its own "Als Titelbild setzen" → `PATCH { primary: true }`.

### 7.2 Existing multi-colour products (the Nomad-Jacket case)

- Untagged images show in the `Alle Farben (geteilt)` group with helper text:
  *"Diese Bilder werden für alle Farbvarianten angezeigt."*
- Splitting them is drag-into-a-colour-group — no data migration, no re-upload.
- When a product has ≥2 colourways and ≥1 shared image, surface a one-line, **non-blocking**
  nudge inviting the vendor to assign colour-specific photos.

### 7.3 Shared is acceptable — advisory only

A colourway with no own images is a valid, shippable state; it simply shows the shared set. The
frontend MAY show an advisory hint (*"Diese Farbe nutzt die geteilten Bilder"*) but MUST NOT block
listing activation or checkout on it.

## 8. Reversible decision — no "own image per colour" rule

If the product owner later wants to require each colourway to have its own image before its
listings can go active, the change is contained and additive:

- a `ProductListingService` activation guard, and/or
- a per-colour `boolean hasOwnImages` on `ProductColorDto` for the frontend to gate on.

Noted here so it is a known, small follow-up rather than a design gap. Not built now.

## 9. Tests

**Unit — `MediaServiceTest`:**
- add image with a `productColorId` belonging to another product → 403.
- `primary` unset is scoped to the colour group (setting a WHITE primary does not demote the
  BLACK primary or the shared primary).
- `PATCH`: assign (`productColorId`), untag (`unassignColor: true`), `altText` / `displayOrder`
  partial update leaves colour untouched, `primary` flips only within the (post-update) group.

**Integration — `MediaEndToEndIntegrationTest`:**
- upload two images, tag one WHITE and one BLACK; `GET ?colorId=<white>` returns the WHITE image
  and every untagged image, in `primary DESC, colour-tagged-before-shared, displayOrder ASC` order.
- `GET` with no param still returns the full set.

**DTO / mapping:**
- `ProductImageResponseDto` carries `productColorId` + `color`.
- `ProductResponseDto.colors` is populated; `ProductColorDto.from` maps all four fields.
- `ProductVariantResponseDto.colorId` / `ListingResponseDto.colorId` populated.

**Migration:** `V32` applies cleanly against a DB with existing `product_images` rows (all become
`product_color_id = NULL`); app context loads under `ddl-auto: validate`.

## Acceptance criteria

- [ ] `V32` runs on a non-empty `product_images` table; existing rows are `product_color_id = NULL`
      (shared); `idx_product_images_color` and both `uq_product_images_primary_*` partial indexes
      exist.
- [ ] `ProductImage.productColor` maps; `Product.colors` is read-only, no cascade.
- [ ] Confirm (`POST .../images`) accepts optional `productColorId`, ownership-checked, 403 on a
      foreign colour id.
- [ ] `PATCH /products/{productId}/media/images/{imageId}` — partial update, `unassignColor: true`
      moves to shared, primary is per colour group, 403/404 as specified.
- [ ] `GET .../images?colorId=` returns tagged-for-colour ∪ untagged, ordered primary-first;
      no-param GET unchanged.
- [ ] `ProductImageResponseDto` exposes `productColorId` + `color`; `ProductResponseDto` exposes
      `colors`; variant + listing DTOs expose `colorId`.
- [ ] `CompleteTheLookCardDto` / `AdminProductResponseDto` behaviour unchanged.
- [ ] No `MediaPurpose` / presign / CORS change.
- [ ] All tests in §9 green.
- [ ] Frontend-integration section added to `docs/frontend-media-integration.md` covering §5–§7.

## Out of scope (deferred, not forgotten)

- Per-colourway videos.
- The "own image per colour" validation rule (§8).
- Bulk re-tag.
- Any change to order-item media (orders don't snapshot images).
