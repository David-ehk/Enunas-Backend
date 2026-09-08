# Product preview state for future-release products

**Date:** 2026-09-08
**Status:** implemented (2026-09-08) — 6 files changed, `ProductResponseDtoPreviewTest` +
`ProductPreviewStateIntegrationTest` (11 cases) added; full suite 506 green.

## Problem

`Product.releaseDate` is currently decorative — no query or gate reads it. A product
is visible on the public storefront only when `status = ACTIVE` **and** it has a
`ProductListing` that is `active = true` and inside its `availableFrom`/`availableUntil`
window (`ProductRepository` PLP queries, `ProductService.assertBrowsable`,
`ProductListingRepository.CURRENTLY_SELLABLE`/`STOREFRONT_VISIBLE`). `OrderService`
independently re-checks the listing window at checkout and never looks at `releaseDate`.

So there is no configuration of existing fields that yields "visible to the public but
not purchasable":

- Listing window in the future → product is 404 / omitted (frontend has nothing to render).
- Listing window open → product is fully buyable, including via a direct API call, because
  checkout never consults `releaseDate`.

We want a public **preview** ("Coming Soon") state: anonymous storefront visitors see the
product, cannot buy it, and see no price.

## Decision

**Derive the preview state at read time from `releaseDate`. `releaseDate` is the master switch.**

- `preview(product) ≔ product.releaseDate != null && product.releaseDate > today`
- A product with a future `releaseDate` is **preview** regardless of its listing window —
  never buyable, never priced, until `releaseDate`.
- On/after `releaseDate` the existing listing-window rules resume unchanged.
- Nothing stored, nothing to recompute — the state flips at UTC midnight of `releaseDate`
  on the next read.

Rejected: a stored `previewMode`/`storefrontState` column (needs a migration + something to
flip it when the date passes; can drift from `releaseDate`); a parallel "upcoming" query
merged in the service (breaks pagination/sorting on the PLP feeds).

### "today"

- Java: `LocalDate.now()` — the JVM is pinned to UTC (`TimezoneConfig`).
- JPQL: `CURRENT_DATE` — DB session date; prod / CI / Testcontainers are all UTC, the same
  assumption the existing `CURRENT_TIMESTAMP` in these queries already rests on.
- Transition moment: **UTC midnight** of `releaseDate`.

**Accepted tradeoff (reviewed 2026-09-08):** UTC midnight is ~02:00 Europe/Berlin (CEST), so a
German customer sees "Coming Soon" for the first ~1–2 h of the calendar release day. Deliberate:
it keeps `releaseDate` on the same clock as the `CURRENT_TIMESTAMP` window checks right beside it
and as the JVM pin (`TimezoneConfig`), and it errs safe (never live early). A Berlin-calendar-day
comparison would force a `:today` parameter through the queries and break the compile-time-constant
`CURRENTLY_SELLABLE` / `STOREFRONT_BROWSABLE_LISTING` strings. Minute-precise drop timing is
already `ProductListing.availableFrom`'s job — `releaseDate` is a coarse calendar date by design.

## Changes

### 1. `ProductListingRepository.CURRENTLY_SELLABLE`

Append: `AND (l.product.releaseDate IS NULL OR l.product.releaseDate <= CURRENT_DATE)`

`STOREFRONT_VISIBLE` is built from `CURRENTLY_SELLABLE`, so it inherits the clause. Effect
for a preview product:

| consumer | new behaviour |
|---|---|
| `findStorefrontVisible*` | preview product's listings absent from public listing endpoints — hides the price |
| `findLowestActivePriceByProductId`, `findSellableListingPricesByProductIds` | no row → DTO `price = null` falls out |
| `existsCurrentlyActiveListingByProductId` | `false` for preview (feeds `assertBrowsable`, see §3) |
| `findCurrentlyActiveByVariantId*` | test-only today, no production caller |

### 2. `ProductRepository` — the 4 PLP queries

`findByStatus`, `findByCategory`, `search`, `findByColorFamily` each inline the same
active-listing `EXISTS`. Replace with one shared constant on `ProductRepository` (the class
Javadoc already flags the copy-paste as a drift hazard):

```java
String STOREFRONT_BROWSABLE = """
    p.status = com.enunas.backend.product.ProductStatus.ACTIVE
    AND EXISTS (SELECT 1 FROM ProductListing l WHERE l.product = p AND l.active = true
        AND ( (p.releaseDate IS NOT NULL AND p.releaseDate > CURRENT_DATE)
              OR ( (l.availableFrom IS NULL OR l.availableFrom <= CURRENT_TIMESTAMP)
                   AND (l.availableUntil IS NULL OR l.availableUntil >= CURRENT_TIMESTAMP) ) ))
    """;
```

Semantics: future `releaseDate` → any `active` listing makes it visible (window ignored);
past/null `releaseDate` → unchanged in-window rule.

`findByColorFamily` goes through `ProductColor pc` (alias `pc.product`, not `p`). Restructure
to `SELECT DISTINCT p FROM Product p JOIN ProductColor pc ON pc.product = p WHERE
pc.colorFamily = :colorFamily AND ` + `STOREFRONT_BROWSABLE` so all four share the constant.
(`Product` has no inverse `colors` collection, hence the explicit `JOIN … ON`.) Its
`countQuery` gets the same treatment.

### 3. `ProductService.assertBrowsable`

```java
if (product.getStatus() != ProductStatus.ACTIVE) throw new ProductNotFoundException(...);
boolean live    = listingRepository.existsCurrentlyActiveListingByProductId(id);
boolean preview = product.getReleaseDate() != null
        && product.getReleaseDate().isAfter(LocalDate.now())
        && listingRepository.existsActiveListingByProductId(id);   // NEW — active flag only
if (!live && !preview) throw new ProductNotFoundException(...);
```

New repo method:

```java
@Query("SELECT COUNT(l) > 0 FROM ProductListing l WHERE l.product.id = :productId AND l.active = true")
boolean existsActiveListingByProductId(@Param("productId") Long productId);
```

Owner/admin exemption at the top of the method is untouched.

### 4. `ProductResponseDto`

Add `private boolean preview;`. All three `from(...)` overloads funnel into the 4-arg one —
single edit:

```java
boolean preview = product.getReleaseDate() != null && product.getReleaseDate().isAfter(LocalDate.now());
...
.preview(preview)
.price(preview || price == null ? null : price.current())
.originalPrice(preview || price == null ? null : price.original())
```

`AdminProductResponseDto`: add `preview` too, for admin-UI parity.

### 5. `OrderService.resolveAndValidateListings`

After the existing `availableFrom`/`availableUntil` checks, for each resolved listing `pl`:

```java
LocalDate releaseDate = pl.getProduct().getReleaseDate();
if (releaseDate != null && releaseDate.isAfter(LocalDate.now())) {
    throw new IllegalStateException(
        "Product " + pl.getProduct().getId() + " is not yet released (releases " + releaseDate + ")");
}
```

`IllegalStateException → 409` (`GlobalExceptionHandler`), same shape as the sibling window
checks. This is the real purchase block — the DTO flag is advisory.

## Tests

Integration (`ProductPreviewStateIntegrationTest extends AbstractDiscountIntegrationTest`;
seed with `seedListing`, then set `releaseDate` / listing window via the repositories):

1. ACTIVE + future `releaseDate` + active listing, window null/future → present in
   `GET /products` with `preview=true`, `price=null`; `GET /products/slug/{slug}` → 200,
   `preview=true`.
2. Same but listing window already open → still `preview=true`, `price=null` (master switch);
   `GET /products/{id}/listings` → empty; `GET /listings/{that id}` → 404.
3. ACTIVE + future `releaseDate` + **no** active listing → still hidden (regression: today's
   behaviour).
4. ACTIVE + `releaseDate` today / past / null → `preview=false`, everything unchanged
   (regression guard).
5. Checkout with a preview-product line → 409.
6. `search`, `category`, `color-family` each surface the preview product with `preview=true`.

DTO unit test: `preview` true/false across the date boundary; `price`/`originalPrice` nulled
when preview.

## Non-goals

No brand-facing `previewMode` toggle (releaseDate *is* the switch), no scheduled job, no
"notify me" / waitlist, no preview-aware sort (frontend concern), no change to admin
visibility, no preview handling for Complete-The-Look cards.
