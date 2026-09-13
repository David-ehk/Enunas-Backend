# Order-Item Image Snapshot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every order line item a purchase-time thumbnail (`imageUrl`), frozen at order creation from the ordered colourway's own cover photo, falling back to the product's shared cover, never to a different colourway's photo.

**Architecture:** `OrderItem` gains a nullable snapshot column holding an S3 key, populated once at order creation by walking `Product.getImages()`/`ProductVariant.getProductColor()` (colour-cover → shared-cover → `null`, two tiers only). `OrderItemResponseDto` resolves that key to a URL via `MediaUrlResolver` at read time — which means every factory that builds an `OrderResponseDto`/`OrderItemResponseDto` needs that resolver threaded through, in two classes (`OrderService`, `RefundPersistenceHelper`).

**Tech Stack:** Java 21, Spring Boot 4.0.5, Spring Data JPA, Flyway, PostgreSQL (Testcontainers in tests), Lombok, JUnit 5 + AssertJ, `@SpringBootTest` + `TestRestTemplate` integration tests via `AbstractDiscountIntegrationTest`.

**Spec:** `docs/superpowers/specs/2026-09-10-order-item-image-snapshot-design.md` — read it in full, including §5's call-site table and its explicit instruction not to trust any line number in this plan either; re-grep before editing. This plan's line numbers reflect the file as read while writing this plan and **will** have drifted by the time you implement.

## Global Constraints

- **DO NOT run `git add`, `git commit`, or `git push` at any point.** The human commits manually. Work on the current branch (`master`) — no new branch, no worktree.
- **Depends on the colourway-image work already in the tree** (`V32__product_image_colour.sql`, `ProductImage.productColor`, `Product.getColors()`). This plan's migration is **`V33`** — verify `V32` is still the latest migration file before creating it; if something newer has landed, use the next free number and say so in your report.
- **Two-tier fallback only: colour-cover → shared-cover → `null`.** Never fall back to "any image regardless of colour" — a wrong-colour photo is worse than no photo (product owner's ruling, spec §3).
- **Assumption, not enforced here:** every product is expected to have ≥1 shared (untagged) image as a baseline; colour-specific images are additive on top. This plan does not add that validation — it only has to behave correctly (resolve to `null`) if the assumption is violated. See spec §3's assumption note.
- **No backfill.** Existing `order_items` rows get `NULL`. Never write a migration `UPDATE` for this column.
- **Store the S3 key, never a resolved URL, in the new column.** `OrderItemResponseDto` resolves at read time via `MediaUrlResolver`, matching the codebase's "keys in columns" rule.
- **`ddl-auto: validate`** — entity mappings must match the migration exactly.
- **Line numbers drift; method names don't.** Every call-site reference below is anchored to a method name first, a line number second (as observed while writing this plan). If a line number is off by a few lines, that is expected — find the call by method name and the exact source snippet quoted, not by trusting the number.
- Run tests with the Maven wrapper from `backend/`: `./mvnw test -Dtest=ClassName` (single class) or `./mvnw test` (full suite).
- OneDrive/VS Code file locks can cause spurious mass `NoClassDefFoundError` on this machine — that's environment interference, not a regression; retry.

---

### Task 1: Migration + `OrderItem` field

**Files:**
- Create: `backend/src/main/resources/db/migration/V33__order_item_image_snapshot.sql`
- Modify: `backend/src/main/java/com/enunas/backend/order/OrderItem.java`

**Interfaces:**
- Produces: `OrderItem.getVariantSnapshotImageKey()` / `setVariantSnapshotImageKey(String)`, nullable.

- [ ] **Step 1: Confirm `V33` is free**

Run: check `backend/src/main/resources/db/migration/` for the highest `V<n>` file. As of writing this plan it is `V32__product_image_colour.sql`. If something newer exists, use the next free number instead of `V33` everywhere in this plan and note it in your report.

- [ ] **Step 2: Write the migration**

```sql
-- =============================================================================
-- V33: order line items gain a purchase-time thumbnail.
--
-- Nullable, no backfill: pre-V33 rows and any line whose product had no image at
-- purchase stay NULL, and the frontend placeholders them. Same snapshot discipline
-- as product_snapshot_name / listing_id_snapshot on this table (see V28).
--
-- V0.0.1..V32 are left untouched (editing an applied migration breaks its Flyway
-- checksum and the app refuses to start).
-- =============================================================================

ALTER TABLE order_items
    ADD COLUMN IF NOT EXISTS variant_snapshot_image_key varchar(512);
```

No index — this column is never a query predicate.

- [ ] **Step 3: Add the field to `OrderItem`**

In `backend/src/main/java/com/enunas/backend/order/OrderItem.java`, add after `variantSnapshotSize`:

```java
    /**
     * S3 key of the line's thumbnail, frozen at order creation: the ordered colourway's cover
     * image, or the product's shared cover — see {@link com.enunas.backend.order.OrderService}'s
     * {@code resolveThumbnailKey}. Null when neither exists (never falls back to a different
     * colourway's image), when the product had no image at all at purchase, and on every row
     * created before V33. Resolved to a URL by {@code OrderItemResponseDto}.
     */
    private String variantSnapshotImageKey;
```

Plain field, no `@Column(nullable = false)` — nullable is the point.

- [ ] **Step 4: Verify the schema validates**

Run: `./mvnw test -Dtest=OrderItemListingIdIntegrationTest`
Expected: PASS. This proves `V33` applies cleanly and Hibernate `validate` accepts the new nullable field (this test already boots the full order-creation path against Testcontainers Postgres).

---

### Task 2: `resolveThumbnailKey` — selection logic + wiring into order creation

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/order/OrderService.java`
- Create: `backend/src/test/java/com/enunas/backend/order/integration/OrderItemImageSnapshotIntegrationTest.java`

**Interfaces:**
- Produces: `private String OrderService.resolveThumbnailKey(Product product, ProductVariant variant)`, `private Optional<ProductImage> OrderService.pickCover(List<ProductImage>, Predicate<ProductImage>)`.
- Consumes: `Product.getImages()`, `Product.getColors()` (indirectly, via `ProductImage.getProductColor()`), `ProductVariant.getProductColor()` — all already present from the colourway-images work.

**Note on test strategy — a deliberate deviation from the spec's "unit test" framing:** `OrderService` has ~20 constructor dependencies (`@RequiredArgsConstructor`). There is no existing `OrderServiceTest` with hand-built mocks, and building one just for this method is disproportionate. Test `resolveThumbnailKey` through integration tests instead — seed real data, place a real order via HTTP, assert the persisted `order_items.variant_snapshot_image_key` via JDBC. This is the same pattern `OrderItemListingIdIntegrationTest` already uses for `listingIdSnapshot`, and it exercises the real lazy-loaded `Product.getImages()` / `ProductVariant.getProductColor()` rather than mocked ones.

- [ ] **Step 1: Write the failing integration tests**

Create `backend/src/test/java/com/enunas/backend/order/integration/OrderItemImageSnapshotIntegrationTest.java`:

```java
package com.enunas.backend.order.integration;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.media.ProductImage;
import com.enunas.backend.media.ProductImageRepository;
import com.enunas.backend.product.Product;
import com.enunas.backend.product.productlisting.PriceInputMode;
import com.enunas.backend.product.productlisting.ProductListing;
import com.enunas.backend.product.productvariant.ColorFamily;
import com.enunas.backend.product.productvariant.ProductColor;
import com.enunas.backend.product.productvariant.ProductVariant;
import com.enunas.backend.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * order_items.variant_snapshot_image_key — colour-cover -> shared-cover -> null, never a
 * different colourway's photo. See docs/superpowers/specs/2026-09-10-order-item-image-snapshot-design.md.
 */
class OrderItemImageSnapshotIntegrationTest extends AbstractDiscountIntegrationTest {

    @Autowired private ProductImageRepository imageRepository;

    private record TwoColourFixture(Product product, ProductColor black, ProductColor white,
                                     long blackListingId, long whiteListingId) {}

    /** Builds Product -> {BLACK, WHITE} ProductColors -> one variant each -> one listing each. */
    private TwoColourFixture seedTwoColourProduct(BrandPartner brand, User creator, String priceEuros, int stock) {
        String name = "Prod-" + UUID.randomUUID().toString().substring(0, 6);
        Product p = productRepository.save(Product.builder()
                .name(name).slug(name.toLowerCase()).brand(brand).creator(creator).build());
        ProductColor black = productColorRepository.save(ProductColor.builder()
                .sku(sku()).color("Black").colorFamily(ColorFamily.BLACK).product(p).build());
        ProductColor white = productColorRepository.save(ProductColor.builder()
                .sku(sku()).color("White").colorFamily(ColorFamily.WHITE).product(p).build());
        ProductVariant blackVariant = productVariantRepository.save(ProductVariant.builder()
                .productColor(black).size("M").stockQuantity(stock).product(p).build());
        ProductVariant whiteVariant = productVariantRepository.save(ProductVariant.builder()
                .productColor(white).size("M").stockQuantity(stock).product(p).build());
        long blackListingId = productListingRepository.save(ProductListing.builder()
                .product(p).variant(blackVariant).price(new BigDecimal(priceEuros))
                .priceInputMode(PriceInputMode.GROSS).currency("EUR").active(true).build()).getId();
        long whiteListingId = productListingRepository.save(ProductListing.builder()
                .product(p).variant(whiteVariant).price(new BigDecimal(priceEuros))
                .priceInputMode(PriceInputMode.GROSS).currency("EUR").active(true).build()).getId();
        return new TwoColourFixture(p, black, white, blackListingId, whiteListingId);
    }

    private String sku() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private ProductImage seedImage(Product product, ProductColor color, boolean primary, int displayOrder) {
        return imageRepository.save(ProductImage.builder()
                .product(product).productColor(color)
                .storageKey("products/" + product.getId() + "/images/" + UUID.randomUUID() + ".jpg")
                .primary(primary).displayOrder(displayOrder).build());
    }

    private String snapshotKeyForFirstItem(long orderId) {
        return jdbc.queryForObject(
                "SELECT variant_snapshot_image_key FROM order_items WHERE order_id = ? ORDER BY id LIMIT 1",
                String.class, orderId);
    }

    @Test
    void order_colourWithOwnImages_snapshotsThatColoursPrimaryOverLowerDisplayOrder() {
        BrandPartner brand = seedBrand("Acme1", "acme1", "0.15").brand();
        seedCustomer();
        TwoColourFixture fx = seedTwoColourProduct(brand, brand.getUser(), "50.00", 5);
        ProductImage blackNonPrimary = seedImage(fx.product(), fx.black(), false, 0);
        ProductImage blackPrimary = seedImage(fx.product(), fx.black(), true, 5);
        seedImage(fx.product(), fx.white(), true, 0); // WHITE's own cover — must NOT leak into BLACK's order
        seedImage(fx.product(), null, true, 0);        // a shared primary — must NOT win over BLACK's own

        String token = login("acme1@it.local", "Brand123!");
        // no-op line to keep brand token referenced if unused by postOrder's customer flow
        String customerToken = login("customer@it.local", "Customer123!");
        ResponseEntity<Map> order = postOrder(customerToken, null, List.of(item(fx.blackListingId(), 1)));
        long orderId = ((Number) order.getBody().get("id")).longValue();

        assertThat(snapshotKeyForFirstItem(orderId)).isEqualTo(blackPrimary.getStorageKey());
        assertThat(snapshotKeyForFirstItem(orderId)).isNotEqualTo(blackNonPrimary.getStorageKey());
    }

    @Test
    void order_colourWithNoOwnImage_fallsBackToSharedCover() {
        BrandPartner brand = seedBrand("Acme2", "acme2", "0.15").brand();
        seedCustomer();
        TwoColourFixture fx = seedTwoColourProduct(brand, brand.getUser(), "50.00", 5);
        seedImage(fx.product(), fx.black(), true, 0); // BLACK has its own — irrelevant to this test
        ProductImage shared = seedImage(fx.product(), null, true, 0); // WHITE has none; shared exists

        String customerToken = login("customer@it.local", "Customer123!");
        ResponseEntity<Map> order = postOrder(customerToken, null, List.of(item(fx.whiteListingId(), 1)));
        long orderId = ((Number) order.getBody().get("id")).longValue();

        assertThat(snapshotKeyForFirstItem(orderId)).isEqualTo(shared.getStorageKey());
    }

    @Test
    void order_assumptionViolated_noOwnImageAndNoSharedImage_snapshotsNullNeverAnotherColour() {
        BrandPartner brand = seedBrand("Acme3", "acme3", "0.15").brand();
        seedCustomer();
        TwoColourFixture fx = seedTwoColourProduct(brand, brand.getUser(), "50.00", 5);
        // Only BLACK has an image; no shared image exists at all — the invariant this feature
        // assumes (spec §3) is violated on purpose here to prove the code degrades safely.
        seedImage(fx.product(), fx.black(), true, 0);

        String customerToken = login("customer@it.local", "Customer123!");
        ResponseEntity<Map> order = postOrder(customerToken, null, List.of(item(fx.whiteListingId(), 1)));
        long orderId = ((Number) order.getBody().get("id")).longValue();

        assertThat(snapshotKeyForFirstItem(orderId)).isNull();
    }

    @Test
    void order_productWithNoImagesAtAll_snapshotsNull() {
        BrandPartner brand = seedBrand("Acme4", "acme4", "0.15").brand();
        seedCustomer();
        long listingId = seedListing(brand, brand.getUser(), "50.00", 5);

        String customerToken = login("customer@it.local", "Customer123!");
        ResponseEntity<Map> order = postOrder(customerToken, null, List.of(item(listingId, 1)));
        long orderId = ((Number) order.getBody().get("id")).longValue();

        assertThat(snapshotKeyForFirstItem(orderId)).isNull();
    }
}
```

- [ ] **Step 2: Run — expect failure**

Run: `./mvnw test -Dtest=OrderItemImageSnapshotIntegrationTest`
Expected: FAIL — `variant_snapshot_image_key` is always `null` (Task 1 added the column but nothing populates it yet), so the first two tests fail their `isEqualTo` assertions. (The last two tests, expecting `null`, will already pass — that's fine, they'll stay green through Step 3 rather than flip.)

- [ ] **Step 3: Add `resolveThumbnailKey` / `pickCover` and wire them in**

Add imports to `OrderService.java` (alongside the existing `com.enunas.backend.product.productlisting.*` / `com.enunas.backend.product.productvariant.*` imports):

```java
import com.enunas.backend.media.ProductImage;
import com.enunas.backend.media.storage.MediaUrlResolver;
import com.enunas.backend.product.Product;
```

`Comparator`, `Optional`, and `Predicate` are already imported.

At the `OrderItem.builder()` site inside `buildPricingDraft` (method `buildPricingDraft`, currently around line 343), add one line to the builder chain:

```java
            OrderItem item = OrderItem.builder()
                    .variant(variant)
                    .listingIdSnapshot(pl.getId())
                    .productSnapshotName(pl.getProduct().getName())
                    .brandSnapshotName(brand != null ? brand.getBrandName() : null)
                    .variantSnapshotSku(variant.getSku())
                    .variantSnapshotColor(variant.getColor())
                    .variantSnapshotSize(variant.getSize())
                    .variantSnapshotImageKey(resolveThumbnailKey(pl.getProduct(), variant))
                    .priceAtPurchase(pl.getPrice())
                    .discountPriceAtPurchase(pl.getDiscountPrice())
                    .quantity(itemDto.getQuantity())
                    .lineGross(lineGross)
                    .lineTotal(lineGross)
                    .build();
```

Add the two new private methods anywhere in `OrderService` near `buildPricingDraft` (e.g. directly below it):

```java
    /**
     * The line's purchase-time thumbnail key, mirroring what the storefront shows for the ordered
     * colourway: the colour's own cover, else the product's shared cover, else null. "Cover" within
     * a group = primary, then lowest displayOrder, then lowest id.
     *
     * <p>Deliberately NOT three-tiered to "any image regardless of colour": showing a customer a
     * different colourway's photo (a red line item with a blue product shot) reads as a picking
     * error, which is worse than a placeholder. No image is preferable to the wrong image —
     * product owner's call. See spec 2026-09-10-order-item-image-snapshot-design.md §3.
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

`product.getImages()` and `variant.getProductColor()` are lazy; `buildPricingDraft` runs inside the order-creation `@Transactional`, so both loads are safe.

- [ ] **Step 4: Run — expect pass**

Run: `./mvnw test -Dtest=OrderItemImageSnapshotIntegrationTest`
Expected: PASS, all 4 tests.

---

### Task 3: `OrderItemResponseDto.imageUrl`

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/order/dto/OrderItemResponseDto.java`
- Create: `backend/src/test/java/com/enunas/backend/order/dto/OrderItemResponseDtoTest.java`

**Interfaces:**
- Produces: `OrderItemResponseDto.getImageUrl()` → `String`, nullable. `OrderItemResponseDto.from(OrderItem, MediaUrlResolver)` — **signature change**, consumed by Task 4.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/enunas/backend/order/dto/OrderItemResponseDtoTest.java`:

```java
package com.enunas.backend.order.dto;

import com.enunas.backend.media.storage.MediaStorageProperties;
import com.enunas.backend.media.storage.MediaUrlResolver;
import com.enunas.backend.order.OrderItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class OrderItemResponseDtoTest {

    private MediaUrlResolver resolver() {
        MediaStorageProperties properties = new MediaStorageProperties();
        properties.setCdnBaseUrl("https://cdn.it.local");
        return new MediaUrlResolver(properties);
    }

    @Test
    void from_snapshotKeyPresent_resolvesImageUrl() {
        OrderItem item = OrderItem.builder()
                .id(1L).productSnapshotName("Tee").variantSnapshotSku("ABC12345")
                .variantSnapshotColor("Black").variantSnapshotSize("M")
                .variantSnapshotImageKey("products/1/images/abc.jpg")
                .priceAtPurchase(new BigDecimal("19.99")).quantity(1)
                .lineTotal(new BigDecimal("19.99")).build();

        OrderItemResponseDto dto = OrderItemResponseDto.from(item, resolver());

        assertThat(dto.getImageUrl()).isEqualTo("https://cdn.it.local/products/1/images/abc.jpg");
    }

    @Test
    void from_noSnapshotKey_imageUrlIsNull() {
        OrderItem item = OrderItem.builder()
                .id(1L).productSnapshotName("Tee").variantSnapshotSku("ABC12345")
                .variantSnapshotColor("Black").variantSnapshotSize("M")
                .priceAtPurchase(new BigDecimal("19.99")).quantity(1)
                .lineTotal(new BigDecimal("19.99")).build();

        assertThat(OrderItemResponseDto.from(item, resolver()).getImageUrl()).isNull();
    }
}
```

- [ ] **Step 2: Run — expect failure**

Run: `./mvnw test -Dtest=OrderItemResponseDtoTest`
Expected: FAIL to compile — `from(OrderItem, MediaUrlResolver)` does not exist yet.

- [ ] **Step 3: Add the field and change the factory signature**

In `OrderItemResponseDto.java`, add the import `import com.enunas.backend.media.storage.MediaUrlResolver;`, add the field, and change `from`:

```java
    private String imageUrl;

    public static OrderItemResponseDto from(OrderItem item, MediaUrlResolver resolver) {
        return OrderItemResponseDto.builder()
                .id(item.getId())
                .listingId(item.getListingIdSnapshot())
                .productName(item.getProductSnapshotName())
                .variantSku(item.getVariantSnapshotSku())
                .variantColor(item.getVariantSnapshotColor())
                .variantSize(item.getVariantSnapshotSize())
                .imageUrl(resolver.resolve(item.getVariantSnapshotImageKey()))
                .priceAtPurchase(item.getPriceAtPurchase())
                .discountPriceAtPurchase(item.getDiscountPriceAtPurchase())
                .quantity(item.getQuantity())
                .lineTotal(item.getLineTotal())
                .build();
    }
```

`MediaUrlResolver.resolve` already returns `null` for a `null`/blank key (see its own javadoc) — no ternary needed here.

**This breaks compilation everywhere `OrderItemResponseDto.from(item)` (one arg) is still called** — that is every site fixed in Task 4. Do not try to keep a one-arg overload "for compatibility"; the whole point of this task chain is that every caller now supplies a resolver.

- [ ] **Step 4: Confirm the expected compile failures, then stop**

Run: `./mvnw compile -pl . 2>&1 | grep -i OrderItemResponseDto` (or just attempt `./mvnw test -Dtest=OrderItemResponseDtoTest` — it will fail the whole module compile, not just this test). Expected: compile errors in `OrderResponseDto.java` and `OrderService.java` at every one-arg `OrderItemResponseDto.from`/`OrderResponseDto.from` call. **This is expected and will be fixed in Task 4 — do not revert this task's change.** If you want a green build before moving on, proceed directly to Task 4 in the same sitting; the module will not compile between these two tasks.

---

### Task 4: Thread `MediaUrlResolver` through `OrderResponseDto` and every `OrderService` call site

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/order/dto/OrderResponseDto.java`
- Modify: `backend/src/main/java/com/enunas/backend/order/OrderService.java`
- Modify: `backend/src/test/java/com/enunas/backend/order/integration/OrderItemImageSnapshotIntegrationTest.java` (add one test)

**Interfaces:**
- Consumes: `OrderItemResponseDto.from(OrderItem, MediaUrlResolver)` (Task 3).
- Produces: `OrderResponseDto.from(Order, MediaUrlResolver)`, `OrderResponseDto.from(Order, String, MediaUrlResolver)`, `OrderResponseDto.withReturns(Order, List<ReturnOrder>, MediaUrlResolver)`. `OrderService` gains a `MediaUrlResolver mediaUrlResolver` field.

- [ ] **Step 1: Update `OrderResponseDto`'s three factories**

```java
    public static OrderResponseDto from(Order order, MediaUrlResolver resolver) {
        return OrderResponseDto.builder()
                .id(order.getId())
                .orderNumber(order.getOrderNumber())
                .buyerId(order.getBuyer().getId())
                .buyerEmail(order.getBuyer().getEmail())
                .status(order.getStatus())
                .shippingAddress(order.getShippingAddress())
                .items(order.getItems().stream()
                        .map(item -> OrderItemResponseDto.from(item, resolver))
                        .toList())
                .subtotal(order.getSubtotal())
                .shippingTotal(order.getShippingTotal())
                .total(order.getTotal())
                .currency(order.getCurrency())
                .discountCode(order.getDiscountCode())
                .discountType(order.getDiscountType())
                .discountPercent(order.getDiscountPercent())
                .discountAmount(order.getDiscountAmount())
                .notes(order.getNotes())
                .hasShippingProblem(order.isShippingProblem())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }

    public static OrderResponseDto from(Order order, String checkoutUrl, MediaUrlResolver resolver) {
        return from(order, resolver).toBuilder().checkoutUrl(checkoutUrl).build();
    }

    public static OrderResponseDto withReturns(Order order, List<ReturnOrder> returns, MediaUrlResolver resolver) {
        var builder = from(order, resolver).toBuilder()
                .returns(returns.stream().map(ReturnSummaryDto::from).toList());

        if (returns.size() == 1) {
            ReturnOrder ret = returns.get(0);
            builder.returnNumber(ret.getReturnNumber())
                    .returnReason(ret.getReason() != null ? ret.getReason().name() : null)
                    .returnDescription(ret.getDescription())
                    .returnRequestedAt(ret.getRequestedAt())
                    .returnShipToAddress(ret.getShipToFormatted());
        }
        return builder.build();
    }
```

Add the import `import com.enunas.backend.media.storage.MediaUrlResolver;` to `OrderResponseDto.java`.

- [ ] **Step 2: Inject `MediaUrlResolver` into `OrderService`**

Add the import `import com.enunas.backend.media.storage.MediaUrlResolver;` (if not already added in Task 2) and add a field to the constructor-injected list (`@RequiredArgsConstructor` — order among the existing `private final` fields doesn't matter):

```java
    private final MediaUrlResolver mediaUrlResolver;
```

- [ ] **Step 3: Update every call site — verify the count fresh first**

**Do not trust this plan's line numbers.** Run:
```
grep -n "OrderResponseDto.from(\|OrderResponseDto.withReturns(\|OrderItemResponseDto::from\|OrderItemResponseDto.from(" backend/src/main/java/com/enunas/backend/order/OrderService.java
```
As of writing this plan that finds these 7 lines, covering 8 invocations (the two-invocation line is called out):

- `createOrder`: `return OrderResponseDto.from(saved, paymentResult.checkoutUrl())` → add `, mediaUrlResolver` before the closing paren:
  ```java
  return OrderResponseDto.from(saved, paymentResult.checkoutUrl(), mediaUrlResolver)
  ```
  (the `.toBuilder()...build()` chain after it is unchanged).

- `updateOrderStatus` (idempotent branch): `return withPaymentId(OrderResponseDto.from(order), order.getId());` →
  ```java
  return withPaymentId(OrderResponseDto.from(order, mediaUrlResolver), order.getId());
  ```

- `updateOrderStatus` (final return): `return withPaymentId(OrderResponseDto.from(saved), saved.getId());` →
  ```java
  return withPaymentId(OrderResponseDto.from(saved, mediaUrlResolver), saved.getId());
  ```

- `cancelOrder`: `return withPaymentId(OrderResponseDto.from(order), order.getId());` →
  ```java
  return withPaymentId(OrderResponseDto.from(order, mediaUrlResolver), order.getId());
  ```

- `toDto(Order, OrderRelations)` — **one line, two invocations**:
  ```java
  base = !returns.isEmpty() ? OrderResponseDto.withReturns(order, returns) : OrderResponseDto.from(order);
  ```
  becomes
  ```java
  base = !returns.isEmpty()
          ? OrderResponseDto.withReturns(order, returns, mediaUrlResolver)
          : OrderResponseDto.from(order, mediaUrlResolver);
  ```

- `toDto(Order, OrderRelations)` — the outer else, a few lines below the ternary above:
  ```java
  base = OrderResponseDto.from(order);
  ```
  becomes
  ```java
  base = OrderResponseDto.from(order, mediaUrlResolver);
  ```

- `toBrandScopedDto` — **this one is a bare method reference, not a call you can add an argument to**:
  ```java
  .items(ownItems.stream().map(OrderItemResponseDto::from).toList())
  ```
  becomes a lambda:
  ```java
  .items(ownItems.stream().map(item -> OrderItemResponseDto.from(item, mediaUrlResolver)).toList())
  ```

`withPaymentId(OrderResponseDto, Long)` itself is untouched — it mutates an already-built DTO. Its 3 callers (the `updateOrderStatus` ×2 and `cancelOrder` entries above) pass the resolver into the `from(...)` call they wrap, not into `withPaymentId`.

If your fresh grep finds a different count or different call shapes than described above (methods get refactored), that is real signal, not an error in your grep — fix every call you find, and say in your report if the count differed from 8.

- [ ] **Step 4: Compile and run the existing order integration suite**

Run: `./mvnw test -Dtest=OrderItemImageSnapshotIntegrationTest,OrderItemListingIdIntegrationTest,OrderBrandSnapshotIntegrationTest,OrderMolliePaymentIdIntegrationTest,BrandOrderIsolationIntegrationTest,DeprecatedOrderScopedReturnShimsIntegrationTest,CustomerOrderStatsIntegrationTest`
Expected: PASS — this exercises `createOrder`, `updateOrderStatus`, `cancelOrder`, `toDto` (both branches), and `toBrandScopedDto` through real HTTP calls, so a missed call site fails loudly here (compile error if missed entirely, or a stale-signature error).

- [ ] **Step 5: Add the read-path integration test**

Add to `OrderItemImageSnapshotIntegrationTest.java`:

```java
    @Test
    void getOrder_returnsResolvedImageUrlForTheOrderedColour() {
        BrandPartner brand = seedBrand("Acme5", "acme5", "0.15").brand();
        seedCustomer();
        TwoColourFixture fx = seedTwoColourProduct(brand, brand.getUser(), "50.00", 5);
        ProductImage blackCover = seedImage(fx.product(), fx.black(), true, 0);

        String customerToken = login("customer@it.local", "Customer123!");
        ResponseEntity<Map> order = postOrder(customerToken, null, List.of(item(fx.blackListingId(), 1)));
        long orderId = ((Number) order.getBody().get("id")).longValue();

        @SuppressWarnings("unchecked")
        ResponseEntity<Map> get = rest.exchange("/orders/" + orderId, org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(auth(customerToken)), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) get.getBody().get("items");
        assertThat(items.get(0).get("imageUrl")).isEqualTo("http://localhost:1/cdn/" + blackCover.getStorageKey());
    }
```

This relies on the `test` profile's `enunas.media.cdn-base-url: http://localhost:1/cdn` (see `application-test.yaml`) to make the resolved URL deterministic without an S3Mock container.

- [ ] **Step 6: Run — expect pass**

Run: `./mvnw test -Dtest=OrderItemImageSnapshotIntegrationTest`
Expected: PASS, all 5 tests.

---

### Task 5: `RefundPersistenceHelper` — its own resolver, verified independently

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/order/RefundPersistenceHelper.java`
- Create: `backend/src/test/java/com/enunas/backend/order/integration/RefundOrderItemImageSnapshotIntegrationTest.java`
- Modify: `docs/superpowers/specs/2026-09-10-order-item-image-snapshot-design.md`

**Interfaces:**
- Consumes: `OrderResponseDto.withReturns(Order, List<ReturnOrder>, MediaUrlResolver)` (Task 4).

This class was called out separately in the spec (§5) precisely because it is easy to miss: it is its own `@Component`, not part of `OrderService`, and the spec deliberately left its exact call count **unverified** rather than assert a number nobody had confirmed by reading the file. Settle it now.

- [ ] **Step 1: Verify the call count in this file, fresh**

Run: `grep -n "OrderResponseDto.from(\|OrderResponseDto.withReturns(" backend/src/main/java/com/enunas/backend/order/RefundPersistenceHelper.java`

As of writing this plan that finds exactly one line, inside `persist(...)`:
```java
return OrderResponseDto.withReturns(orderRepository.save(order), returns);
```
If your grep finds more than one, treat each the same way as below and note the higher count in your report.

- [ ] **Step 2: Write the failing test**

Create `backend/src/test/java/com/enunas/backend/order/integration/RefundOrderItemImageSnapshotIntegrationTest.java` — full order → ship → deliver → return → approve → receive → refund flow (mirrors the working pattern in `DeprecatedOrderScopedReturnShimsIntegrationTest`), with a colour-tagged cover image, asserting the refund response resolves it:

```java
package com.enunas.backend.order.integration;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import com.enunas.backend.media.ProductImage;
import com.enunas.backend.media.ProductImageRepository;
import com.enunas.backend.product.Product;
import com.enunas.backend.product.productvariant.ColorFamily;
import com.enunas.backend.product.productvariant.ProductColor;
import com.enunas.backend.product.productvariant.ProductVariant;
import com.enunas.backend.product.productlisting.PriceInputMode;
import com.enunas.backend.product.productlisting.ProductListing;
import com.enunas.backend.user.EmailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RefundPersistenceHelper.persist builds its OrderResponseDto via a SEPARATE injected
 * MediaUrlResolver from OrderService's — a genuinely distinct piece of wiring (it is its own
 * @Component so its own @Transactional proxy applies). Exercised on its own rather than assumed
 * correct because OrderService's tests pass.
 */
class RefundOrderItemImageSnapshotIntegrationTest extends AbstractDiscountIntegrationTest {

    @Autowired private ProductImageRepository imageRepository;
    @MockitoBean private EmailService emailService;

    @Test
    void refundResponse_resolvesTheOrderedColoursImageUrl() {
        BrandPartner brand = seedBrand("AcmeR", "acmer", "0.15").brand();
        seedCustomer();
        seedAdmin();

        String name = "Prod-" + UUID.randomUUID().toString().substring(0, 6);
        Product p = productRepository.save(Product.builder()
                .name(name).slug(name.toLowerCase()).brand(brand).creator(brand.getUser()).build());
        ProductColor black = productColorRepository.save(ProductColor.builder()
                .sku(UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase())
                .color("Black").colorFamily(ColorFamily.BLACK).product(p).build());
        ProductVariant variant = productVariantRepository.save(ProductVariant.builder()
                .productColor(black).size("M").stockQuantity(5).product(p).build());
        long listingId = productListingRepository.save(ProductListing.builder()
                .product(p).variant(variant).price(new BigDecimal("50.00"))
                .priceInputMode(PriceInputMode.GROSS).currency("EUR").active(true).build()).getId();
        ProductImage cover = imageRepository.save(ProductImage.builder()
                .product(p).productColor(black)
                .storageKey("products/" + p.getId() + "/images/" + UUID.randomUUID() + ".jpg")
                .primary(true).displayOrder(0).build());

        String customerToken = login("customer@it.local", "Customer123!");
        String brandToken = login("acmer@it.local", "Brand123!");
        String adminToken = login("admin@it.local", "Admin123!");

        ResponseEntity<Map> order = postOrder(customerToken, null, List.of(item(listingId, 1)));
        long orderId = ((Number) order.getBody().get("id")).longValue();
        confirmPaid(orderId);

        rest.exchange("/brand/orders/" + orderId + "/ship", HttpMethod.POST,
                new HttpEntity<>(Map.of("carrier", "DHL", "trackingNumber", "T1"), auth(brandToken)), Map.class);
        rest.exchange("/admin/orders/" + orderId + "/status?status=DELIVERED", HttpMethod.PATCH,
                new HttpEntity<>(null, auth(adminToken)), Map.class);

        Map<String, Object> returnBody = new HashMap<>();
        returnBody.put("reason", "WRONG_SIZE");
        returnBody.put("description", "Passt nicht");
        rest.exchange("/orders/" + orderId + "/return", HttpMethod.POST,
                new HttpEntity<>(returnBody, auth(customerToken)), Map.class);
        rest.exchange("/admin/orders/" + orderId + "/return/approve", HttpMethod.POST,
                new HttpEntity<>(null, auth(adminToken)), Map.class);
        rest.exchange("/admin/orders/" + orderId + "/return/receive", HttpMethod.POST,
                new HttpEntity<>(null, auth(adminToken)), Map.class);

        @SuppressWarnings("unchecked")
        ResponseEntity<Map> refund = rest.exchange("/admin/orders/" + orderId + "/return/refund",
                HttpMethod.POST, new HttpEntity<>(null, auth(adminToken)), Map.class);

        assertThat(refund.getStatusCode().value()).as("body: %s", refund.getBody()).isEqualTo(200);
        assertThat(refund.getBody().get("status")).isEqualTo("REFUNDED");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) refund.getBody().get("items");
        assertThat(items).isNotEmpty();
        assertThat(items.get(0).get("imageUrl")).isEqualTo("http://localhost:1/cdn/" + cover.getStorageKey());
    }
}
```

- [ ] **Step 3: Run — expect failure**

Run: `./mvnw test -Dtest=RefundOrderItemImageSnapshotIntegrationTest`
Expected: FAIL to compile (`OrderResponseDto.withReturns` still takes 2 args in `RefundPersistenceHelper`, and adding the 3-arg call elsewhere already broke the 2-arg overload's existence in Task 4 Step 1 — `RefundPersistenceHelper.java:94` is now the one remaining caller of a signature that no longer exists).

- [ ] **Step 4: Fix `RefundPersistenceHelper`**

Add the import `import com.enunas.backend.media.storage.MediaUrlResolver;`, add the field:

```java
    private final MediaUrlResolver mediaUrlResolver;
```

And update the call at the end of `persist(...)`:

```java
        return OrderResponseDto.withReturns(orderRepository.save(order), returns, mediaUrlResolver);
```

- [ ] **Step 5: Run — expect pass**

Run: `./mvnw test -Dtest=RefundOrderItemImageSnapshotIntegrationTest`
Expected: PASS.

- [ ] **Step 6: Reconcile the spec's hedge into a settled number**

Open `docs/superpowers/specs/2026-09-10-order-item-image-snapshot-design.md`, section 5. Find the paragraph starting `**\`RefundPersistenceHelper\` — verify independently, do not carry a line number forward for it.**` and its closing sentence about the cross-class total being unsettled. Replace that closing sentence with the confirmed result from Step 1 above, e.g.:

> Confirmed at implementation time (grep on `RefundPersistenceHelper.java`): exactly 1 call site
> (`persist`, `OrderResponseDto.withReturns`). Cross-class total: **9** — 8 in `OrderService` +
> 1 in `RefundPersistenceHelper`.

(Adjust the numbers if your Step 1 grep found something different — the point is that this sentence must state a number someone actually counted during this implementation, not repeat the spec's earlier hedge or guess.) This is the one piece of "open" language in the spec that must not survive past this task — say so explicitly in your final report too.

---

### Task 6: Full verification

- [ ] **Step 1: Run the full order + media test surface**

Run: `./mvnw test -Dtest=OrderItemImageSnapshotIntegrationTest,RefundOrderItemImageSnapshotIntegrationTest,OrderItemResponseDtoTest,OrderItemListingIdIntegrationTest,OrderBrandSnapshotIntegrationTest,OrderMolliePaymentIdIntegrationTest,BrandOrderIsolationIntegrationTest,DeprecatedOrderScopedReturnShimsIntegrationTest,CustomerOrderStatsIntegrationTest,DeprecatedOrderScopedReturnShimsIntegrationTest,ReturnLifecyclePhase3Test`
Expected: all PASS.

- [ ] **Step 2: Run the full suite once**

Run: `./mvnw test`
Expected: green. A lone `NoClassDefFoundError`/file-lock error on an unrelated class is environment interference (OneDrive) — retry that class alone before treating it as a real failure.

- [ ] **Step 3: Confirm nothing was committed**

`git status` should show every file from this plan as modified/untracked and unstaged. Hand back to the requester for review and commit.

## Self-Review (completed by plan author)

- **Spec coverage:** §1 schema → Task 1. §2 entity → Task 1. §3 selection logic + assumption note → Task 2. §4 read path → Task 3. §5 threading (including the `toBrandScopedDto` lambda conversion and the `RefundPersistenceHelper` reconciliation) → Tasks 4–5. §6 tests → Tasks 2–5 (integration-test strategy substituted for the spec's "unit test" framing, with the reason stated in Task 2). Acceptance criteria → Task 6.
- **Placeholder scan:** none — every step has concrete code, an exact grep/test command, or a fully worded doc edit.
- **Type consistency:** `resolveThumbnailKey(Product, ProductVariant)` and `pickCover(List<ProductImage>, Predicate<ProductImage>)` defined once in Task 2, never redeclared. `OrderItemResponseDto.from(OrderItem, MediaUrlResolver)` defined in Task 3, consumed by every site in Task 4 and Task 5. `OrderResponseDto.from`/`withReturns`'s new resolver parameter is consistently named `mediaUrlResolver` at every call site.
- **Known-drift risk flagged in-plan:** every line-number reference carries "verify fresh, method names are the anchor" language, per the spec's own hard-won lesson from three miscounts in review.
