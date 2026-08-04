# Backend Input Validation & Sanitization Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the input-validation gaps across every request DTO in Products, Listings, Orders, Brand applications, Returns, Addresses, and Coupons so the backend never trusts frontend-only validation, rejects oversized payloads, and rejects embedded HTML/script markup in free-text fields.

**Architecture:** The backend already validates every listed domain's `@RequestBody` with `@Valid` and already has a `GlobalExceptionHandler` that turns `MethodArgumentNotValidException` into a clean 400 response, and one working custom-constraint precedent (`com.enunas.backend.product.validation.ValidCatalogueCategory`). This plan does not introduce a new validation pipeline — it (1) adds one new reusable constraint, `@NoHtml`, in a new shared `com.enunas.backend.validation` package, following the exact annotation+validator pattern the codebase already uses, and (2) applies `@Size` bounds and `@NoHtml` (plus a handful of `@Pattern`/`@URL` constraints on structurally well-known fields like currency codes, discount codes, and URLs) to every DTO field currently missing them, one domain per task.

**Tech Stack:** Java 21, Spring Boot 4.0.5, Jakarta Bean Validation via `spring-boot-starter-validation` (Hibernate Validator — already a dependency, confirmed in `backend/pom.xml`), Lombok, JUnit 5 + AssertJ (already used in `CreateProductDtoValidationTest`).

## Global Constraints

- No new Maven dependencies. `spring-boot-starter-validation` (Hibernate Validator) is already on the classpath — `@URL` (`org.hibernate.validator.constraints.URL`) is available from it.
- Rich text does not exist anywhere in this backend today (every "description"-style field is stored and presumably rendered as plain text). Per the spec's own framing — "sanitize dangerous HTML **if you ever allow rich text**" — the correct, proportionate move today is to **reject** input containing `<`/`>` outright via the new `@NoHtml` constraint, not to build a Jsoup/OWASP allow-list sanitizer for a feature that doesn't exist. Do not add a sanitizer library.
- Mirror the existing custom-validator convention exactly: annotation interface + separate `ConstraintValidator` class, both under a dedicated `validation` package (see `com.enunas.backend.product.validation.ValidCatalogueCategory` / `CatalogueCategoryValidator` for the pattern).
- Do not touch JPA `@Embeddable` entity classes (e.g. `com.enunas.backend.order.ShippingAddress`). The DTO at the controller boundary (`ShippingAddressDto`) is the actual input surface being hardened — the entity is internal state, not a request body.
- Do not change existing business semantics: discount-code normalization (trim+uppercase in `DiscountService`), the `domestic` VAT flag, or the explicit "vatId/taxNumber format is never validated here — manual check" rule documented in `RegisterBrandPartnerDto`/`AdminBrandMasterDataDto`. Only add length and markup guards to those two fields, never a format `@Pattern`.
- Reviews are explicitly a **future** domain — no DTO exists yet in the codebase. Out of scope for this plan; do not create placeholder DTOs for it.
- All new/changed constraints must keep every currently-passing test green, in particular `backend/src/test/java/com/enunas/backend/product/CreateProductDtoValidationTest.java` and `backend/src/test/java/com/enunas/backend/brandpartner/BrandPartnerVatIdToggleTest.java`.
- Test style: instantiate the real Jakarta `Validator` directly (`Validation.buildDefaultValidatorFactory()`), exactly as `CreateProductDtoValidationTest` already does — no Spring context, no MockMvc, for DTO-level tests. For the standalone `NoHtmlValidator`, unit-test the validator class directly (no Validator factory needed).

## File Structure

| File | Responsibility |
|---|---|
| `backend/src/main/java/com/enunas/backend/validation/NoHtml.java` (new) | Field/parameter-level constraint annotation — rejects any `CharSequence` containing `<` or `>`. |
| `backend/src/main/java/com/enunas/backend/validation/NoHtmlValidator.java` (new) | `ConstraintValidator` implementation backing `@NoHtml`. |
| `product/dto/CreateProductDto.java`, `UpdateProductDto.java`, `ProductVariantDto.java`, `UpdateProductVariantDto.java`, `admin/dto/RejectionDto.java` | Products domain — add `@Size` + `@NoHtml` to free-text fields. |
| `product/productlisting/dto/CreateListingDto.java`, `UpdateListingDto.java` | Listings domain — bound `region`, tighten `currency` to a real ISO-4217-shaped pattern. |
| `order/dto/CreateOrderDto.java`, `CancelOrderDto.java`, `ShipmentConfirmationDto.java` | Orders domain — bound `notes`/`discountCode`/`carrier`/`trackingNumber`/`note`. |
| `order/dto/ReturnRequestDto.java`, `ShippingProblemDto.java`, `UploadReturnLabelDto.java` | Returns domain — bound `description`, validate `labelUrl` as a real URL. |
| `order/dto/ShippingAddressDto.java` | Addresses domain — bound every free-text field, loosely validate `phone`. |
| `brandpartner/dto/RegisterBrandPartnerDto.java`, `UpdateBrandPartnerDto.java`, `AdminBrandMasterDataDto.java` | Brand applications domain — bound every currently-unbounded field, validate `logoUrl`/`websiteUrl` as URLs, validate handle charset. |
| `discount/dto/CreateDiscountDto.java` | Coupons domain — bound and charset-restrict `code`. |

---

### Task 1: Shared `@NoHtml` constraint

**Files:**
- Create: `backend/src/main/java/com/enunas/backend/validation/NoHtml.java`
- Create: `backend/src/main/java/com/enunas/backend/validation/NoHtmlValidator.java`
- Test: `backend/src/test/java/com/enunas/backend/validation/NoHtmlValidatorTest.java`

**Interfaces:**
- Produces: `@NoHtml` — a `jakarta.validation` constraint annotation usable on any `String`/`CharSequence` field, parameter, or record component. Default message: `"must not contain HTML markup"`. Null/empty values are always valid (presence is `@NotBlank`/`@NotNull`'s job, not this constraint's).
- Consumes: nothing from prior tasks (this is the foundational task every later task depends on).

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/enunas/backend/validation/NoHtmlValidatorTest.java`:

```java
package com.enunas.backend.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NoHtmlValidatorTest {

    private final NoHtmlValidator validator = new NoHtmlValidator();

    @Test
    void nullValue_isValid() {
        assertThat(validator.isValid(null, null)).isTrue();
    }

    @Test
    void emptyValue_isValid() {
        assertThat(validator.isValid("", null)).isTrue();
    }

    @Test
    void plainText_isValid() {
        assertThat(validator.isValid("Black Hoodie, size M", null)).isTrue();
    }

    @Test
    void scriptTag_isInvalid() {
        assertThat(validator.isValid("<script>alert(1)</script>", null)).isFalse();
    }

    @Test
    void loneAngleBracket_isInvalid() {
        assertThat(validator.isValid("5 > 3 and 2 < 4", null)).isFalse();
    }

    @Test
    void imgOnErrorPayload_isInvalid() {
        assertThat(validator.isValid("<img src=x onerror=alert(1)>", null)).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=NoHtmlValidatorTest -pl backend` (or from inside `backend/`: `./mvnw test -Dtest=NoHtmlValidatorTest`)

Expected: **compile failure** — `NoHtmlValidator` does not exist yet.

- [ ] **Step 3: Write the constraint annotation and validator**

Create `backend/src/main/java/com/enunas/backend/validation/NoHtml.java`:

```java
package com.enunas.backend.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Rejects any value containing {@code <} or {@code >}. There is no rich-text field anywhere in
 * this backend today, so free-text input is never expected to carry markup — this constraint
 * blocks stored-XSS payloads at the API boundary instead of sanitizing/stripping them. Null and
 * empty values are always valid; combine with {@code @NotBlank}/{@code @NotNull} for presence.
 */
@Documented
@Constraint(validatedBy = NoHtmlValidator.class)
@Target({ElementType.METHOD, ElementType.FIELD, ElementType.ANNOTATION_TYPE,
        ElementType.CONSTRUCTOR, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface NoHtml {

    String message() default "must not contain HTML markup";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
```

Create `backend/src/main/java/com/enunas/backend/validation/NoHtmlValidator.java`:

```java
package com.enunas.backend.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class NoHtmlValidator implements ConstraintValidator<NoHtml, CharSequence> {

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        if (value == null || value.isEmpty()) {
            return true;
        }
        String text = value.toString();
        return text.indexOf('<') < 0 && text.indexOf('>') < 0;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=NoHtmlValidatorTest`

Expected: PASS (6/6 tests green)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/validation/NoHtml.java backend/src/main/java/com/enunas/backend/validation/NoHtmlValidator.java backend/src/test/java/com/enunas/backend/validation/NoHtmlValidatorTest.java
git commit -m "feat: add reusable @NoHtml bean-validation constraint"
```

---

### Task 2: Products domain

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/product/dto/CreateProductDto.java`
- Modify: `backend/src/main/java/com/enunas/backend/product/dto/UpdateProductDto.java`
- Modify: `backend/src/main/java/com/enunas/backend/product/dto/ProductVariantDto.java`
- Modify: `backend/src/main/java/com/enunas/backend/product/dto/UpdateProductVariantDto.java`
- Modify: `backend/src/main/java/com/enunas/backend/admin/dto/RejectionDto.java`
- Test: `backend/src/test/java/com/enunas/backend/product/CreateProductDtoValidationTest.java` (extend existing)
- Test: `backend/src/test/java/com/enunas/backend/product/UpdateProductDtoValidationTest.java` (new)
- Test: `backend/src/test/java/com/enunas/backend/admin/RejectionDtoValidationTest.java` (new)

**Interfaces:**
- Consumes: `com.enunas.backend.validation.NoHtml` from Task 1.
- Produces: nothing consumed by later tasks — Products is independent of Listings/Orders/etc.

- [ ] **Step 1: Write the failing tests**

Append these methods to `backend/src/test/java/com/enunas/backend/product/CreateProductDtoValidationTest.java`, inside the class body (before the final `// ===== Helper =====` section):

```java
    // ===== @Size / @NoHtml on free-text fields =====

    @Test
    void name_exceedsMaxLength_isInvalid() {
        CreateProductDto dto = validClothingDto();
        dto.setName("A".repeat(256));

        Set<ConstraintViolation<CreateProductDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "name".equals(v.getPropertyPath().toString()));
    }

    @Test
    void description_containingHtml_isInvalid() {
        CreateProductDto dto = validClothingDto();
        dto.setDescription("<script>alert(1)</script>");

        Set<ConstraintViolation<CreateProductDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "description".equals(v.getPropertyPath().toString()));
    }

    @Test
    void careInstructions_exceedsMaxLength_isInvalid() {
        CreateProductDto dto = validClothingDto();
        dto.setCareInstructions("A".repeat(2001));

        Set<ConstraintViolation<CreateProductDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "careInstructions".equals(v.getPropertyPath().toString()));
    }

    @Test
    void variantColor_containingHtml_isInvalid() {
        CreateProductDto dto = validClothingDto();
        dto.getVariants().get(0).setColor("<img src=x onerror=alert(1)>");

        Set<ConstraintViolation<CreateProductDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().contains("color"));
    }
```

Create `backend/src/test/java/com/enunas/backend/product/UpdateProductDtoValidationTest.java`:

```java
package com.enunas.backend.product;

import com.enunas.backend.product.dto.UpdateProductDto;
import com.enunas.backend.product.dto.UpdateProductVariantDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateProductDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void allFieldsNull_isValid() {
        UpdateProductDto dto = new UpdateProductDto();

        Set<ConstraintViolation<UpdateProductDto>> violations = validator.validate(dto);

        assertThat(violations).isEmpty();
    }

    @Test
    void name_containingHtml_isInvalid() {
        UpdateProductDto dto = new UpdateProductDto();
        dto.setName("<b>Hoodie</b>");

        Set<ConstraintViolation<UpdateProductDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "name".equals(v.getPropertyPath().toString()));
    }

    @Test
    void collectionName_exceedsMaxLength_isInvalid() {
        UpdateProductDto dto = new UpdateProductDto();
        dto.setCollectionName("A".repeat(256));

        Set<ConstraintViolation<UpdateProductDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "collectionName".equals(v.getPropertyPath().toString()));
    }

    @Test
    void variantColor_containingHtml_isInvalid() {
        UpdateProductVariantDto dto = new UpdateProductVariantDto();
        dto.setColor("<script>x</script>");

        Set<ConstraintViolation<UpdateProductVariantDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "color".equals(v.getPropertyPath().toString()));
    }
}
```

Create `backend/src/test/java/com/enunas/backend/admin/RejectionDtoValidationTest.java`:

```java
package com.enunas.backend.admin;

import com.enunas.backend.admin.dto.RejectionDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RejectionDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void reason_containingHtml_isInvalid() {
        RejectionDto dto = new RejectionDto();
        dto.setReason("<script>alert(1)</script>");

        Set<ConstraintViolation<RejectionDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "reason".equals(v.getPropertyPath().toString()));
    }

    @Test
    void plainTextReason_isValid() {
        RejectionDto dto = new RejectionDto();
        dto.setReason("Photos do not match the listed material.");

        Set<ConstraintViolation<RejectionDto>> violations = validator.validate(dto);

        assertThat(violations).isEmpty();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -Dtest=CreateProductDtoValidationTest,UpdateProductDtoValidationTest,RejectionDtoValidationTest`

Expected: the four new `CreateProductDtoValidationTest` methods FAIL (no violation is produced because the DTOs don't have the constraints yet); `UpdateProductDtoValidationTest` and `RejectionDtoValidationTest` fail to compile only if a referenced setter doesn't exist yet — here every setter already exists (Lombok `@Data`/plain setters), so these fail as assertion failures, not compile errors.

- [ ] **Step 3: Add the constraints**

Replace `backend/src/main/java/com/enunas/backend/product/dto/CreateProductDto.java` with:

```java
package com.enunas.backend.product.dto;

import com.enunas.backend.product.Gender;
import com.enunas.backend.product.ProductCatalogueCategory;
import com.enunas.backend.product.ProductCategory;
import com.enunas.backend.product.ProductType;
import com.enunas.backend.product.validation.CatalogueCategoryAware;
import com.enunas.backend.product.validation.ValidCatalogueCategory;
import com.enunas.backend.validation.NoHtml;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

@Data
@ValidCatalogueCategory
public class CreateProductDto implements CatalogueCategoryAware {

    @NotBlank
    @Size(max = 255)
    @NoHtml
    private String name;

    @Size(max = 5000)
    @NoHtml
    private String description;

    @Size(max = 5000)
    @NoHtml
    private String inspirationStory;

    @NotNull
    private ProductCategory category;

    /**
     * Required when category == CLOTHING (1–3 values). Optional otherwise.
     * Validated by {@link com.enunas.backend.product.validation.CatalogueCategoryValidator}.
     */
    private List<ProductCatalogueCategory> catalogueCategory;

    @NotNull
    private ProductType productType;

    @NotNull
    private Gender gender;

    @Size(max = 255)
    @NoHtml
    private String material;

    @Size(max = 100)
    @NoHtml
    private String originCountry;

    @Size(max = 2000)
    @NoHtml
    private String careInstructions;

    @Size(max = 255)
    @NoHtml
    private String collectionName;

    private LocalDate releaseDate;

    @Min(0)
    private int returnPeriodDays = 14;

    @NotEmpty
    @Valid
    private List<ProductVariantDto> variants;

    /** If true, completeTheLookProductIds must contain 1–4 distinct product IDs. */
    private Boolean completeTheLookEnabled = false;

    /** IDs of related products shown in "Complete The Look". Validated in service. */
    private Set<Long> completeTheLookProductIds;
}
```

Replace `backend/src/main/java/com/enunas/backend/product/dto/UpdateProductDto.java` with:

```java
package com.enunas.backend.product.dto;

import com.enunas.backend.product.Gender;
import com.enunas.backend.product.ProductCatalogueCategory;
import com.enunas.backend.product.ProductCategory;
import com.enunas.backend.product.ProductType;
import com.enunas.backend.validation.NoHtml;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * All fields are optional — null means "keep current value".
 * CatalogueCategory rules (CLOTHING requires 1–3 values) are enforced in the service layer
 * because validation depends on the current persisted category when only one field is patched.
 */
@Data
public class UpdateProductDto {

    @Size(max = 255)
    @NoHtml
    private String name;

    @Size(max = 5000)
    @NoHtml
    private String description;

    @Size(max = 5000)
    @NoHtml
    private String inspirationStory;

    private ProductCategory category;

    private List<ProductCatalogueCategory> catalogueCategory;

    private ProductType productType;

    private Gender gender;

    @Size(max = 255)
    @NoHtml
    private String material;

    @Size(max = 100)
    @NoHtml
    private String originCountry;

    @Size(max = 2000)
    @NoHtml
    private String careInstructions;

    @Size(max = 255)
    @NoHtml
    private String collectionName;

    private LocalDate releaseDate;

    @Min(0)
    private Integer returnPeriodDays;

    private Boolean completeTheLookEnabled;

    private Set<Long> completeTheLookProductIds;
}
```

Replace `backend/src/main/java/com/enunas/backend/product/dto/ProductVariantDto.java` with:

```java
package com.enunas.backend.product.dto;

import com.enunas.backend.product.productvariant.ColorFamily;
import com.enunas.backend.validation.NoHtml;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class ProductVariantDto {

    @NotBlank
    @Size(max = 50)
    @NoHtml
    private String color;

    @NotNull
    private ColorFamily colorFamily;

    @NotBlank
    @Size(max = 50)
    @NoHtml
    private String size;

    @Min(0)
    private int stockQuantity;

    @Min(0)
    private Integer weightGrams;
}
```

Replace `backend/src/main/java/com/enunas/backend/product/dto/UpdateProductVariantDto.java` with:

```java
package com.enunas.backend.product.dto;

import com.enunas.backend.product.productvariant.ColorFamily;
import com.enunas.backend.validation.NoHtml;
import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class UpdateProductVariantDto {

    @Size(max = 50)
    @NoHtml
    private String color;

    private ColorFamily colorFamily;

    @Size(max = 50)
    @NoHtml
    private String size;

    @Min(0)
    private Integer stockQuantity;

    @Min(0)
    private Integer weightGrams;
}
```

Replace `backend/src/main/java/com/enunas/backend/admin/dto/RejectionDto.java` with:

```java
package com.enunas.backend.admin.dto;

import com.enunas.backend.validation.NoHtml;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Reason supplied by an admin when rejecting a brand application or product. */
@Data
public class RejectionDto {

    @Size(max = 1000)
    @NoHtml
    private String reason;
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw test -Dtest=CreateProductDtoValidationTest,UpdateProductDtoValidationTest,RejectionDtoValidationTest`

Expected: PASS, all methods green (including the pre-existing `CreateProductDtoValidationTest` methods — confirm none regressed).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/product/dto/CreateProductDto.java backend/src/main/java/com/enunas/backend/product/dto/UpdateProductDto.java backend/src/main/java/com/enunas/backend/product/dto/ProductVariantDto.java backend/src/main/java/com/enunas/backend/product/dto/UpdateProductVariantDto.java backend/src/main/java/com/enunas/backend/admin/dto/RejectionDto.java backend/src/test/java/com/enunas/backend/product/CreateProductDtoValidationTest.java backend/src/test/java/com/enunas/backend/product/UpdateProductDtoValidationTest.java backend/src/test/java/com/enunas/backend/admin/RejectionDtoValidationTest.java
git commit -m "feat: bound and HTML-guard Products domain DTOs"
```

---

### Task 3: Listings domain

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/product/productlisting/dto/CreateListingDto.java`
- Modify: `backend/src/main/java/com/enunas/backend/product/productlisting/dto/UpdateListingDto.java`
- Test: `backend/src/test/java/com/enunas/backend/product/productlisting/ListingDtoValidationTest.java` (new)

**Interfaces:**
- Consumes: `com.enunas.backend.validation.NoHtml` from Task 1.
- Produces: nothing consumed by later tasks.

Note: `product/productlisting/dto/CreateProductVariantDto.java` and `UpdateProductVariantDto.java` in this same package are never referenced by any controller or service (`ProductVariantController` uses `product.dto.ProductVariantDto`/`UpdateProductVariantDto` instead, already hardened in Task 2) — confirmed via `grep -r CreateProductVariantDto backend/src/main/java/com/enunas/backend/product/productlisting`, which returns only the file's own declaration. Leave them untouched; they are dead code and validating them would be validating nothing.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/enunas/backend/product/productlisting/ListingDtoValidationTest.java`:

```java
package com.enunas.backend.product.productlisting;

import com.enunas.backend.product.productlisting.dto.CreateListingDto;
import com.enunas.backend.product.productlisting.dto.UpdateListingDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ListingDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void validCreateListingDto_hasNoViolations() {
        CreateListingDto dto = validDto();

        Set<ConstraintViolation<CreateListingDto>> violations = validator.validate(dto);

        assertThat(violations).isEmpty();
    }

    @Test
    void lowercaseCurrency_isInvalid() {
        CreateListingDto dto = validDto();
        dto.setCurrency("eur");

        Set<ConstraintViolation<CreateListingDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "currency".equals(v.getPropertyPath().toString()));
    }

    @Test
    void region_containingHtml_isInvalid() {
        CreateListingDto dto = validDto();
        dto.setRegion("<script>alert(1)</script>");

        Set<ConstraintViolation<CreateListingDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "region".equals(v.getPropertyPath().toString()));
    }

    @Test
    void updateListingDto_region_exceedsMaxLength_isInvalid() {
        UpdateListingDto dto = new UpdateListingDto();
        dto.setRegion("A".repeat(101));

        Set<ConstraintViolation<UpdateListingDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "region".equals(v.getPropertyPath().toString()));
    }

    private CreateListingDto validDto() {
        CreateListingDto dto = new CreateListingDto();
        dto.setVariantId(1L);
        dto.setPriceInputMode(com.enunas.backend.product.productlisting.PriceInputMode.NET);
        dto.setPrice(new BigDecimal("19.99"));
        dto.setCurrency("EUR");
        return dto;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=ListingDtoValidationTest`

Expected: `lowercaseCurrency_isInvalid` and `region_containingHtml_isInvalid` and `updateListingDto_region_exceedsMaxLength_isInvalid` FAIL (constraints don't exist yet); `validCreateListingDto_hasNoViolations` PASSES already.

- [ ] **Step 3: Add the constraints**

Replace `backend/src/main/java/com/enunas/backend/product/productlisting/dto/CreateListingDto.java` with:

```java
package com.enunas.backend.product.productlisting.dto;

import com.enunas.backend.product.productlisting.PriceInputMode;
import com.enunas.backend.validation.NoHtml;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class CreateListingDto {

    @NotNull
    private Long variantId;

    /** Whether {@code price}/{@code discountPrice} are entered as NET or GROSS figures. */
    @NotNull
    private PriceInputMode priceInputMode;

    @NotNull
    @DecimalMin(value = "0.01")
    private BigDecimal price;

    @DecimalMin(value = "0.0")
    private BigDecimal discountPrice;

    @NotBlank
    @Size(min = 3, max = 3)
    @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter uppercase ISO 4217 currency code")
    private String currency = "EUR";

    @Size(max = 100)
    @NoHtml
    private String region;

    private LocalDateTime dropDate;

    private LocalDateTime availableFrom;

    private LocalDateTime availableUntil;
}
```

Replace `backend/src/main/java/com/enunas/backend/product/productlisting/dto/UpdateListingDto.java` with:

```java
package com.enunas.backend.product.productlisting.dto;

import com.enunas.backend.product.productlisting.PriceInputMode;
import com.enunas.backend.validation.NoHtml;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class UpdateListingDto {

    /** When set, reinterprets the price figures under the new mode (recompute is automatic). */
    private PriceInputMode priceInputMode;

    @DecimalMin(value = "0.01")
    private BigDecimal price;

    @DecimalMin(value = "0.0")
    private BigDecimal discountPrice;

    private Boolean active;

    @Size(max = 100)
    @NoHtml
    private String region;

    private LocalDateTime dropDate;

    private LocalDateTime availableFrom;

    private LocalDateTime availableUntil;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=ListingDtoValidationTest`

Expected: PASS (4/4 tests green)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/product/productlisting/dto/CreateListingDto.java backend/src/main/java/com/enunas/backend/product/productlisting/dto/UpdateListingDto.java backend/src/test/java/com/enunas/backend/product/productlisting/ListingDtoValidationTest.java
git commit -m "feat: bound and HTML-guard Listings domain DTOs"
```

---

### Task 4: Orders domain

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/order/dto/CreateOrderDto.java`
- Modify: `backend/src/main/java/com/enunas/backend/order/dto/CancelOrderDto.java`
- Modify: `backend/src/main/java/com/enunas/backend/order/dto/ShipmentConfirmationDto.java`
- Test: `backend/src/test/java/com/enunas/backend/order/OrderDtoValidationTest.java` (new)

**Interfaces:**
- Consumes: `com.enunas.backend.validation.NoHtml` from Task 1.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/enunas/backend/order/OrderDtoValidationTest.java`:

```java
package com.enunas.backend.order;

import com.enunas.backend.order.dto.CancelOrderDto;
import com.enunas.backend.order.dto.CreateOrderDto;
import com.enunas.backend.order.dto.OrderItemRequestDto;
import com.enunas.backend.order.dto.ShipmentConfirmationDto;
import com.enunas.backend.order.dto.ShippingAddressDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OrderDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void notes_containingHtml_isInvalid() {
        CreateOrderDto dto = validCreateOrderDto();
        dto.setNotes("<script>alert(document.cookie)</script>");

        Set<ConstraintViolation<CreateOrderDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "notes".equals(v.getPropertyPath().toString()));
    }

    @Test
    void discountCode_withSpaces_isInvalid() {
        CreateOrderDto dto = validCreateOrderDto();
        dto.setDiscountCode("not a code");

        Set<ConstraintViolation<CreateOrderDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "discountCode".equals(v.getPropertyPath().toString()));
    }

    @Test
    void discountCode_alphanumeric_isValid() {
        CreateOrderDto dto = validCreateOrderDto();
        dto.setDiscountCode("SUMMER-25");

        Set<ConstraintViolation<CreateOrderDto>> violations = validator.validate(dto);

        assertThat(violations).noneMatch(v -> "discountCode".equals(v.getPropertyPath().toString()));
    }

    @Test
    void cancelOrderNote_containingHtml_isInvalid() {
        CancelOrderDto dto = new CancelOrderDto();
        dto.setReason(CancelReason.CUSTOMER_REQUEST);
        dto.setNote("<b>urgent</b>");

        Set<ConstraintViolation<CancelOrderDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "note".equals(v.getPropertyPath().toString()));
    }

    @Test
    void shipmentConfirmation_carrierContainingHtml_isInvalid() {
        ShipmentConfirmationDto dto = new ShipmentConfirmationDto();
        dto.setCarrier("<img src=x onerror=alert(1)>");
        dto.setTrackingNumber("1Z999AA10123456784");

        Set<ConstraintViolation<ShipmentConfirmationDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "carrier".equals(v.getPropertyPath().toString()));
    }

    private CreateOrderDto validCreateOrderDto() {
        CreateOrderDto dto = new CreateOrderDto();
        OrderItemRequestDto item = new OrderItemRequestDto();
        item.setListingId(1L);
        item.setQuantity(1);
        dto.setItems(List.of(item));

        ShippingAddressDto address = new ShippingAddressDto();
        address.setFullName("Jane Doe");
        address.setStreet("Hauptstrasse 1");
        address.setCity("Berlin");
        address.setPostalCode("10115");
        address.setCountry("Germany");
        dto.setShippingAddress(address);

        return dto;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=OrderDtoValidationTest`

Expected: `notes_containingHtml_isInvalid`, `discountCode_withSpaces_isInvalid`, `cancelOrderNote_containingHtml_isInvalid`, `shipmentConfirmation_carrierContainingHtml_isInvalid` FAIL; `discountCode_alphanumeric_isValid` PASSES already (no constraint yet means nothing is rejected).

- [ ] **Step 3: Add the constraints**

Replace `backend/src/main/java/com/enunas/backend/order/dto/CreateOrderDto.java` with:

```java
package com.enunas.backend.order.dto;

import com.enunas.backend.validation.NoHtml;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class CreateOrderDto {

    @NotEmpty
    @Valid
    private List<OrderItemRequestDto> items;

    @NotNull
    @Valid
    private ShippingAddressDto shippingAddress;

    @Size(max = 1000)
    @NoHtml
    private String notes;

    /** Optional single discount code (max one per order — no stacking). */
    @Size(max = 32)
    @Pattern(regexp = "^[A-Za-z0-9_-]*$", message = "must contain only letters, digits, - and _")
    private String discountCode;
}
```

Replace `backend/src/main/java/com/enunas/backend/order/dto/CancelOrderDto.java` with:

```java
package com.enunas.backend.order.dto;

import com.enunas.backend.order.CancelReason;
import com.enunas.backend.validation.NoHtml;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class CancelOrderDto {

    @NotNull
    private CancelReason reason;

    @Size(max = 500)
    @NoHtml
    private String note;
}
```

Replace `backend/src/main/java/com/enunas/backend/order/dto/ShipmentConfirmationDto.java` with:

```java
package com.enunas.backend.order.dto;

import com.enunas.backend.validation.NoHtml;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ShipmentConfirmationDto {

    @NotBlank
    @Size(max = 100)
    @NoHtml
    private String carrier;       // "DHL", "UPS", "Hermes"

    @NotBlank
    @Size(max = 100)
    @NoHtml
    private String trackingNumber;

    @Size(max = 500)
    @NoHtml
    private String note;          // optional
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=OrderDtoValidationTest`

Expected: PASS (5/5 tests green)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/order/dto/CreateOrderDto.java backend/src/main/java/com/enunas/backend/order/dto/CancelOrderDto.java backend/src/main/java/com/enunas/backend/order/dto/ShipmentConfirmationDto.java backend/src/test/java/com/enunas/backend/order/OrderDtoValidationTest.java
git commit -m "feat: bound and HTML-guard Orders domain DTOs"
```

---

### Task 5: Returns domain

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/order/dto/ReturnRequestDto.java`
- Modify: `backend/src/main/java/com/enunas/backend/order/dto/ShippingProblemDto.java`
- Modify: `backend/src/main/java/com/enunas/backend/order/dto/UploadReturnLabelDto.java`
- Test: `backend/src/test/java/com/enunas/backend/order/ReturnDtoValidationTest.java` (new)

**Interfaces:**
- Consumes: `com.enunas.backend.validation.NoHtml` from Task 1.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/enunas/backend/order/ReturnDtoValidationTest.java`:

```java
package com.enunas.backend.order;

import com.enunas.backend.order.dto.ReturnRequestDto;
import com.enunas.backend.order.dto.ShippingProblemDto;
import com.enunas.backend.order.dto.UploadReturnLabelDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReturnDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void returnRequest_descriptionContainingHtml_isInvalid() {
        ReturnRequestDto dto = new ReturnRequestDto(null, ReturnReason.WRONG_ITEM, "<script>x</script>");

        Set<ConstraintViolation<ReturnRequestDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "description".equals(v.getPropertyPath().toString()));
    }

    @Test
    void returnRequest_plainDescription_isValid() {
        ReturnRequestDto dto = new ReturnRequestDto(null, ReturnReason.WRONG_ITEM, "Wrong size shipped");

        Set<ConstraintViolation<ReturnRequestDto>> violations = validator.validate(dto);

        assertThat(violations).isEmpty();
    }

    @Test
    void shippingProblem_descriptionContainingHtml_isInvalid() {
        ShippingProblemDto dto = new ShippingProblemDto();
        dto.setDescription("<img src=x onerror=alert(1)>");

        Set<ConstraintViolation<ShippingProblemDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "description".equals(v.getPropertyPath().toString()));
    }

    @Test
    void uploadReturnLabel_labelUrlNotAUrl_isInvalid() {
        UploadReturnLabelDto dto = new UploadReturnLabelDto();
        dto.setCarrier("DHL");
        dto.setTrackingNumber("00340434202343214321");
        dto.setLabelUrl("not-a-url");

        Set<ConstraintViolation<UploadReturnLabelDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "labelUrl".equals(v.getPropertyPath().toString()));
    }

    @Test
    void uploadReturnLabel_validHttpsUrl_isValid() {
        UploadReturnLabelDto dto = new UploadReturnLabelDto();
        dto.setCarrier("DHL");
        dto.setTrackingNumber("00340434202343214321");
        dto.setLabelUrl("https://labels.example.com/abc123.pdf");

        Set<ConstraintViolation<UploadReturnLabelDto>> violations = validator.validate(dto);

        assertThat(violations).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=ReturnDtoValidationTest`

Expected: `returnRequest_descriptionContainingHtml_isInvalid`, `shippingProblem_descriptionContainingHtml_isInvalid`, `uploadReturnLabel_labelUrlNotAUrl_isInvalid` FAIL; the two "isValid" tests PASS already.

- [ ] **Step 3: Add the constraints**

Replace `backend/src/main/java/com/enunas/backend/order/dto/ReturnRequestDto.java` with:

```java
package com.enunas.backend.order.dto;

import com.enunas.backend.order.ReturnReason;
import com.enunas.backend.validation.NoHtml;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReturnRequestDto(
        Long orderItemId,   // null = full order return; set = single-item return
        @NotNull ReturnReason reason,
        @Size(max = 1000) @NoHtml String description
) {}
```

Replace `backend/src/main/java/com/enunas/backend/order/dto/ShippingProblemDto.java` with:

```java
package com.enunas.backend.order.dto;

import com.enunas.backend.validation.NoHtml;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ShippingProblemDto {

    @NotBlank
    @Size(max = 1000)
    @NoHtml
    private String description;
}
```

Replace `backend/src/main/java/com/enunas/backend/order/dto/UploadReturnLabelDto.java` with:

```java
package com.enunas.backend.order.dto;

import com.enunas.backend.validation.NoHtml;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.validator.constraints.URL;

/**
 * Brand-supplied return label — the MVP path (no carrier-API integration exists yet). The brand
 * generates the label itself (its own DHL/UPS/Hermes account) and gives the platform the tracking
 * number and a URL to the label file/PDF; nothing here calls out to a carrier.
 */
@Getter
@Setter
@NoArgsConstructor
public class UploadReturnLabelDto {

    @NotBlank
    @Size(max = 100)
    @NoHtml
    private String carrier; // "DHL", "UPS", "Hermes" — free text, same convention as ShipmentConfirmationDto

    @NotBlank
    @Size(max = 100)
    @NoHtml
    private String trackingNumber;

    @NotBlank
    @Size(max = 2048)
    @URL
    private String labelUrl;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=ReturnDtoValidationTest`

Expected: PASS (5/5 tests green)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/order/dto/ReturnRequestDto.java backend/src/main/java/com/enunas/backend/order/dto/ShippingProblemDto.java backend/src/main/java/com/enunas/backend/order/dto/UploadReturnLabelDto.java backend/src/test/java/com/enunas/backend/order/ReturnDtoValidationTest.java
git commit -m "feat: bound and HTML-guard Returns domain DTOs, validate label URLs"
```

---

### Task 6: Addresses domain

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/order/dto/ShippingAddressDto.java`
- Test: `backend/src/test/java/com/enunas/backend/order/ShippingAddressDtoValidationTest.java` (new)

**Interfaces:**
- Consumes: `com.enunas.backend.validation.NoHtml` from Task 1.
- Produces: nothing consumed by later tasks. Note: `com.enunas.backend.order.ShippingAddress` (the JPA `@Embeddable`) is intentionally left untouched per Global Constraints — only the request-body DTO is hardened.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/enunas/backend/order/ShippingAddressDtoValidationTest.java`:

```java
package com.enunas.backend.order;

import com.enunas.backend.order.dto.ShippingAddressDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ShippingAddressDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void validAddress_hasNoViolations() {
        ShippingAddressDto dto = validAddress();

        Set<ConstraintViolation<ShippingAddressDto>> violations = validator.validate(dto);

        assertThat(violations).isEmpty();
    }

    @Test
    void fullName_containingHtml_isInvalid() {
        ShippingAddressDto dto = validAddress();
        dto.setFullName("<script>alert(1)</script>");

        Set<ConstraintViolation<ShippingAddressDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "fullName".equals(v.getPropertyPath().toString()));
    }

    @Test
    void street_exceedsMaxLength_isInvalid() {
        ShippingAddressDto dto = validAddress();
        dto.setStreet("A".repeat(256));

        Set<ConstraintViolation<ShippingAddressDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "street".equals(v.getPropertyPath().toString()));
    }

    @Test
    void phone_withLetters_isInvalid() {
        ShippingAddressDto dto = validAddress();
        dto.setPhone("call-me-maybe");

        Set<ConstraintViolation<ShippingAddressDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "phone".equals(v.getPropertyPath().toString()));
    }

    @Test
    void phone_validFormat_isValid() {
        ShippingAddressDto dto = validAddress();
        dto.setPhone("+49 30 1234567");

        Set<ConstraintViolation<ShippingAddressDto>> violations = validator.validate(dto);

        assertThat(violations).isEmpty();
    }

    private ShippingAddressDto validAddress() {
        ShippingAddressDto dto = new ShippingAddressDto();
        dto.setFullName("Jane Doe");
        dto.setStreet("Hauptstrasse 1");
        dto.setCity("Berlin");
        dto.setPostalCode("10115");
        dto.setCountry("Germany");
        return dto;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=ShippingAddressDtoValidationTest`

Expected: `fullName_containingHtml_isInvalid`, `street_exceedsMaxLength_isInvalid`, `phone_withLetters_isInvalid` FAIL; `validAddress_hasNoViolations` and `phone_validFormat_isValid` PASS already.

- [ ] **Step 3: Add the constraints**

Replace `backend/src/main/java/com/enunas/backend/order/dto/ShippingAddressDto.java` with:

```java
package com.enunas.backend.order.dto;

import com.enunas.backend.validation.NoHtml;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ShippingAddressDto {

    @NotBlank
    @Size(max = 255)
    @NoHtml
    private String fullName;

    @NotBlank
    @Size(max = 255)
    @NoHtml
    private String street;

    @Size(max = 255)
    @NoHtml
    private String street2;

    @NotBlank
    @Size(max = 128)
    @NoHtml
    private String city;

    @NotBlank
    @Size(max = 16)
    @NoHtml
    private String postalCode;

    @NotBlank
    @Size(max = 100)
    @NoHtml
    private String country;

    @Size(max = 100)
    @NoHtml
    private String state;

    @Size(max = 30)
    @Pattern(regexp = "^[+0-9 ()-]*$", message = "must be a valid phone number")
    private String phone;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=ShippingAddressDtoValidationTest`

Expected: PASS (5/5 tests green)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/order/dto/ShippingAddressDto.java backend/src/test/java/com/enunas/backend/order/ShippingAddressDtoValidationTest.java
git commit -m "feat: bound and HTML-guard the shipping address DTO"
```

---

### Task 7: Brand applications domain

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/brandpartner/dto/RegisterBrandPartnerDto.java`
- Modify: `backend/src/main/java/com/enunas/backend/brandpartner/dto/UpdateBrandPartnerDto.java`
- Modify: `backend/src/main/java/com/enunas/backend/brandpartner/dto/AdminBrandMasterDataDto.java`
- Test: `backend/src/test/java/com/enunas/backend/brandpartner/BrandPartnerDtoValidationTest.java` (new)

**Interfaces:**
- Consumes: `com.enunas.backend.validation.NoHtml` from Task 1.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/enunas/backend/brandpartner/BrandPartnerDtoValidationTest.java`:

```java
package com.enunas.backend.brandpartner;

import com.enunas.backend.brandpartner.dto.AdminBrandMasterDataDto;
import com.enunas.backend.brandpartner.dto.RegisterBrandPartnerDto;
import com.enunas.backend.brandpartner.dto.UpdateBrandPartnerDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BrandPartnerDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void validRegisterDto_hasNoViolations() {
        RegisterBrandPartnerDto dto = validRegisterDto();

        Set<ConstraintViolation<RegisterBrandPartnerDto>> violations = validator.validate(dto);

        assertThat(violations).isEmpty();
    }

    @Test
    void description_containingHtml_isInvalid() {
        RegisterBrandPartnerDto dto = validRegisterDto();
        dto.setDescription("<script>alert(1)</script>");

        Set<ConstraintViolation<RegisterBrandPartnerDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "description".equals(v.getPropertyPath().toString()));
    }

    @Test
    void websiteUrl_notAUrl_isInvalid() {
        RegisterBrandPartnerDto dto = validRegisterDto();
        dto.setWebsiteUrl("definitely not a url");

        Set<ConstraintViolation<RegisterBrandPartnerDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "websiteUrl".equals(v.getPropertyPath().toString()));
    }

    @Test
    void instagramHandle_withSpaces_isInvalid() {
        RegisterBrandPartnerDto dto = validRegisterDto();
        dto.setInstagramHandle("not a handle!");

        Set<ConstraintViolation<RegisterBrandPartnerDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "instagramHandle".equals(v.getPropertyPath().toString()));
    }

    @Test
    void vatId_isNeverFormatValidated_onlyLengthCapped() {
        RegisterBrandPartnerDto dto = validRegisterDto();
        dto.setVatId("this is not a real VAT id but that's fine");

        Set<ConstraintViolation<RegisterBrandPartnerDto>> violations = validator.validate(dto);

        // Free-text VAT id is accepted (manual check per BrandPartnerService) — no violation.
        assertThat(violations).isEmpty();
    }

    @Test
    void vatId_exceedsMaxLength_isInvalid() {
        RegisterBrandPartnerDto dto = validRegisterDto();
        dto.setVatId("A".repeat(33));

        Set<ConstraintViolation<RegisterBrandPartnerDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "vatId".equals(v.getPropertyPath().toString()));
    }

    @Test
    void updateDto_returnInstructions_containingHtml_isInvalid() {
        UpdateBrandPartnerDto dto = new UpdateBrandPartnerDto();
        dto.setReturnInstructions("<img src=x onerror=alert(1)>");

        Set<ConstraintViolation<UpdateBrandPartnerDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "returnInstructions".equals(v.getPropertyPath().toString()));
    }

    @Test
    void adminBrandMasterData_taxNumber_exceedsMaxLength_isInvalid() {
        AdminBrandMasterDataDto dto = new AdminBrandMasterDataDto();
        dto.setLegalName("Acme GmbH");
        dto.setAddressStreet("Musterstrasse 1");
        dto.setAddressPostalCode("10115");
        dto.setAddressCity("Berlin");
        dto.setAddressCountry("DE");
        dto.setTaxNumber("A".repeat(33));

        Set<ConstraintViolation<AdminBrandMasterDataDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "taxNumber".equals(v.getPropertyPath().toString()));
    }

    private RegisterBrandPartnerDto validRegisterDto() {
        RegisterBrandPartnerDto dto = new RegisterBrandPartnerDto();
        dto.setEmail("brand@example.com");
        dto.setPassword("supersecret1");
        dto.setBrandName("Acme");
        dto.setFirstName("Jane");
        dto.setLastName("Doe");
        dto.setLegalName("Acme GmbH");
        dto.setAddressStreet("Musterstrasse 1");
        dto.setAddressPostalCode("10115");
        dto.setAddressCity("Berlin");
        dto.setAddressCountry("DE");
        return dto;
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=BrandPartnerDtoValidationTest`

Expected: `description_containingHtml_isInvalid`, `websiteUrl_notAUrl_isInvalid`, `instagramHandle_withSpaces_isInvalid`, `vatId_exceedsMaxLength_isInvalid`, `updateDto_returnInstructions_containingHtml_isInvalid`, `adminBrandMasterData_taxNumber_exceedsMaxLength_isInvalid` FAIL; `validRegisterDto_hasNoViolations` and `vatId_isNeverFormatValidated_onlyLengthCapped` PASS already.

- [ ] **Step 3: Add the constraints**

Replace `backend/src/main/java/com/enunas/backend/brandpartner/dto/RegisterBrandPartnerDto.java` with:

```java
package com.enunas.backend.brandpartner.dto;

import com.enunas.backend.validation.NoHtml;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.hibernate.validator.constraints.URL;

@Data
public class RegisterBrandPartnerDto {

    @NotBlank
    @Email
    private String email;

    @NotBlank
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;

    @NotBlank
    @Size(max = 100)
    @NoHtml
    private String brandName;

    /** Contact person behind the brand — first name. */
    @NotBlank
    @Size(max = 100)
    @NoHtml
    private String firstName;

    /** Contact person behind the brand — last name. */
    @NotBlank
    @Size(max = 100)
    @NoHtml
    private String lastName;

    @Size(max = 5000)
    @NoHtml
    private String description;

    @Size(max = 2048)
    @URL
    private String logoUrl;

    @Size(max = 2048)
    @URL
    private String websiteUrl;

    @Size(max = 30)
    @Pattern(regexp = "^[A-Za-z0-9._]*$", message = "must contain only letters, digits, . and _")
    private String instagramHandle;

    @Size(max = 30)
    @Pattern(regexp = "^[A-Za-z0-9._]*$", message = "must contain only letters, digits, . and _")
    private String tiktokHandle;

    /** ISO 3166-1 alpha-2 country code (e.g. "DE", "US"). Optional at apply time. */
    @Size(min = 2, max = 2)
    private String country;

    /** Public business contact email. Optional; defaults to login email if omitted. */
    @Email
    private String contactEmail;

    /**
     * USt-IdNr (VAT identification number). Captured at onboarding. Whether it is mandatory is
     * controlled application-side by the {@code enunas.brand.vat-id-required} toggle (default false) —
     * never validated for format/correctness here (manual check). See BrandPartnerService. Only
     * length is capped, to keep abuse/oversized input out without touching that business rule.
     */
    @Size(max = 32)
    private String vatId;

    /** Steuernummer (German tax number). Optional. Length-capped only, same rationale as vatId. */
    @Size(max = 32)
    private String taxNumber;

    // ===== §22f supplier legal name + business address — mandatory master data (no VAT logic). =====

    @NotBlank
    @Size(max = 255)
    @NoHtml
    private String legalName;

    @NotBlank
    @Size(max = 255)
    @NoHtml
    private String addressStreet;

    @NotBlank
    @Size(max = 16)
    @NoHtml
    private String addressPostalCode;

    @NotBlank
    @Size(max = 128)
    @NoHtml
    private String addressCity;

    /** ISO 3166-1 alpha-2 country code of the business address. */
    @NotBlank
    @Size(min = 2, max = 2)
    private String addressCountry;

    // ===== Returns destination — OPTIONAL. Omit the whole block and returns fall back to the
    // §22f address above. Set it when the brand ships/receives through a 3PL or a separate
    // warehouse. Purely logistics: never feeds the `domestic` VAT flag. =====

    @Size(max = 255)
    @NoHtml
    private String returnRecipient;

    @Size(max = 255)
    @NoHtml
    private String returnStreet;

    @Size(max = 16)
    @NoHtml
    private String returnPostalCode;

    @Size(max = 128)
    @NoHtml
    private String returnCity;

    /** ISO 3166-1 alpha-2 country code of the returns destination. */
    @Size(min = 2, max = 2)
    private String returnCountry;

    @Size(max = 2000)
    @NoHtml
    private String returnInstructions;
}
```

Replace `backend/src/main/java/com/enunas/backend/brandpartner/dto/UpdateBrandPartnerDto.java` with:

```java
package com.enunas.backend.brandpartner.dto;

import com.enunas.backend.validation.NoHtml;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.hibernate.validator.constraints.URL;

@Data
public class UpdateBrandPartnerDto {

    @Size(max = 5000)
    @NoHtml
    private String description;

    @Size(max = 2048)
    @URL
    private String logoUrl;

    @Size(max = 2048)
    @URL
    private String websiteUrl;

    @Size(max = 30)
    @Pattern(regexp = "^[A-Za-z0-9._]*$", message = "must contain only letters, digits, . and _")
    private String instagramHandle;

    @Size(max = 30)
    @Pattern(regexp = "^[A-Za-z0-9._]*$", message = "must contain only letters, digits, . and _")
    private String tiktokHandle;

    @Size(min = 2, max = 2)
    private String country;

    @Email
    private String contactEmail;

    @Size(max = 32)
    private String vatId;

    @Size(max = 32)
    private String taxNumber;

    // §22f supplier legal name + address — optional on update (null = keep current).
    @Size(max = 255)
    @NoHtml
    private String legalName;

    @Size(max = 255)
    @NoHtml
    private String addressStreet;

    @Size(max = 16)
    @NoHtml
    private String addressPostalCode;

    @Size(max = 128)
    @NoHtml
    private String addressCity;

    @Size(min = 2, max = 2)
    private String addressCountry;

    // Returns destination — optional on update (null = keep current). Brands self-serve this via
    // PATCH /brandpartner/me: moving warehouses must not require an admin. Returns already in
    // flight are unaffected, because they snapshot the address at request time.
    @Size(max = 255)
    @NoHtml
    private String returnRecipient;

    @Size(max = 255)
    @NoHtml
    private String returnStreet;

    @Size(max = 16)
    @NoHtml
    private String returnPostalCode;

    @Size(max = 128)
    @NoHtml
    private String returnCity;

    @Size(min = 2, max = 2)
    private String returnCountry;

    @Size(max = 2000)
    @NoHtml
    private String returnInstructions;
}
```

Replace `backend/src/main/java/com/enunas/backend/brandpartner/dto/AdminBrandMasterDataDto.java` with:

```java
package com.enunas.backend.brandpartner.dto;

import com.enunas.backend.validation.NoHtml;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Admin update of a brand's §22f master data. Constraints mirror the onboarding DTO
 * ({@code RegisterBrandPartnerDto}) so the admin and vendor paths validate identically — address
 * fields mandatory, country 2-letter. vatId / taxNumber stay optional (no format validation).
 * Scope is strictly these fields: no financial, payout, or {@code domestic} data.
 */
@Data
public class AdminBrandMasterDataDto {

    @NotBlank
    @Size(max = 255)
    @NoHtml
    private String legalName;

    @NotBlank
    @Size(max = 255)
    @NoHtml
    private String addressStreet;

    @NotBlank
    @Size(max = 16)
    @NoHtml
    private String addressPostalCode;

    @NotBlank
    @Size(max = 128)
    @NoHtml
    private String addressCity;

    @NotBlank
    @Size(min = 2, max = 2)
    private String addressCountry;

    @Size(max = 32)
    private String vatId;

    @Size(max = 32)
    private String taxNumber;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=BrandPartnerDtoValidationTest,BrandPartnerVatIdToggleTest`

Expected: PASS (8/8 new tests green, and the pre-existing `BrandPartnerVatIdToggleTest` still green — confirming the "vatId is never format-validated" business rule wasn't broken).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/brandpartner/dto/RegisterBrandPartnerDto.java backend/src/main/java/com/enunas/backend/brandpartner/dto/UpdateBrandPartnerDto.java backend/src/main/java/com/enunas/backend/brandpartner/dto/AdminBrandMasterDataDto.java backend/src/test/java/com/enunas/backend/brandpartner/BrandPartnerDtoValidationTest.java
git commit -m "feat: bound and HTML-guard Brand applications domain DTOs"
```

---

### Task 8: Coupons domain

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/discount/dto/CreateDiscountDto.java`
- Test: `backend/src/test/java/com/enunas/backend/discount/CreateDiscountDtoValidationTest.java` (new)

**Interfaces:**
- Consumes: nothing new (this task uses only `jakarta.validation.constraints.Pattern`/`Size`, already imported elsewhere).
- Produces: nothing consumed by later tasks. This is the final task.

Note: `UpdateDiscountDto` has no free-text fields (`percent`, `validFrom`, `validUntil`, `maxUses`, `active` — all numeric/boolean/temporal, already constrained) — nothing to add there.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/enunas/backend/discount/CreateDiscountDtoValidationTest.java`:

```java
package com.enunas.backend.discount;

import com.enunas.backend.discount.dto.CreateDiscountDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CreateDiscountDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void validCode_hasNoViolations() {
        CreateDiscountDto dto = new CreateDiscountDto();
        dto.setCode("SUMMER-25");
        dto.setPercent(new BigDecimal("0.10"));

        Set<ConstraintViolation<CreateDiscountDto>> violations = validator.validate(dto);

        assertThat(violations).isEmpty();
    }

    @Test
    void code_containingHtml_isInvalid() {
        CreateDiscountDto dto = new CreateDiscountDto();
        dto.setCode("<script>alert(1)</script>");
        dto.setPercent(new BigDecimal("0.10"));

        Set<ConstraintViolation<CreateDiscountDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "code".equals(v.getPropertyPath().toString()));
    }

    @Test
    void code_tooShort_isInvalid() {
        CreateDiscountDto dto = new CreateDiscountDto();
        dto.setCode("AB");
        dto.setPercent(new BigDecimal("0.10"));

        Set<ConstraintViolation<CreateDiscountDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "code".equals(v.getPropertyPath().toString()));
    }

    @Test
    void code_withWhitespace_isInvalid() {
        CreateDiscountDto dto = new CreateDiscountDto();
        dto.setCode("SUMMER 25");
        dto.setPercent(new BigDecimal("0.10"));

        Set<ConstraintViolation<CreateDiscountDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "code".equals(v.getPropertyPath().toString()));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=CreateDiscountDtoValidationTest`

Expected: `code_containingHtml_isInvalid`, `code_tooShort_isInvalid`, `code_withWhitespace_isInvalid` FAIL (only `@NotBlank` exists today, no length/charset constraint); `validCode_hasNoViolations` PASSES already.

- [ ] **Step 3: Add the constraint**

Replace `backend/src/main/java/com/enunas/backend/discount/dto/CreateDiscountDto.java` with:

```java
package com.enunas.backend.discount.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Create payload shared by the admin and brand endpoints. The discount {@code type} is never
 * read from the client — it is fixed by the endpoint (ADMIN vs BRAND), so a brand can never
 * mint an admin-absorbed code. The per-type percent cap is enforced server-side.
 */
@Data
public class CreateDiscountDto {

    @NotBlank
    @Size(min = 3, max = 32)
    @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "must contain only letters, digits, - and _")
    private String code;

    /** Discount fraction, e.g. 0.10 for 10%. Upper bound checked per type in the service. */
    @NotNull
    @DecimalMin(value = "0.0001")
    @DecimalMax(value = "1.0000")
    private BigDecimal percent;

    private LocalDateTime validFrom;

    private LocalDateTime validUntil;

    @Min(1)
    private Integer maxUses;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=CreateDiscountDtoValidationTest`

Expected: PASS (4/4 tests green)

- [ ] **Step 5: Run the full backend test suite**

Run: `./mvnw test` (from `backend/`)

Expected: PASS — every test across the whole module green, including all tests touched in Tasks 1–8 and every pre-existing test (`CreateProductDtoValidationTest`, `BrandPartnerVatIdToggleTest`, and the full integration suite).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/enunas/backend/discount/dto/CreateDiscountDto.java backend/src/test/java/com/enunas/backend/discount/CreateDiscountDtoValidationTest.java
git commit -m "feat: bound and charset-restrict discount codes"
```
