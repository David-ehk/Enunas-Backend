# Colourway-Specific Product Images Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a single connected product carry colourway-specific gallery images — an image may be tagged to one `ProductColor` or left shared (untagged, shown for every colourway).

**Architecture:** One nullable FK `product_images.product_color_id → product_colors(id)`. `null` = shared. The PDP gallery for a selected colour = its tagged images ∪ all untagged images. "Primary" (the card thumbnail) becomes one-per-colour-group, enforced by two partial unique indexes. A new `PATCH .../media/images/{id}` re-tags/edits an image without re-uploading bytes. The S3 key layout, presign flow, and `MediaPurpose` are untouched — colour is DB metadata only.

**Tech Stack:** Java 21, Spring Boot 4.0.5, Spring Data JPA, Flyway, PostgreSQL (Testcontainers in tests), Lombok, JUnit 5 + Mockito + AssertJ, `@DataJpaTest` for repository tests, `@SpringBootTest` + S3Mock Testcontainer for the media E2E.

**Spec:** `docs/superpowers/specs/2026-09-09-colourway-specific-product-images-design.md` — read it before starting; every task argues from it.

## Global Constraints

- **DO NOT run `git add`, `git commit`, or `git push` at any point.** The requester commits manually. Every task ends at a passing test, not a commit. Work directly on the current branch (`master`) — no new branch, no worktree.
- **Flyway: never edit an applied migration.** The new script is `V32__product_image_colour.sql`. Latest existing is `V31`.
- **`ddl-auto: validate`** — Hibernate never changes the schema. Entity mappings must match the migration exactly or the app won't start. `@Index`/`length`/`@ForeignKey` annotations are documentation only; the migration is the source of truth.
- **British spelling `colour` in new identifiers** where it doesn't collide with existing code. NOTE the existing codebase uses American `color` (`ProductColor`, `getColor()`, `colorFamily`, `product_color_id` column). Match the existing spelling for anything touching existing types and the DB column; the migration filename and index names in this plan use `colour` deliberately and consistently. When in doubt, match the file you're editing.
- **Partial update semantics:** an omitted DTO field means "unchanged". Use boxed types (`Boolean`, `Long`, `Integer`) for optional fields.
- Build: `./mvnw test` from `backend/`. Single test: `./mvnw test -Dtest=ClassName` or `-Dtest=ClassName#method`. Run commands from the `backend/` directory.
- OneDrive/VS Code file locks can break `mvn clean` and cause mass `NoClassDefFoundError` — that is environment interference, not a regression. Retry without `clean`, or wait and retry.

---

### Task 1: Migration + entity mappings

**Files:**
- Create: `backend/src/main/resources/db/migration/V32__product_image_colour.sql`
- Modify: `backend/src/main/java/com/enunas/backend/media/ProductImage.java`
- Modify: `backend/src/main/java/com/enunas/backend/product/Product.java`
- Test: `backend/src/test/java/com/enunas/backend/media/ProductMediaStorageKeyTest.java` (add cases)

**Interfaces:**
- Produces:
  - `ProductImage.getProductColor()` / `setProductColor(ProductColor)` — nullable.
  - `Product.getColors()` → `List<ProductColor>` (read-only, unmodifiable).
  - DB column `product_images.product_color_id BIGINT NULL`, FK to `product_colors(id)` `ON DELETE SET NULL`.
  - Indexes `idx_product_images_colour`, `uq_product_images_primary_per_colour`, `uq_product_images_primary_shared`.

- [ ] **Step 1: Write the migration**

Create `backend/src/main/resources/db/migration/V32__product_image_colour.sql`:

```sql
-- =============================================================================
-- V32: colourway-specific product images.
--
-- product_images belonged to a product only, so a product's WHITE and BLACK
-- colourways could not show different photos. Add an optional link to
-- product_colors: NULL = shared (shown for every colourway), set = specific to
-- that colourway. No backfill — existing rows stay NULL, i.e. shared, which is
-- exactly today's behaviour.
--
-- V0.0.1..V31 are left untouched (editing an applied migration breaks its
-- Flyway checksum and the app refuses to start).
-- =============================================================================

ALTER TABLE product_images
    ADD COLUMN product_color_id BIGINT
    REFERENCES product_colors (id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_product_images_colour
    ON product_images (product_color_id);

-- "One primary per colour-group" as two partial unique indexes — the house idiom
-- (cf. V6 uq_ledger_order_payment, V15 uq_returns_active_per_brand). Two indexes,
-- not one over COALESCE(product_color_id, 0): a sentinel would silently couple
-- correctness to "no product_colors.id is ever 0".

-- (a) at most one primary among a colourway's own images
CREATE UNIQUE INDEX IF NOT EXISTS uq_product_images_primary_per_colour
    ON product_images (product_id, product_color_id)
    WHERE is_primary AND product_color_id IS NOT NULL;

-- (b) at most one primary among a product's shared (untagged) images
CREATE UNIQUE INDEX IF NOT EXISTS uq_product_images_primary_shared
    ON product_images (product_id)
    WHERE is_primary AND product_color_id IS NULL;
```

- [ ] **Step 2: Add the mapping to `ProductImage`**

In `backend/src/main/java/com/enunas/backend/media/ProductImage.java`, add an import for `com.enunas.backend.product.productvariant.ProductColor` and this field after `product`:

```java
    /** The colourway this image is specific to. Null = shared: shown for every colourway. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_color_id")
    private ProductColor productColor;
```

- [ ] **Step 3: Add the read-only `colors` association to `Product`**

In `backend/src/main/java/com/enunas/backend/product/Product.java`, mirror the existing `images` / `variants` pattern. Add the field alongside them:

```java
    @OneToMany(mappedBy = "product")
    @Builder.Default
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private List<ProductColor> colors = new ArrayList<>();
```

And a getter next to `getImages()`:

```java
    public List<ProductColor> getColors() {
        return colors == null ? List.of() : Collections.unmodifiableList(colors);
    }
```

`ProductColor` is already imported (`com.enunas.backend.product.productvariant.ProductColor` — check the import block; `ProductVariant` from the same package is imported, add `ProductColor` the same way).

- [ ] **Step 4: Write the failing repository/entity tests**

Add to `backend/src/test/java/com/enunas/backend/media/ProductMediaStorageKeyTest.java`. Add imports for `com.enunas.backend.product.productvariant.ProductColor`, `com.enunas.backend.product.productvariant.ProductColorRepository`, `com.enunas.backend.product.productvariant.ColorFamily`, and `@Autowired private ProductColorRepository colorRepository;`.

```java
    @Test
    void productImage_colourTag_persistsAndReadsBack() {
        Product product = seedProduct();
        ProductColor black = colorRepository.save(ProductColor.builder()
                .sku("SKUBLACK").color("Black").colorFamily(ColorFamily.BLACK).product(product).build());

        ProductImage saved = imageRepository.save(ProductImage.builder()
                .product(product).productColor(black)
                .storageKey("products/" + product.getId() + "/images/b.jpg")
                .displayOrder(0).build());

        assertThat(imageRepository.findById(saved.getId()).orElseThrow()
                .getProductColor().getId()).isEqualTo(black.getId());
    }

    @Test
    void productImage_colourTag_isOptional() {
        Product product = seedProduct();
        ProductImage saved = imageRepository.save(ProductImage.builder()
                .product(product).storageKey("products/" + product.getId() + "/images/s.jpg")
                .displayOrder(0).build());

        assertThat(imageRepository.findById(saved.getId()).orElseThrow().getProductColor()).isNull();
    }

    @Test
    void productImage_twoPrimariesInSameColourGroup_violateUniqueIndex() {
        Product product = seedProduct();
        ProductColor black = colorRepository.save(ProductColor.builder()
                .sku("SKUBLK2").color("Black").colorFamily(ColorFamily.BLACK).product(product).build());
        imageRepository.saveAndFlush(ProductImage.builder().product(product).productColor(black)
                .storageKey("products/" + product.getId() + "/images/b1.jpg").primary(true).build());

        assertThatThrownBy(() -> imageRepository.saveAndFlush(ProductImage.builder()
                .product(product).productColor(black)
                .storageKey("products/" + product.getId() + "/images/b2.jpg").primary(true).build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void productImage_primaryInDifferentColourGroups_isAllowed() {
        Product product = seedProduct();
        ProductColor black = colorRepository.save(ProductColor.builder()
                .sku("SKUBLK3").color("Black").colorFamily(ColorFamily.BLACK).product(product).build());
        ProductColor white = colorRepository.save(ProductColor.builder()
                .sku("SKUWHT3").color("White").colorFamily(ColorFamily.WHITE).product(product).build());

        imageRepository.saveAndFlush(ProductImage.builder().product(product).productColor(black)
                .storageKey("products/" + product.getId() + "/images/b.jpg").primary(true).build());
        imageRepository.saveAndFlush(ProductImage.builder().product(product).productColor(white)
                .storageKey("products/" + product.getId() + "/images/w.jpg").primary(true).build());
        imageRepository.saveAndFlush(ProductImage.builder().product(product)
                .storageKey("products/" + product.getId() + "/images/shared.jpg").primary(true).build());

        assertThat(imageRepository.findByProductIdOrderByDisplayOrderAsc(product.getId())).hasSize(3);
    }
```

Check `ColorFamily` has `BLACK` / `WHITE` constants — open `backend/src/main/java/com/enunas/backend/product/productvariant/ColorFamily.java` and use whatever two distinct constants it actually defines.

- [ ] **Step 5: Run the tests — expect failure**

Run: `./mvnw test -Dtest=ProductMediaStorageKeyTest`
Expected: the four new tests FAIL to compile (`productColor` builder method unknown) or fail at runtime (column missing) until Steps 1–3 are in place. If Steps 1–3 are already saved, they should pass — in that case verify by temporarily removing the mapping and re-adding.

- [ ] **Step 6: Run tests — expect pass**

Run: `./mvnw test -Dtest=ProductMediaStorageKeyTest`
Expected: PASS (all original + 4 new).

- [ ] **Step 7: Verify schema validation still boots**

Run: `./mvnw test -Dtest=MediaEndToEndIntegrationTest`
Expected: PASS — proves `V32` applies and Hibernate `validate` accepts the new mapping.

---

### Task 2: Repository query for the colour-filtered read + per-group primary lookup

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/media/ProductImageRepository.java`
- Test: `backend/src/test/java/com/enunas/backend/media/ProductMediaStorageKeyTest.java` (add cases)

**Interfaces:**
- Consumes: `ProductImage.getProductColor()` (Task 1).
- Produces:
  - `List<ProductImage> findForProductAndOptionalColour(Long productId, Long colorId)` — `colorId == null` → all images for the product; `colorId` set → images tagged with that colour **plus** all untagged images. `productColor` is fetch-joined so the result is safe to map outside a transaction.
  - `Optional<ProductImage> findByProductIdAndProductColorIdAndPrimary(Long productId, Long productColorId, boolean primary)` — `productColorId == null` matches rows where `product_color_id IS NULL`.

- [ ] **Step 1: Add the repository methods**

In `backend/src/main/java/com/enunas/backend/media/ProductImageRepository.java` add imports for `org.springframework.data.jpa.repository.Query` and `org.springframework.data.repository.query.Param`, then:

```java
    Optional<ProductImage> findByProductIdAndProductColorIdAndPrimary(
            Long productId, Long productColorId, boolean primary);

    @Query("""
            SELECT i FROM ProductImage i
            LEFT JOIN FETCH i.productColor
            WHERE i.product.id = :productId
              AND (:colorId IS NULL OR i.productColor.id = :colorId OR i.productColor IS NULL)
            """)
    List<ProductImage> findForProductAndOptionalColour(
            @Param("productId") Long productId, @Param("colorId") Long colorId);
```

Keep the existing `findByProductIdOrderByDisplayOrderAsc` and `findByProductIdAndPrimary` — other code and tests still use them.

- [ ] **Step 2: Write the failing tests**

Add to `ProductMediaStorageKeyTest.java`:

```java
    @Test
    void findForProduct_noColourFilter_returnsAll() {
        Product product = seedProduct();
        ProductColor black = colorRepository.save(ProductColor.builder()
                .sku("SKUF1").color("Black").colorFamily(ColorFamily.BLACK).product(product).build());
        imageRepository.save(ProductImage.builder().product(product).productColor(black)
                .storageKey("products/" + product.getId() + "/images/b.jpg").displayOrder(1).build());
        imageRepository.save(ProductImage.builder().product(product)
                .storageKey("products/" + product.getId() + "/images/s.jpg").displayOrder(0).build());

        assertThat(imageRepository.findForProductAndOptionalColour(product.getId(), null)).hasSize(2);
    }

    @Test
    void findForProduct_withColourFilter_returnsTaggedPlusShared_notOtherColours() {
        Product product = seedProduct();
        ProductColor black = colorRepository.save(ProductColor.builder()
                .sku("SKUF2B").color("Black").colorFamily(ColorFamily.BLACK).product(product).build());
        ProductColor white = colorRepository.save(ProductColor.builder()
                .sku("SKUF2W").color("White").colorFamily(ColorFamily.WHITE).product(product).build());
        imageRepository.save(ProductImage.builder().product(product).productColor(black)
                .storageKey("products/" + product.getId() + "/images/b.jpg").displayOrder(0).build());
        imageRepository.save(ProductImage.builder().product(product).productColor(white)
                .storageKey("products/" + product.getId() + "/images/w.jpg").displayOrder(0).build());
        imageRepository.save(ProductImage.builder().product(product)
                .storageKey("products/" + product.getId() + "/images/s.jpg").displayOrder(0).build());

        List<ProductImage> forBlack =
                imageRepository.findForProductAndOptionalColour(product.getId(), black.getId());

        assertThat(forBlack).extracting(i -> i.getStorageKey().substring(i.getStorageKey().lastIndexOf('/') + 1))
                .containsExactlyInAnyOrder("b.jpg", "s.jpg");
    }

    @Test
    void findByProductIdAndProductColorIdAndPrimary_nullColour_matchesSharedGroup() {
        Product product = seedProduct();
        imageRepository.save(ProductImage.builder().product(product)
                .storageKey("products/" + product.getId() + "/images/s.jpg").primary(true).build());

        assertThat(imageRepository.findByProductIdAndProductColorIdAndPrimary(product.getId(), null, true))
                .isPresent();
    }
```

Add `import java.util.List;` if not present.

- [ ] **Step 3: Run — expect failure**

Run: `./mvnw test -Dtest=ProductMediaStorageKeyTest`
Expected: the 3 new tests FAIL to compile (methods don't exist) before Step 1, PASS after.

- [ ] **Step 4: Run — expect pass**

Run: `./mvnw test -Dtest=ProductMediaStorageKeyTest`
Expected: PASS. If `findByProductIdAndProductColorIdAndPrimary(..., null, ...)` throws instead of generating `IS NULL`, replace it with an explicit `@Query`:

```java
    @Query("SELECT i FROM ProductImage i WHERE i.product.id = :productId AND i.primary = :primary " +
           "AND (:colorId IS NULL AND i.productColor IS NULL OR i.productColor.id = :colorId)")
    Optional<ProductImage> findByProductIdAndProductColorIdAndPrimary(
            @Param("productId") Long productId, @Param("colorId") Long colorId, @Param("primary") boolean primary);
```

---

### Task 3: DTO fields — `ProductImageDto`, `UpdateProductImageDto`, `ProductImageResponseDto`

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/media/dto/ProductImageDto.java`
- Create: `backend/src/main/java/com/enunas/backend/media/dto/UpdateProductImageDto.java`
- Modify: `backend/src/main/java/com/enunas/backend/media/dto/ProductImageResponseDto.java`
- Test: `backend/src/test/java/com/enunas/backend/media/ProductMediaDtoTest.java`

**Interfaces:**
- Produces:
  - `ProductImageDto.getProductColorId()` → `Long` (nullable), on the create/confirm body.
  - `UpdateProductImageDto` with `Long getProductColorId()`, `Boolean getUnassignColor()`, `Boolean getPrimary()`, `String getAltText()`, `Integer getDisplayOrder()` — all nullable.
  - `ProductImageResponseDto.getProductColorId()` → `Long` (nullable), `getColor()` → `String` (nullable).
  - `ProductImageResponseDto.from(ProductImage, MediaUrlResolver)` unchanged signature, now also populates the two new fields.

- [ ] **Step 1: Add `productColorId` to `ProductImageDto`**

```java
    /** Optional: tag this image to a colourway. Null = shared (shown for every colourway). */
    private Long productColorId;
```

- [ ] **Step 2: Create `UpdateProductImageDto`**

`backend/src/main/java/com/enunas/backend/media/dto/UpdateProductImageDto.java`:

```java
package com.enunas.backend.media.dto;

import lombok.Data;

/**
 * PATCH body for image metadata. Every field is optional; an omitted field is left unchanged.
 *
 * <p>Colour: send {@code productColorId} (a positive id) to assign, or {@code unassignColor=true}
 * to move the image back to the shared group. {@code updateMyProfile}/{@code updateVariant} have
 * no field-clearing mechanism at all, so an explicit flag is used rather than a magic value.
 */
@Data
public class UpdateProductImageDto {

    private Long productColorId;
    private Boolean unassignColor;
    private Boolean primary;
    private String altText;
    private Integer displayOrder;
}
```

- [ ] **Step 3: Add the response fields**

In `ProductImageResponseDto.java` add two fields and populate them in `from(...)`:

```java
    private Long productColorId;
    private String color;
```

```java
    public static ProductImageResponseDto from(ProductImage image, MediaUrlResolver resolver) {
        return ProductImageResponseDto.builder()
                .id(image.getId())
                .imageUrl(resolver.resolve(image.getStorageKey()))
                .altText(image.getAltText())
                .primary(image.isPrimary())
                .displayOrder(image.getDisplayOrder())
                .productColorId(image.getProductColor() != null ? image.getProductColor().getId() : null)
                .color(image.getProductColor() != null ? image.getProductColor().getColor() : null)
                .createdAt(image.getCreatedAt())
                .build();
    }
```

- [ ] **Step 4: Write the failing test**

Add to `ProductMediaDtoTest.java` (add imports for `com.enunas.backend.product.Product`, `com.enunas.backend.product.productvariant.ProductColor`, `com.enunas.backend.product.productvariant.ColorFamily`):

```java
    @Test
    void productImageResponseDto_taggedImage_carriesColourIdAndName() {
        Product product = Product.builder().id(7L).build();
        ProductColor black = ProductColor.builder().id(3L).color("Black")
                .colorFamily(ColorFamily.BLACK).product(product).build();
        ProductImage image = ProductImage.builder()
                .id(1L).storageKey("products/7/images/b.jpg").productColor(black)
                .primary(true).displayOrder(0).createdAt(LocalDateTime.now()).build();

        ProductImageResponseDto dto = ProductImageResponseDto.from(image, resolver());

        assertThat(dto.getProductColorId()).isEqualTo(3L);
        assertThat(dto.getColor()).isEqualTo("Black");
    }

    @Test
    void productImageResponseDto_sharedImage_hasNullColourFields() {
        ProductImage image = ProductImage.builder()
                .id(1L).storageKey("products/7/images/s.jpg")
                .primary(false).displayOrder(0).createdAt(LocalDateTime.now()).build();

        ProductImageResponseDto dto = ProductImageResponseDto.from(image, resolver());

        assertThat(dto.getProductColorId()).isNull();
        assertThat(dto.getColor()).isNull();
    }
```

- [ ] **Step 5: Run — expect fail then pass**

Run: `./mvnw test -Dtest=ProductMediaDtoTest`
Expected: FAIL (no `getProductColorId`) before Steps 1–3, PASS after.

---

### Task 4: `MediaService.addImage` — colour validation + per-group primary

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/media/MediaService.java`
- Test: `backend/src/test/java/com/enunas/backend/media/MediaServiceTest.java`

**Interfaces:**
- Consumes: `ProductImageDto.getProductColorId()` (Task 3); `ProductImageRepository.findByProductIdAndProductColorIdAndPrimary` (Task 2); `ProductColorRepository.findById`.
- Produces: `addImage` now tags the image when `productColorId` is set, rejects a colour id that belongs to another product with `SecurityException` (→ 403), and scopes the "unset previous primary" step to the image's colour group.

- [ ] **Step 1: Inject `ProductColorRepository`**

In `MediaService.java` add `import com.enunas.backend.product.productvariant.ProductColor;` and `import com.enunas.backend.product.productvariant.ProductColorRepository;`, then add to the constructor field list (it uses `@RequiredArgsConstructor`, so just add a `private final` field):

```java
    private final ProductColorRepository productColorRepository;
```

- [ ] **Step 2: Write the failing tests**

Add to `MediaServiceTest.java`. Add `@Mock private ProductColorRepository productColorRepository;` to the mock list, and imports for `ProductColor` / `ColorFamily` from `com.enunas.backend.product.productvariant`.

```java
    @Test
    void addImage_withColourOfAnotherProduct_throwsSecurityException() {
        when(productRepository.findById(42L)).thenReturn(Optional.of(product));
        Product otherProduct = Product.builder().id(99L).build();
        ProductColor foreignColour = ProductColor.builder().id(7L).color("Black")
                .colorFamily(ColorFamily.BLACK).product(otherProduct).build();
        when(productColorRepository.findById(7L)).thenReturn(Optional.of(foreignColour));

        ProductImageDto dto = new ProductImageDto();
        dto.setStorageKey("products/42/images/abc.jpg");
        dto.setProductColorId(7L);

        assertThatThrownBy(() -> mediaService.addImage(42L, dto, owner))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void addImage_primary_unsetsOnlyWithinSameColourGroup() {
        when(productRepository.findById(42L)).thenReturn(Optional.of(product));
        when(imageRepository.save(any(ProductImage.class))).thenAnswer(inv -> inv.getArgument(0));
        ProductColor black = ProductColor.builder().id(3L).color("Black")
                .colorFamily(ColorFamily.BLACK).product(product).build();
        when(productColorRepository.findById(3L)).thenReturn(Optional.of(black));
        ProductImage oldPrimary = ProductImage.builder().id(50L).product(product)
                .productColor(black).storageKey("products/42/images/old.jpg").primary(true).build();
        when(imageRepository.findByProductIdAndProductColorIdAndPrimary(42L, 3L, true))
                .thenReturn(Optional.of(oldPrimary));

        ProductImageDto dto = new ProductImageDto();
        dto.setStorageKey("products/42/images/new.jpg");
        dto.setProductColorId(3L);
        dto.setPrimary(true);

        mediaService.addImage(42L, dto, owner);

        assertThat(oldPrimary.isPrimary()).isFalse();
        verify(imageRepository).save(oldPrimary);
    }

    @Test
    void addImage_sharedPrimary_looksUpNullColourGroup() {
        when(productRepository.findById(42L)).thenReturn(Optional.of(product));
        when(imageRepository.save(any(ProductImage.class))).thenAnswer(inv -> inv.getArgument(0));
        when(imageRepository.findByProductIdAndProductColorIdAndPrimary(42L, null, true))
                .thenReturn(Optional.empty());

        ProductImageDto dto = new ProductImageDto();
        dto.setStorageKey("products/42/images/new.jpg");
        dto.setPrimary(true);

        mediaService.addImage(42L, dto, owner);

        verify(imageRepository).findByProductIdAndProductColorIdAndPrimary(42L, null, true);
    }
```

The existing `addImage_confirmsUploadBeforeSaving` test currently stubs `imageRepository.findByProductIdAndPrimary`. After Step 3 the code calls `findByProductIdAndProductColorIdAndPrimary` instead — update that existing test's stub/verify accordingly (change `findByProductIdAndPrimary(productId, true)` to `findByProductIdAndProductColorIdAndPrimary(42L, null, true)`), and it should still pass since `dto.isPrimary()` defaults to `false` there (no lookup happens).

- [ ] **Step 3: Rewrite `addImage`**

Replace the body of `addImage` in `MediaService.java`:

```java
    @Transactional
    public ProductImageResponseDto addImage(Long productId, ProductImageDto dto, User owner) {
        Product product = findProductAndVerifyOwnership(productId, owner);
        mediaStorageService.verifyUploaded(dto.getStorageKey(), MediaPurpose.PRODUCT_IMAGE, productId);

        ProductColor colour = resolveColourForProduct(dto.getProductColorId(), productId);

        if (dto.isPrimary()) {
            imageRepository.findByProductIdAndProductColorIdAndPrimary(productId, dto.getProductColorId(), true)
                    .ifPresent(img -> {
                        img.setPrimary(false);
                        imageRepository.save(img);
                    });
        }

        ProductImage image = ProductImage.builder()
                .product(product)
                .productColor(colour)
                .storageKey(dto.getStorageKey())
                .altText(dto.getAltText())
                .primary(dto.isPrimary())
                .displayOrder(dto.getDisplayOrder())
                .build();

        return ProductImageResponseDto.from(imageRepository.save(image), mediaUrlResolver);
    }

    /**
     * Loads the colourway a media mutation targets and asserts it belongs to {@code productId}.
     * Null id → null (the shared group). A colour id from another product is a cross-tenant
     * attempt — same 403 as a foreign storageKey.
     */
    private ProductColor resolveColourForProduct(Long productColorId, Long productId) {
        if (productColorId == null) {
            return null;
        }
        ProductColor colour = productColorRepository.findById(productColorId)
                .orElseThrow(() -> new ProductNotFoundException("Colour not found with id: " + productColorId));
        if (!colour.getProduct().getId().equals(productId)) {
            throw new SecurityException("Colour does not belong to this product");
        }
        return colour;
    }
```

- [ ] **Step 4: Run — expect pass**

Run: `./mvnw test -Dtest=MediaServiceTest`
Expected: PASS (existing + 3 new).

---

### Task 5: `updateImage` service method + `PATCH` endpoint

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/media/MediaService.java`
- Modify: `backend/src/main/java/com/enunas/backend/media/MediaController.java`
- Test: `backend/src/test/java/com/enunas/backend/media/MediaServiceTest.java`

**Interfaces:**
- Consumes: `UpdateProductImageDto` (Task 3); `resolveColourForProduct` (Task 4); `ProductImageRepository.findById` / `findByProductIdAndProductColorIdAndPrimary`.
- Produces:
  - `MediaService.updateImage(Long productId, Long imageId, UpdateProductImageDto dto, User owner)` → `ProductImageResponseDto`.
  - `PATCH /products/{productId}/media/images/{imageId}` → `200` `ProductImageResponseDto`; `403` if the image is on another product or the colour id belongs to another product; `404` if no such image.

- [ ] **Step 1: Write the failing tests**

Add to `MediaServiceTest.java`:

```java
    @Test
    void updateImage_reassignsColour() {
        when(productRepository.findById(42L)).thenReturn(Optional.of(product));
        when(imageRepository.save(any(ProductImage.class))).thenAnswer(inv -> inv.getArgument(0));
        ProductColor white = ProductColor.builder().id(4L).color("White")
                .colorFamily(ColorFamily.WHITE).product(product).build();
        when(productColorRepository.findById(4L)).thenReturn(Optional.of(white));
        ProductImage image = ProductImage.builder().id(5L).product(product)
                .storageKey("products/42/images/x.jpg").primary(false).build();
        when(imageRepository.findById(5L)).thenReturn(Optional.of(image));

        UpdateProductImageDto dto = new UpdateProductImageDto();
        dto.setProductColorId(4L);
        mediaService.updateImage(42L, 5L, dto, owner);

        assertThat(image.getProductColor()).isEqualTo(white);
    }

    @Test
    void updateImage_unassignColour_movesToShared() {
        when(productRepository.findById(42L)).thenReturn(Optional.of(product));
        when(imageRepository.save(any(ProductImage.class))).thenAnswer(inv -> inv.getArgument(0));
        ProductColor black = ProductColor.builder().id(3L).color("Black")
                .colorFamily(ColorFamily.BLACK).product(product).build();
        ProductImage image = ProductImage.builder().id(5L).product(product).productColor(black)
                .storageKey("products/42/images/x.jpg").build();
        when(imageRepository.findById(5L)).thenReturn(Optional.of(image));

        UpdateProductImageDto dto = new UpdateProductImageDto();
        dto.setUnassignColor(true);
        mediaService.updateImage(42L, 5L, dto, owner);

        assertThat(image.getProductColor()).isNull();
        verifyNoInteractions(productColorRepository);
    }

    @Test
    void updateImage_imageOnAnotherProduct_throwsSecurityException() {
        when(productRepository.findById(42L)).thenReturn(Optional.of(product));
        Product other = Product.builder().id(77L).build();
        ProductImage image = ProductImage.builder().id(5L).product(other)
                .storageKey("products/77/images/x.jpg").build();
        when(imageRepository.findById(5L)).thenReturn(Optional.of(image));

        assertThatThrownBy(() -> mediaService.updateImage(42L, 5L, new UpdateProductImageDto(), owner))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void updateImage_missingImage_throwsNotFound() {
        when(productRepository.findById(42L)).thenReturn(Optional.of(product));
        when(imageRepository.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mediaService.updateImage(42L, 5L, new UpdateProductImageDto(), owner))
                .isInstanceOf(com.enunas.backend.exception.ProductNotFoundException.class);
    }

    @Test
    void updateImage_setPrimary_demotesCurrentPrimaryInTargetGroup() {
        when(productRepository.findById(42L)).thenReturn(Optional.of(product));
        when(imageRepository.save(any(ProductImage.class))).thenAnswer(inv -> inv.getArgument(0));
        ProductImage image = ProductImage.builder().id(5L).product(product)
                .storageKey("products/42/images/x.jpg").primary(false).build();
        ProductImage currentPrimary = ProductImage.builder().id(6L).product(product)
                .storageKey("products/42/images/y.jpg").primary(true).build();
        when(imageRepository.findById(5L)).thenReturn(Optional.of(image));
        when(imageRepository.findByProductIdAndProductColorIdAndPrimary(42L, null, true))
                .thenReturn(Optional.of(currentPrimary));

        UpdateProductImageDto dto = new UpdateProductImageDto();
        dto.setPrimary(true);
        mediaService.updateImage(42L, 5L, dto, owner);

        assertThat(currentPrimary.isPrimary()).isFalse();
        assertThat(image.isPrimary()).isTrue();
    }
```

- [ ] **Step 2: Implement `updateImage`**

Add to `MediaService.java` (imports: `com.enunas.backend.media.dto.UpdateProductImageDto`, `java.util.Objects`):

```java
    /**
     * Edit image metadata without re-uploading bytes. Partial update — an omitted field is
     * unchanged. Colour: {@code productColorId} assigns, {@code unassignColor=true} moves to the
     * shared group. Setting a colour that differs from the current one drops the image's primary
     * flag unless {@code primary=true} is also sent, so it can never collide with the target
     * group's existing primary.
     */
    @Transactional
    public ProductImageResponseDto updateImage(Long productId, Long imageId,
                                               UpdateProductImageDto dto, User owner) {
        findProductAndVerifyOwnership(productId, owner);
        ProductImage image = imageRepository.findById(imageId)
                .orElseThrow(() -> new ProductNotFoundException("Image not found with id: " + imageId));
        if (!image.getProduct().getId().equals(productId)) {
            throw new SecurityException("Image does not belong to this product");
        }

        Long currentColourId = image.getProductColor() != null ? image.getProductColor().getId() : null;
        boolean colourChanged = false;
        if (Boolean.TRUE.equals(dto.getUnassignColor())) {
            colourChanged = currentColourId != null;
            image.setProductColor(null);
        } else if (dto.getProductColorId() != null) {
            image.setProductColor(resolveColourForProduct(dto.getProductColorId(), productId));
            colourChanged = !Objects.equals(currentColourId, dto.getProductColorId());
        }

        if (dto.getAltText() != null) image.setAltText(dto.getAltText());
        if (dto.getDisplayOrder() != null) image.setDisplayOrder(dto.getDisplayOrder());

        Long targetColourId = image.getProductColor() != null ? image.getProductColor().getId() : null;
        if (Boolean.TRUE.equals(dto.getPrimary())) {
            demoteCurrentPrimary(productId, targetColourId, imageId);
            image.setPrimary(true);
        } else if (Boolean.FALSE.equals(dto.getPrimary()) || (colourChanged && image.isPrimary())) {
            image.setPrimary(false);
        }

        return ProductImageResponseDto.from(imageRepository.save(image), mediaUrlResolver);
    }

    private void demoteCurrentPrimary(Long productId, Long colourId, Long exceptImageId) {
        imageRepository.findByProductIdAndProductColorIdAndPrimary(productId, colourId, true)
                .filter(existing -> !existing.getId().equals(exceptImageId))
                .ifPresent(existing -> {
                    existing.setPrimary(false);
                    imageRepository.save(existing);
                });
    }
```

- [ ] **Step 3: Add the endpoint**

In `MediaController.java` add `import com.enunas.backend.media.dto.UpdateProductImageDto;` and `import org.springframework.web.bind.annotation.PatchMapping;` (or use `@RequestMapping(method = PATCH)`), then after `addImage`:

```java
    @PatchMapping("/images/{imageId}")
    @PreAuthorize("hasRole('BRAND_PARTNER')")
    public ResponseEntity<ProductImageResponseDto> updateImage(
            @PathVariable Long productId,
            @PathVariable Long imageId,
            @Valid @RequestBody UpdateProductImageDto dto,
            @AuthenticationPrincipal User owner) {
        return ResponseEntity.ok(mediaService.updateImage(productId, imageId, dto, owner));
    }
```

- [ ] **Step 4: Run — expect pass**

Run: `./mvnw test -Dtest=MediaServiceTest`
Expected: PASS.

---

### Task 6: Colour-filtered `getImages` + `GET` query param

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/media/MediaService.java`
- Modify: `backend/src/main/java/com/enunas/backend/media/MediaController.java`
- Test: `backend/src/test/java/com/enunas/backend/media/MediaServiceTest.java`

**Interfaces:**
- Consumes: `ProductImageRepository.findForProductAndOptionalColour` (Task 2).
- Produces:
  - `MediaService.getImages(Long productId, Long colorId)` → `List<ProductImageResponseDto>`, sorted primary-first then `displayOrder` then `id`. `colorId == null` returns every image.
  - `GET /products/{productId}/media/images?colorId={id}` — param optional; behaviour unchanged when omitted.

- [ ] **Step 1: Write the failing test**

Add to `MediaServiceTest.java`:

```java
    @Test
    void getImages_sortsPrimaryFirstThenDisplayOrder() {
        ProductImage a = ProductImage.builder().id(1L).product(product)
                .storageKey("products/42/images/a.jpg").primary(false).displayOrder(2).build();
        ProductImage b = ProductImage.builder().id(2L).product(product)
                .storageKey("products/42/images/b.jpg").primary(true).displayOrder(5).build();
        ProductImage c = ProductImage.builder().id(3L).product(product)
                .storageKey("products/42/images/c.jpg").primary(false).displayOrder(1).build();
        when(imageRepository.findForProductAndOptionalColour(42L, null))
                .thenReturn(java.util.List.of(a, b, c));
        when(mediaUrlResolver.resolve(anyString())).thenAnswer(inv -> "cdn/" + inv.getArgument(0));

        var result = mediaService.getImages(42L, null);

        assertThat(result).extracting(dto -> dto.getImageUrl().substring(dto.getImageUrl().lastIndexOf('/') + 1))
                .containsExactly("b.jpg", "c.jpg", "a.jpg");
    }

    @Test
    void getImages_passesColourIdThrough() {
        when(imageRepository.findForProductAndOptionalColour(42L, 9L)).thenReturn(java.util.List.of());
        mediaService.getImages(42L, 9L);
        verify(imageRepository).findForProductAndOptionalColour(42L, 9L);
    }
```

- [ ] **Step 2: Replace `getImages` in `MediaService`**

```java
    @Transactional(readOnly = true)
    public List<ProductImageResponseDto> getImages(Long productId, Long colorId) {
        return imageRepository.findForProductAndOptionalColour(productId, colorId).stream()
                .sorted(java.util.Comparator
                        .comparing((ProductImage i) -> !i.isPrimary())
                        .thenComparingInt(ProductImage::getDisplayOrder)
                        .thenComparing(ProductImage::getId))
                .map(image -> ProductImageResponseDto.from(image, mediaUrlResolver))
                .toList();
    }
```

Remove the old single-arg `getImages(Long productId)` — update its only caller in Step 3. (Search: `grep -rn "getImages(" backend/src` — the only non-test caller is `MediaController`.)

- [ ] **Step 3: Wire the controller param**

In `MediaController.java`:

```java
    @GetMapping("/images")
    public ResponseEntity<List<ProductImageResponseDto>> getImages(
            @PathVariable Long productId,
            @RequestParam(required = false) Long colorId) {
        return ResponseEntity.ok(mediaService.getImages(productId, colorId));
    }
```

`@RequestParam` is already imported via `org.springframework.web.bind.annotation.*`.

- [ ] **Step 4: Run — expect pass**

Run: `./mvnw test -Dtest=MediaServiceTest`
Expected: PASS.

---

### Task 7: `ProductColorDto` + expose colours on product / variant / listing DTOs

**Files:**
- Create: `backend/src/main/java/com/enunas/backend/product/dto/ProductColorDto.java`
- Modify: `backend/src/main/java/com/enunas/backend/product/dto/ProductResponseDto.java`
- Modify: `backend/src/main/java/com/enunas/backend/product/dto/ProductVariantResponseDto.java`
- Modify: `backend/src/main/java/com/enunas/backend/product/productlisting/dto/ProductVariantResponseDto.java`
- Modify: `backend/src/main/java/com/enunas/backend/product/productlisting/dto/ListingResponseDto.java`
- Test: `backend/src/test/java/com/enunas/backend/product/dto/ProductColorDtoTest.java` (create), plus a case in an existing `ProductResponseDto` test if one exists (`grep -rln "ProductResponseDto" backend/src/test`).

**Interfaces:**
- Produces:
  - `ProductColorDto` with `Long id`, `String color`, `ColorFamily colorFamily`, `String sku`; static `from(ProductColor)`.
  - `ProductResponseDto.getColors()` → `List<ProductColorDto>`.
  - `ProductVariantResponseDto.getColorId()` → `Long` (both DTOs of that name).
  - `ListingResponseDto.getColorId()` → `Long`.

- [ ] **Step 1: Create `ProductColorDto`**

```java
package com.enunas.backend.product.dto;

import com.enunas.backend.product.productvariant.ColorFamily;
import com.enunas.backend.product.productvariant.ProductColor;
import lombok.Builder;
import lombok.Getter;

/** A colourway of a product: the swatch source for the PDP and the image-colour picker. */
@Getter
@Builder
public class ProductColorDto {

    private Long id;
    private String color;
    private ColorFamily colorFamily;
    private String sku;

    public static ProductColorDto from(ProductColor colour) {
        return ProductColorDto.builder()
                .id(colour.getId())
                .color(colour.getColor())
                .colorFamily(colour.getColorFamily())
                .sku(colour.getSku())
                .build();
    }
}
```

- [ ] **Step 2: Write the failing test**

`backend/src/test/java/com/enunas/backend/product/dto/ProductColorDtoTest.java`:

```java
package com.enunas.backend.product.dto;

import com.enunas.backend.product.productvariant.ColorFamily;
import com.enunas.backend.product.productvariant.ProductColor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductColorDtoTest {

    @Test
    void from_mapsAllFields() {
        ProductColor colour = ProductColor.builder()
                .id(3L).sku("ABC123").color("Black").colorFamily(ColorFamily.BLACK).build();

        ProductColorDto dto = ProductColorDto.from(colour);

        assertThat(dto.getId()).isEqualTo(3L);
        assertThat(dto.getSku()).isEqualTo("ABC123");
        assertThat(dto.getColor()).isEqualTo("Black");
        assertThat(dto.getColorFamily()).isEqualTo(ColorFamily.BLACK);
    }
}
```

- [ ] **Step 3: Add `colors` to `ProductResponseDto`**

Add the field:

```java
    private List<ProductColorDto> colors;
```

In the main `from(Product, DisplayPrice, Function<Long, DisplayPrice>, MediaUrlResolver)` builder chain, add:

```java
                .colors(product.getColors().stream().map(ProductColorDto::from).toList())
```

- [ ] **Step 4: Add `colorId` to both `ProductVariantResponseDto` classes**

`product/dto/ProductVariantResponseDto.java` — add field `private Long colorId;` and in `from`:

```java
                .colorId(variant.getProductColor() != null ? variant.getProductColor().getId() : null)
```

`product/productlisting/dto/ProductVariantResponseDto.java` — same field and same line in its `from`.

`ProductVariant.getProductColor()` already exists (it's a real `@ManyToOne` on the entity).

- [ ] **Step 5: Add `colorId` to `ListingResponseDto`**

Add `private Long colorId;` and in `from`:

```java
                .colorId(productListing.getVariant().getProductColor() != null
                        ? productListing.getVariant().getProductColor().getId() : null)
```

- [ ] **Step 6: Run — expect pass**

Run: `./mvnw test -Dtest=ProductColorDtoTest`
Then the broader product/listing suites to catch DTO consumers:
Run: `./mvnw test -Dtest=*ProductResponseDto*,*ListingResponseDto*,*ProductVariant*`
Expected: PASS. If a JSON-serialization or builder test asserts an exact field set, update it to include the new fields.

---

### Task 8: End-to-end integration test

**Files:**
- Modify: `backend/src/test/java/com/enunas/backend/media/MediaEndToEndIntegrationTest.java`

**Interfaces:**
- Consumes: everything above, over HTTP.

- [ ] **Step 1: Add the E2E test**

Add to `MediaEndToEndIntegrationTest.java`. It seeds a product with two colourways directly through repositories, then drives presign → PUT → confirm (with `productColorId`) twice and asserts the colour filter. Add `@Autowired private com.enunas.backend.product.productvariant.ProductColorRepository colourRepository;`.

```java
    @Test
    void productImages_taggedByColour_filterReturnsColourPlusShared() throws Exception {
        User brandUser = userRepository.save(User.builder()
                .email("e2e-colour@it.local").password(passwordEncoder.encode("Brand123!"))
                .role(Role.BRAND_PARTNER).enabled(true).adminApproved(true).build());
        BrandPartner brand = BrandPartner.builder()
                .user(brandUser).brandName("E2E Colour Brand").slug("e2e-colour-brand").build();
        brand.setStatus(BrandStatus.ACTIVE);
        brandPartnerRepository.save(brand);
        Product product = productRepository.save(Product.builder()
                .name("E2E Jacket").slug("e2e-jacket").brand(brand).creator(brandUser).build());
        var black = colourRepository.save(com.enunas.backend.product.productvariant.ProductColor.builder()
                .sku("E2EBLK").color("Black")
                .colorFamily(com.enunas.backend.product.productvariant.ColorFamily.BLACK)
                .product(product).build());
        var white = colourRepository.save(com.enunas.backend.product.productvariant.ProductColor.builder()
                .sku("E2EWHT").color("White")
                .colorFamily(com.enunas.backend.product.productvariant.ColorFamily.WHITE)
                .product(product).build());

        String token = login("e2e-colour@it.local", "Brand123!");

        String blackKey = uploadAndConfirmImage(product.getId(), token, black.getId());
        String whiteKey = uploadAndConfirmImage(product.getId(), token, white.getId());
        String sharedKey = uploadAndConfirmImage(product.getId(), token, null);

        ResponseEntity<List> all = rest.exchange(
                "/products/" + product.getId() + "/media/images", HttpMethod.GET, null, List.class);
        assertThat(all.getBody()).hasSize(3);

        ResponseEntity<List> forBlack = rest.exchange(
                "/products/" + product.getId() + "/media/images?colorId=" + black.getId(),
                HttpMethod.GET, null, List.class);
        assertThat(forBlack.getBody()).hasSize(2); // black + shared, not white
    }

    private String uploadAndConfirmImage(Long productId, String token, Long colourId) throws Exception {
        byte[] bytes = ("img-" + colourId).getBytes(StandardCharsets.UTF_8);
        @SuppressWarnings("unchecked")
        ResponseEntity<Map> presign = rest.exchange(
                "/products/" + productId + "/media/upload-url", HttpMethod.POST,
                new HttpEntity<>(Map.of("purpose", "PRODUCT_IMAGE", "contentType", "image/jpeg",
                        "contentLength", bytes.length), auth(token)), Map.class);
        String key = (String) presign.getBody().get("key");
        putBytes((String) presign.getBody().get("uploadUrl"), "image/jpeg", bytes);

        java.util.HashMap<String, Object> body = new java.util.HashMap<>();
        body.put("storageKey", key);
        body.put("displayOrder", 0);
        if (colourId != null) body.put("productColorId", colourId);
        @SuppressWarnings("unchecked")
        ResponseEntity<Map> confirm = rest.exchange(
                "/products/" + productId + "/media/images", HttpMethod.POST,
                new HttpEntity<>(body, auth(token)), Map.class);
        assertThat(confirm.getStatusCode().is2xxSuccessful()).as("confirm: %s", confirm.getBody()).isTrue();
        return key;
    }
```

- [ ] **Step 2: Run — expect pass**

Run: `./mvnw test -Dtest=MediaEndToEndIntegrationTest`
Expected: PASS (all existing + the new one).

---

### Task 9: Frontend integration doc

**Files:**
- Modify: `backend/../docs/frontend-media-integration.md` (repo path `docs/frontend-media-integration.md`)

- [ ] **Step 1: Append a new section**

Add this section after "## Product images (brand-partner dashboard)":

```markdown
## Colourway-specific product images

An image can now be tagged to one colourway, or left **shared** (shown for every colourway). The
PDP gallery for a selected swatch = *its tagged images* + *all shared images*.

### New / changed fields

- `POST /products/{productId}/media/images` — body accepts optional `productColorId` (a
  `ProductColor` id from `GET /products/{id}` → `colors[]`). Omit it for a shared image.
- `PATCH /products/{productId}/media/images/{imageId}` — **new.** Partial update of image
  metadata. Body: `{ productColorId?, unassignColor?, primary?, altText?, displayOrder? }`.
  - `productColorId: <id>` reassigns the image to that colourway.
  - `unassignColor: true` moves it back to the shared group.
  - `primary: true` makes it the cover for its (post-update) colour group, demoting the previous
    one in that group only.
  - Omitted fields are unchanged. Sending a `productColorId` from another product → `403`.
  - Changing the colour drops `primary` unless `primary: true` is also sent.
- `GET /products/{productId}/media/images?colorId={id}` — optional filter. Returns the
  colourway's own images **plus** shared images, primary-first. No param → all images (use this
  in the vendor dashboard).
- `GET /products/{productId}/media/images` responses now include `productColorId` (nullable) and
  `color` (nullable) on each image.
- `GET /products/{id}` (`ProductResponseDto`) gains `colors: [{ id, color, colorFamily, sku }]`.
  Each entry in `images[]` carries `productColorId` + `primary`. `variants[]` and listing
  responses gain `colorId`.

### Vendor dashboard UX (specified — do not redesign)

1. **Grouped upload.** The "Produktbilder" section renders as colour groups: `Alle Farben
   (geteilt)` plus one group per entry in `colors[]`. An upload targets the active group — confirm
   with that group's `productColorId` (or none for `Alle Farben`). Moving an image between groups
   (drag / dropdown) → `PATCH` with `productColorId: <id>` or `unassignColor: true`. Each group has
   its own "Als Titelbild setzen" → `PATCH { primary: true }`.
2. **Existing products.** Untagged images appear under `Alle Farben (geteilt)` with the hint
   *"Diese Bilder werden für alle Farbvarianten angezeigt."* No migration — splitting them is
   drag-into-a-colour-group. When a product has ≥2 colourways and ≥1 shared image, show a
   one-line, non-blocking nudge to assign colour-specific photos.
3. **Shared is acceptable.** A colourway with no own images is valid and shippable — it shows the
   shared set. You MAY show an advisory hint; you MUST NOT block listing activation or checkout on
   it.

### PDP

Filter client-side: show an image when `img.productColorId === selectedColorId || img.productColorId == null`.
The listing / search card picks the primary whose `productColorId` matches the card's colour
(fall back to the shared primary, then the first image).
```

- [ ] **Step 2: Verify the doc**

Re-read the section; confirm every endpoint and field name matches what Tasks 3–7 actually produced (`productColorId`, `unassignColor`, `colorId`, `colors`, `color`).

---

## Final verification

- [ ] Run the full media + product test surface:

Run: `./mvnw test -Dtest=ProductMediaStorageKeyTest,MediaServiceTest,ProductMediaDtoTest,MediaEndToEndIntegrationTest,ProductColorDtoTest`
Expected: all PASS.

- [ ] Run the full suite once:

Run: `./mvnw test`
Expected: green. If OneDrive lock noise causes `NoClassDefFoundError` en masse, retry; that's environment, not a regression (`docs/superpowers` / project memory).

- [ ] Confirm **nothing was committed** — `git status` should show the new/modified files unstaged. Hand back to the requester for commit.

## Self-Review (completed by plan author)

- **Spec coverage:** §1 schema → Task 1. §2 entities → Task 1. §3 write path → Task 4. §4 PATCH → Task 5. §5 read path → Tasks 2, 6. §6 PDP payload (`colors`, image `productColorId`, variant/listing `colorId`) → Tasks 3, 7. §7 frontend contract → Task 9. §8 (no rule) → nothing to build, correct. §9 tests → Tasks 1–8. Acceptance criteria → Final verification.
- **Placeholder scan:** none — every step has concrete code or an exact command.
- **Type consistency:** `resolveColourForProduct(Long, Long)` defined in Task 4, reused in Task 5. `findForProductAndOptionalColour` / `findByProductIdAndProductColorIdAndPrimary` defined in Task 2, consumed in Tasks 4–6. `ProductColorDto.from` defined in Task 7 Step 1, consumed Step 3. `UpdateProductImageDto` fields defined in Task 3, consumed in Task 5. American `color` spelling kept for all entity/DB/DTO identifiers that touch existing code; `colour` only in the migration filename, index names, and new private helper/local names.
- **Known risk flagged in-plan:** Spring Data null-parameter derived query (Task 2 Step 4 carries the `@Query` fallback).
