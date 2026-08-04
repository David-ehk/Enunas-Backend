# Checkout Address Handling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **NO GIT COMMITS during execution.** The project owner handles all `git add`/`git commit`/`git push` themselves. Every "Commit" step below is replaced by "Leave the change in the working tree" — do not stage or commit anything. Review diffs are taken against the uncommitted working tree (`git diff -- <touched files>`), not commit ranges.

**Goal:** Give customers persisted, self-service saved addresses; restructure the checkout-time shipping address to German field conventions (`firstName`/`lastName`/`street`/`houseNumber`); and let checkout accept either a saved address or a fresh inline one, always validated server-side regardless of source.

**Architecture:** `ShippingAddressDto`/`ShippingAddress` (the existing order-time DTO and JPA `@Embeddable`) are restructured in place — no new snapshot entity, since `Order` already embeds this value fresh at creation time and never re-reads it, which already satisfies "immutable snapshot." A new `UserAddress` entity + full CRUD (`UserAddressController` under `/customer/addresses`) gives customers a saved-address book, structurally separate from and never read by order/invoice/return/shipment/refund logic after an order exists. `CreateOrderDto` accepts either a `savedAddressId` or an inline `shippingAddress` — exactly one, enforced by a custom class-level constraint — and `OrderService` resolves whichever was given into the same shape before building the order-time snapshot, exactly as it does today.

**Tech Stack:** Java 21, Spring Boot 4.0.5, Jakarta Bean Validation (Hibernate Validator, already on the classpath), Spring Data JPA, Flyway, Lombok, JUnit 5 + AssertJ + Spring Boot Test / Testcontainers for integration tests (all already in use in this codebase — no new dependencies).

## Global Constraints

- No new Maven dependencies.
- `country` stays `private String country` everywhere — never an enum, never a DB constraint. Checkout-time allowed values live in one place, `AllowedShippingCountries` (a plain Java `Set<String>` constant, `Set.of("DE")` today) — expanding to more countries later is a one-line edit there, never a migration.
- The backend must not know or assume anything about how the frontend obtained an address (Google Places, manual entry, or anything else) — it validates whatever arrives, uniformly.
- **`UserAddress` is a customer convenience feature only. Business documents (orders, invoices, returns, shipments, refunds) always use the immutable Order `ShippingAddress` snapshot and must never read from `UserAddress` after order creation.** Structurally enforced: `Order` gets no foreign key to `UserAddress` — only a value-embedded `ShippingAddress` built once at creation time.
- Reuse `com.enunas.backend.validation.NoHtml` (already shipped) on every free-text field — never redefine it.
- Follow the existing custom-validator convention exactly: annotation interface + separate `ConstraintValidator` class (see `com.enunas.backend.product.validation.ValidCatalogueCategory`/`CatalogueCategoryValidator` for the pattern already in this codebase).
- DTO `@Size` caps must never exceed their backing DB column width (a prior plan's final review caught this class of bug after the fact — this plan sizes columns and DTO caps together, in the same task, from the start).
- Never edit an applied Flyway migration (V0.0.1 through V17 are off-limits) — only add new `Vxx` scripts. Never destructively drop a column with existing free-text data without a clear justification; when in doubt, add new columns and leave the old ones in place, unused.
- No worktree, no git commits, no pushes during execution — see the banner above.

## File Structure

| File | Responsibility |
|---|---|
| `order/validation/AllowedShippingCountries.java`, `ValidShippingCountry.java`, `ValidShippingCountryValidator.java` | Single source of truth for which countries checkout currently accepts. |
| `order/dto/ShippingAddressDto.java`, `order/ShippingAddress.java` | Restructured order-time address (DTO + JPA embeddable), German field shape. |
| `db/migration/V18__restructure_shipping_address.sql` | Adds `first_name`/`last_name`/`house_number` to `orders`, best-effort backfill, no destructive drops. |
| `order/OrderService.java` | Address-building block updated for the new fields; later gains the saved-vs-inline resolution step. |
| `compliance/Vat22fExportService.java` | Compliance export's `destinationName`/`destinationStreet` derivation updated for the new fields. |
| `customer/UserAddress.java`, `UserAddressRepository.java` | New entity + repository for saved addresses. |
| `db/migration/V19__user_addresses.sql` | New `user_addresses` table. |
| `customer/dto/UserAddressDto.java`, `UserAddressResponseDto.java` | Request/response DTOs for the saved-address CRUD API. |
| `customer/UserAddressService.java`, `UserAddressController.java` | Saved-address CRUD business logic and REST endpoints. |
| `exception/AddressNotFoundException.java` | Not-found/not-owned saved address → 404, via the existing `GlobalExceptionHandler` bucket. |
| `order/validation/ExactlyOneAddressSource.java` + validator | Class-level constraint on `CreateOrderDto`: exactly one of `savedAddressId`/`shippingAddress`. |
| `order/dto/CreateOrderDto.java` | Gains `savedAddressId`, `shippingAddress` becomes optional, the exactly-one-of constraint. |

---

### Task 1: `@ValidShippingCountry` constraint + `AllowedShippingCountries`

**Files:**
- Create: `backend/src/main/java/com/enunas/backend/order/validation/AllowedShippingCountries.java`
- Create: `backend/src/main/java/com/enunas/backend/order/validation/ValidShippingCountry.java`
- Create: `backend/src/main/java/com/enunas/backend/order/validation/ValidShippingCountryValidator.java`
- Test: `backend/src/test/java/com/enunas/backend/order/validation/ValidShippingCountryValidatorTest.java`

**Interfaces:**
- Produces: `AllowedShippingCountries.isAllowed(String country)` — `static boolean`, null-safe, `false` for `null`. Consumed later by `ValidShippingCountryValidator` (this task) and `OrderService.resolveShippingAddress` (Task 5).
- Produces: `@ValidShippingCountry` — field-level Jakarta constraint, usable on `String` fields. Null is always valid (presence is `@NotBlank`'s job).

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/enunas/backend/order/validation/ValidShippingCountryValidatorTest.java`:

```java
package com.enunas.backend.order.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ValidShippingCountryValidatorTest {

    private final ValidShippingCountryValidator validator = new ValidShippingCountryValidator();

    @Test
    void nullValue_isValid() {
        assertThat(validator.isValid(null, null)).isTrue();
    }

    @Test
    void de_isValid() {
        assertThat(validator.isValid("DE", null)).isTrue();
    }

    @Test
    void fr_isInvalid() {
        assertThat(validator.isValid("FR", null)).isFalse();
    }

    @Test
    void lowercase_de_isInvalid() {
        // Case-sensitive on purpose: the rest of this codebase's country fields are always
        // uppercase ISO 3166-1 alpha-2 (see RegisterBrandPartnerDto.addressCountry).
        assertThat(validator.isValid("de", null)).isFalse();
    }

    @Test
    void allowedShippingCountries_isAllowed_matchesValidator() {
        assertThat(AllowedShippingCountries.isAllowed("DE")).isTrue();
        assertThat(AllowedShippingCountries.isAllowed("AT")).isFalse();
        assertThat(AllowedShippingCountries.isAllowed(null)).isFalse();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=ValidShippingCountryValidatorTest` (from `backend/`)

Expected: compile failure — `ValidShippingCountryValidator`/`AllowedShippingCountries` don't exist yet.

- [ ] **Step 3: Write the implementation**

Create `backend/src/main/java/com/enunas/backend/order/validation/AllowedShippingCountries.java`:

```java
package com.enunas.backend.order.validation;

import java.util.Set;

/**
 * Single source of truth for which ISO 3166-1 alpha-2 country codes checkout currently accepts.
 * Deliberately just a {@code Set}, not a DB table or enum — expanding shipping coverage (e.g.
 * DE -> DE+AT -> DACH -> EU) is a one-line edit here, never a migration, never a code change to
 * any DTO. Only affects checkout; {@code UserAddress} (saved addresses) is not restricted by
 * this — a customer may save an address for a country not currently shippable, and checkout
 * will reject using it with a clear error, without blocking them from having saved it.
 */
public final class AllowedShippingCountries {

    public static final Set<String> ALLOWED = Set.of("DE");

    public static boolean isAllowed(String country) {
        return country != null && ALLOWED.contains(country);
    }

    private AllowedShippingCountries() {}
}
```

Create `backend/src/main/java/com/enunas/backend/order/validation/ValidShippingCountry.java`:

```java
package com.enunas.backend.order.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = ValidShippingCountryValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidShippingCountry {

    String message() default "shipping is not currently available for this country";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
```

Create `backend/src/main/java/com/enunas/backend/order/validation/ValidShippingCountryValidator.java`:

```java
package com.enunas.backend.order.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ValidShippingCountryValidator implements ConstraintValidator<ValidShippingCountry, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return AllowedShippingCountries.isAllowed(value);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw test -Dtest=ValidShippingCountryValidatorTest`

Expected: PASS (5/5 tests green)

- [ ] **Step 5: Leave the change in the working tree** (no commit — see banner)

---

### Task 2: Restructure `ShippingAddressDto`/`ShippingAddress` to the German field shape

**Files:**
- Modify: `backend/src/main/java/com/enunas/backend/order/dto/ShippingAddressDto.java`
- Modify: `backend/src/main/java/com/enunas/backend/order/ShippingAddress.java`
- Create: `backend/src/main/resources/db/migration/V18__restructure_shipping_address.sql`
- Modify: `backend/src/main/java/com/enunas/backend/order/OrderService.java` (address-building block only)
- Modify: `backend/src/main/java/com/enunas/backend/compliance/Vat22fExportService.java`
- Modify: `backend/src/test/java/com/enunas/backend/order/ShippingAddressDtoValidationTest.java` (full rewrite)
- Modify: `backend/src/test/java/com/enunas/backend/order/OrderDtoValidationTest.java` (full rewrite)
- Modify: `backend/src/test/java/com/enunas/backend/discount/integration/AbstractDiscountIntegrationTest.java` (`postOrder`'s address literal only)
- Modify: `backend/src/test/java/com/enunas/backend/discount/integration/Vat22fComplianceTest.java` (one assertion)

**Interfaces:**
- Consumes: nothing from Task 1 in the DTO/entity fields themselves — `country` will consume `@ValidShippingCountry` from Task 1.
- Produces: `ShippingAddressDto` with fields `firstName, lastName, street, houseNumber, addressLine2, postalCode, city, country, phone` — every later task that touches an address uses exactly this shape and these exact getter names.
- Produces: `ShippingAddress` (JPA embeddable) with the same field names, mapped to `orders` columns `first_name, last_name, street, house_number, street2 (as addressLine2), postal_code, city, country, phone`.

This task is one atomic unit: the DTO, the embeddable, every place that builds one from the other, and every existing test/fixture that constructs a `ShippingAddressDto` all have to change together, or the codebase won't compile / existing tests will fail on the old field names. Splitting it further would leave an intermediate broken state.

- [ ] **Step 1: Write the failing tests**

Replace `backend/src/test/java/com/enunas/backend/order/ShippingAddressDtoValidationTest.java` with:

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
    void firstName_containingHtml_isInvalid() {
        ShippingAddressDto dto = validAddress();
        dto.setFirstName("<script>alert(1)</script>");

        Set<ConstraintViolation<ShippingAddressDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "firstName".equals(v.getPropertyPath().toString()));
    }

    @Test
    void street_exceedsMaxLength_isInvalid() {
        ShippingAddressDto dto = validAddress();
        dto.setStreet("A".repeat(256));

        Set<ConstraintViolation<ShippingAddressDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "street".equals(v.getPropertyPath().toString()));
    }

    @Test
    void houseNumber_withLetterSuffix_isValid() {
        ShippingAddressDto dto = validAddress();
        dto.setHouseNumber("12a");

        Set<ConstraintViolation<ShippingAddressDto>> violations = validator.validate(dto);

        assertThat(violations).isEmpty();
    }

    @Test
    void houseNumber_withDisallowedCharacter_isInvalid() {
        ShippingAddressDto dto = validAddress();
        dto.setHouseNumber("12<b>");

        Set<ConstraintViolation<ShippingAddressDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "houseNumber".equals(v.getPropertyPath().toString()));
    }

    @Test
    void postalCode_fourDigits_isInvalid() {
        ShippingAddressDto dto = validAddress();
        dto.setPostalCode("1234");

        Set<ConstraintViolation<ShippingAddressDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "postalCode".equals(v.getPropertyPath().toString()));
    }

    @Test
    void postalCode_withLetters_isInvalid() {
        ShippingAddressDto dto = validAddress();
        dto.setPostalCode("1012AB");

        Set<ConstraintViolation<ShippingAddressDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "postalCode".equals(v.getPropertyPath().toString()));
    }

    @Test
    void country_notDE_isInvalid() {
        ShippingAddressDto dto = validAddress();
        dto.setCountry("NL");

        Set<ConstraintViolation<ShippingAddressDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "country".equals(v.getPropertyPath().toString()));
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
        dto.setFirstName("Jane");
        dto.setLastName("Doe");
        dto.setStreet("Hauptstrasse");
        dto.setHouseNumber("1");
        dto.setCity("Berlin");
        dto.setPostalCode("10115");
        dto.setCountry("DE");
        return dto;
    }
}
```

Replace `backend/src/test/java/com/enunas/backend/order/OrderDtoValidationTest.java` with:

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
        address.setFirstName("Jane");
        address.setLastName("Doe");
        address.setStreet("Hauptstrasse");
        address.setHouseNumber("1");
        address.setCity("Berlin");
        address.setPostalCode("10115");
        address.setCountry("DE");
        dto.setShippingAddress(address);

        return dto;
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -Dtest=ShippingAddressDtoValidationTest,OrderDtoValidationTest`

Expected: compile failures — `setFirstName`/`setHouseNumber`/etc. don't exist yet on `ShippingAddressDto`.

- [ ] **Step 3: Restructure the DTO and embeddable**

Replace `backend/src/main/java/com/enunas/backend/order/dto/ShippingAddressDto.java` with:

```java
package com.enunas.backend.order.dto;

import com.enunas.backend.order.validation.ValidShippingCountry;
import com.enunas.backend.validation.NoHtml;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ShippingAddressDto {

    @NotBlank
    @Size(max = 100)
    @NoHtml
    private String firstName;

    @NotBlank
    @Size(max = 100)
    @NoHtml
    private String lastName;

    @NotBlank
    @Size(max = 255)
    @NoHtml
    private String street;

    @NotBlank
    @Size(max = 16)
    @Pattern(regexp = "^[A-Za-z0-9 /-]{1,16}$", message = "must be a valid house number")
    private String houseNumber;

    @Size(max = 255)
    @NoHtml
    private String addressLine2;

    @NotBlank
    @Pattern(regexp = "^\\d{5}$", message = "must be a 5-digit German postal code")
    private String postalCode;

    @NotBlank
    @Size(max = 128)
    @NoHtml
    private String city;

    @NotBlank
    @Size(min = 2, max = 2)
    @ValidShippingCountry
    private String country;

    @Size(max = 30)
    @Pattern(regexp = "^[+0-9 ()-]*$", message = "must be a valid phone number")
    private String phone;
}
```

Replace `backend/src/main/java/com/enunas/backend/order/ShippingAddress.java` with:

```java
package com.enunas.backend.order;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Embeddable
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShippingAddress {

    @NotBlank
    private String firstName;

    @NotBlank
    private String lastName;

    @NotBlank
    private String street;

    @NotBlank
    @Column(name = "house_number")
    private String houseNumber;

    @Column(name = "street2")
    private String addressLine2;

    @NotBlank
    private String postalCode;

    @NotBlank
    private String city;

    @NotBlank
    private String country;

    private String phone;
}
```

- [ ] **Step 4: Add the migration**

Create `backend/src/main/resources/db/migration/V18__restructure_shipping_address.sql`:

```sql
-- =============================================================================
-- V18: restructure the orders table's embedded shipping address to the German
-- checkout field shape: firstName/lastName replace fullName, a new house_number
-- column is added (German addresses separate street from house number), and
-- `state` is retired (Germany doesn't use states for shipping).
--
-- full_name and state are NOT dropped -- left in place, unused by new code,
-- rather than risk a lossy destructive migration on free-text data. A later
-- cleanup migration can drop them once confirmed safe.
--
-- Best-effort backfill: existing rows' full_name is split on the first space
-- into first_name/last_name. house_number is left NULL for historical rows --
-- it cannot be safely auto-extracted from the free-text street column.
--
-- Column widths match the DTO's @Size caps exactly (varchar(100) for
-- first_name/last_name, varchar(16) for house_number) so an over-limit value
-- can never pass bean validation and then fail at INSERT.
--
-- Additive only where it matters; full_name/state stay in place, unused.
-- V0.0.1..V17 are left untouched (Flyway checksum).
-- =============================================================================

ALTER TABLE orders ADD COLUMN IF NOT EXISTS first_name varchar(100);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS last_name varchar(100);
ALTER TABLE orders ADD COLUMN IF NOT EXISTS house_number varchar(16);

UPDATE orders
SET first_name = split_part(full_name, ' ', 1),
    last_name = trim(substring(full_name from position(' ' in full_name) + 1))
WHERE full_name IS NOT NULL AND position(' ' in full_name) > 0 AND first_name IS NULL;

UPDATE orders
SET first_name = full_name
WHERE full_name IS NOT NULL AND position(' ' in full_name) = 0 AND first_name IS NULL;
```

- [ ] **Step 5: Update `OrderService`'s address-building block**

In `backend/src/main/java/com/enunas/backend/order/OrderService.java`, find the block inside `createOrder` that reads:

```java
        ShippingAddress address = ShippingAddress.builder()
                .fullName(dto.getShippingAddress().getFullName())
                .street(dto.getShippingAddress().getStreet())
                .street2(dto.getShippingAddress().getStreet2())
                .city(dto.getShippingAddress().getCity())
                .postalCode(dto.getShippingAddress().getPostalCode())
                .country(dto.getShippingAddress().getCountry())
                .state(dto.getShippingAddress().getState())
                .phone(dto.getShippingAddress().getPhone())
                .build();
```

Replace it with:

```java
        ShippingAddress address = ShippingAddress.builder()
                .firstName(dto.getShippingAddress().getFirstName())
                .lastName(dto.getShippingAddress().getLastName())
                .street(dto.getShippingAddress().getStreet())
                .houseNumber(dto.getShippingAddress().getHouseNumber())
                .addressLine2(dto.getShippingAddress().getAddressLine2())
                .city(dto.getShippingAddress().getCity())
                .postalCode(dto.getShippingAddress().getPostalCode())
                .country(dto.getShippingAddress().getCountry())
                .phone(dto.getShippingAddress().getPhone())
                .build();
```

- [ ] **Step 6: Update the §22f compliance export**

In `backend/src/main/java/com/enunas/backend/compliance/Vat22fExportService.java`, replace these two lines inside `toRow(...)`:

```java
                .destinationName(dest != null ? dest.getFullName() : null)
                .destinationStreet(dest != null ? dest.getStreet() : null)
```

with:

```java
                .destinationName(dest != null ? formatDestinationName(dest.getFirstName(), dest.getLastName()) : null)
                .destinationStreet(dest != null ? formatDestinationStreet(dest.getStreet(), dest.getHouseNumber()) : null)
```

Then add these two private static helpers next to the existing `n(String)` helper in the same class (they reuse it):

```java
    private static String formatDestinationName(String firstName, String lastName) {
        if (firstName == null && lastName == null) return null;
        return (n(firstName) + " " + n(lastName)).trim();
    }

    private static String formatDestinationStreet(String street, String houseNumber) {
        if (street == null && houseNumber == null) return null;
        return (n(street) + " " + n(houseNumber)).trim();
    }
```

- [ ] **Step 7: Fix the integration test fixture that builds an inline address**

In `backend/src/test/java/com/enunas/backend/discount/integration/AbstractDiscountIntegrationTest.java`, find in `postOrder(...)`:

```java
        Map<String, Object> address = Map.of(
                "fullName", "John Doe", "street", "1 Main St",
                "city", "Amsterdam", "postalCode", "1012AB", "country", "NL");
```

Replace with:

```java
        Map<String, Object> address = Map.of(
                "firstName", "John", "lastName", "Doe",
                "street", "Hauptstrasse", "houseNumber", "1",
                "city", "Berlin", "postalCode", "10115", "country", "DE");
```

- [ ] **Step 8: Fix the one compliance-export test assertion that depended on the old address**

In `backend/src/test/java/com/enunas/backend/discount/integration/Vat22fComplianceTest.java`, find:

```java
        assertThat(r.get("destinationCity")).isEqualTo("Amsterdam");                // (5)
```

Replace with:

```java
        assertThat(r.get("destinationCity")).isEqualTo("Berlin");                   // (5)
```

- [ ] **Step 9: Run tests to verify they pass**

Run: `./mvnw test -Dtest=ShippingAddressDtoValidationTest,OrderDtoValidationTest`

Expected: PASS (10/10 + 5/5 tests green)

Then run the full backend suite to catch any other call site this task's restructure affected: `./mvnw test` (from `backend/`). Expect exactly the one pre-existing, unrelated failure noted in prior work (`SettlementIntegrationTest.closedPeriodGuard_...`) — anything else failing means a caller of the old field names was missed; find it with `grep -rn "getFullName\|setFullName\|\.fullName(\|getState()\|\.state(" backend/src/main/java backend/src/test/java` and fix it before moving on.

- [ ] **Step 10: Leave the change in the working tree** (no commit — see banner)

---

### Task 3: `UserAddress` entity + repository

**Files:**
- Create: `backend/src/main/java/com/enunas/backend/customer/UserAddress.java`
- Create: `backend/src/main/java/com/enunas/backend/customer/UserAddressRepository.java`
- Create: `backend/src/main/resources/db/migration/V19__user_addresses.sql`

**Interfaces:**
- Consumes: `com.enunas.backend.user.User` (existing entity).
- Produces: `UserAddress` entity with fields `id, user, firstName, lastName, street, houseNumber, addressLine2, postalCode, city, country, isDefault (boolean), createdAt, updatedAt`. Getter for the boolean is `isDefault()`, setter is `setDefault(boolean)` (Lombok strips the redundant "is" only on the setter, not the getter, for a field already named `isDefault`).
- Produces: `UserAddressRepository` with `findByUserOrderByCreatedAtDesc(User)`, `findByIdAndUser(Long, User)`, `existsByUser(User)` — Task 4 (service) and Task 5 (checkout resolution) both call `findByIdAndUser`.

No dedicated test in this task — the entity and repository are exercised end-to-end by Task 4's tests (there's no meaningful behavior to unit-test in a bare `@Entity`/`JpaRepository` pair beyond what those integration tests already cover).

- [ ] **Step 1: Write the entity**

Create `backend/src/main/java/com/enunas/backend/customer/UserAddress.java`:

```java
package com.enunas.backend.customer;

import com.enunas.backend.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A customer's saved shipping address. Convenience feature only — business documents (orders,
 * invoices, returns, shipments, refunds) always use the immutable Order {@code ShippingAddress}
 * snapshot and must never read from this entity after order creation. There is no foreign key
 * from {@code Order} to this table, by design, so that guarantee is structural, not just a
 * convention.
 */
@Entity
@Table(name = "user_addresses")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserAddress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String firstName;

    @Column(nullable = false)
    private String lastName;

    @Column(nullable = false)
    private String street;

    @Column(name = "house_number", nullable = false)
    private String houseNumber;

    @Column(name = "address_line2")
    private String addressLine2;

    @Column(name = "postal_code", nullable = false)
    private String postalCode;

    @Column(nullable = false)
    private String city;

    @Column(nullable = false)
    private String country;

    @Builder.Default
    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    @Column(updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 2: Write the repository**

Create `backend/src/main/java/com/enunas/backend/customer/UserAddressRepository.java`:

```java
package com.enunas.backend.customer;

import com.enunas.backend.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserAddressRepository extends JpaRepository<UserAddress, Long> {

    List<UserAddress> findByUserOrderByCreatedAtDesc(User user);

    Optional<UserAddress> findByIdAndUser(Long id, User user);

    boolean existsByUser(User user);
}
```

- [ ] **Step 3: Write the migration**

Create `backend/src/main/resources/db/migration/V19__user_addresses.sql`:

```sql
-- =============================================================================
-- V19: user_addresses -- customer self-service saved shipping addresses.
--
-- Convenience feature only: business documents (orders, invoices, returns,
-- shipments, refunds) always read the immutable orders.* shipping-address
-- snapshot (see V18), never this table, once an order exists. There is no
-- foreign key from orders to this table.
--
-- Column widths match the DTO's @Size caps exactly (see UserAddressDto),
-- same discipline as V18, so an over-limit value can never pass bean
-- validation and then fail at INSERT.
--
-- country is unrestricted here on purpose (unlike checkout's ShippingAddress,
-- which is currently DE-only via AllowedShippingCountries) -- a customer may
-- save an address for a country not yet shippable; checkout rejects using it
-- with a clear error rather than the address book blocking them from saving
-- it in the first place.
-- =============================================================================

CREATE TABLE IF NOT EXISTS user_addresses (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES users(id),
    first_name varchar(100) NOT NULL,
    last_name varchar(100) NOT NULL,
    street varchar(255) NOT NULL,
    house_number varchar(16) NOT NULL,
    address_line2 varchar(255),
    postal_code varchar(16) NOT NULL,
    city varchar(128) NOT NULL,
    country varchar(2) NOT NULL,
    is_default boolean NOT NULL DEFAULT false,
    created_at timestamp(6) NOT NULL,
    updated_at timestamp(6) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_user_addresses_user_id ON user_addresses(user_id);
```

- [ ] **Step 4: Verify the application boots and the schema applies**

Run: `./mvnw test -Dtest=NoHtmlValidatorTest` (any fast unit test — this just confirms the module still compiles with the new entity/repository present; Flyway itself is exercised by the first integration test that boots the Spring context, which happens in Task 4).

Expected: PASS.

- [ ] **Step 5: Leave the change in the working tree** (no commit — see banner)

---

### Task 4: `UserAddress` CRUD — DTOs, service, controller, exception wiring

**Files:**
- Create: `backend/src/main/java/com/enunas/backend/customer/dto/UserAddressDto.java`
- Create: `backend/src/main/java/com/enunas/backend/customer/dto/UserAddressResponseDto.java`
- Create: `backend/src/main/java/com/enunas/backend/exception/AddressNotFoundException.java`
- Modify: `backend/src/main/java/com/enunas/backend/exception/GlobalExceptionHandler.java`
- Create: `backend/src/main/java/com/enunas/backend/customer/UserAddressService.java`
- Create: `backend/src/main/java/com/enunas/backend/customer/UserAddressController.java`
- Modify: `backend/src/test/java/com/enunas/backend/discount/integration/AbstractDiscountIntegrationTest.java` (add `user_addresses` to the `@AfterEach` truncate list only)
- Test: `backend/src/test/java/com/enunas/backend/customer/UserAddressDtoValidationTest.java`
- Test: `backend/src/test/java/com/enunas/backend/customer/integration/UserAddressIntegrationTest.java`

**Interfaces:**
- Consumes: `UserAddress`/`UserAddressRepository` from Task 3.
- Produces: `UserAddressDto` (shared create/update request — every field required, full-replace semantics, not partial patch), `UserAddressResponseDto.from(UserAddress)`, `UserAddressService` with `getMyAddresses(User)`, `createAddress(UserAddressDto, User)`, `updateAddress(Long, UserAddressDto, User)`, `deleteAddress(Long, User)`, `setDefault(Long, User)`. `/customer/addresses` REST endpoints. `AddressNotFoundException` — Task 5 reuses this exact exception for the checkout savedAddressId-not-found case.
- `/customer/addresses/**` is already covered by the existing `SecurityConfiguration` rule `.requestMatchers("/customer/**", ...).hasRole("CUSTOMER")` — no security config changes needed.

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/com/enunas/backend/customer/UserAddressDtoValidationTest.java`:

```java
package com.enunas.backend.customer;

import com.enunas.backend.customer.dto.UserAddressDto;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UserAddressDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void validAddress_hasNoViolations() {
        UserAddressDto dto = validDto();

        Set<ConstraintViolation<UserAddressDto>> violations = validator.validate(dto);

        assertThat(violations).isEmpty();
    }

    @Test
    void nonGermanCountry_isValid() {
        // UserAddress is a personal address book, not restricted to checkout's allowed-country
        // list -- a customer may save an address for a country not yet shippable.
        UserAddressDto dto = validDto();
        dto.setCountry("FR");

        Set<ConstraintViolation<UserAddressDto>> violations = validator.validate(dto);

        assertThat(violations).isEmpty();
    }

    @Test
    void nonGermanPostalCode_isValid() {
        UserAddressDto dto = validDto();
        dto.setCountry("NL");
        dto.setPostalCode("1012AB");

        Set<ConstraintViolation<UserAddressDto>> violations = validator.validate(dto);

        assertThat(violations).isEmpty();
    }

    @Test
    void firstName_containingHtml_isInvalid() {
        UserAddressDto dto = validDto();
        dto.setFirstName("<script>alert(1)</script>");

        Set<ConstraintViolation<UserAddressDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "firstName".equals(v.getPropertyPath().toString()));
    }

    @Test
    void houseNumber_blank_isInvalid() {
        UserAddressDto dto = validDto();
        dto.setHouseNumber("");

        Set<ConstraintViolation<UserAddressDto>> violations = validator.validate(dto);

        assertThat(violations).anyMatch(v -> "houseNumber".equals(v.getPropertyPath().toString()));
    }

    private UserAddressDto validDto() {
        UserAddressDto dto = new UserAddressDto();
        dto.setFirstName("Jane");
        dto.setLastName("Doe");
        dto.setStreet("Hauptstrasse");
        dto.setHouseNumber("1");
        dto.setPostalCode("10115");
        dto.setCity("Berlin");
        dto.setCountry("DE");
        return dto;
    }
}
```

Create `backend/src/test/java/com/enunas/backend/customer/integration/UserAddressIntegrationTest.java`:

```java
package com.enunas.backend.customer.integration;

import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UserAddressIntegrationTest extends AbstractDiscountIntegrationTest {

    @Test
    void firstAddress_autoBecomesDefault() {
        seedCustomer();
        String token = login("customer@it.local", "Customer123!");

        ResponseEntity<Map> created = createAddress(token, addressBody("Jane", "Doe"));

        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(created.getBody().get("isDefault")).isEqualTo(true);
    }

    @Test
    void secondAddress_doesNotAutoBecomeDefault() {
        seedCustomer();
        String token = login("customer@it.local", "Customer123!");
        createAddress(token, addressBody("Jane", "Doe"));

        ResponseEntity<Map> second = createAddress(token, addressBody("John", "Doe"));

        assertThat(second.getBody().get("isDefault")).isEqualTo(false);
    }

    @Test
    void setDefault_reassignsDefaultAmongOwnAddresses() {
        seedCustomer();
        String token = login("customer@it.local", "Customer123!");
        long first = addressId(createAddress(token, addressBody("Jane", "Doe")));
        long second = addressId(createAddress(token, addressBody("John", "Doe")));

        ResponseEntity<Map> resp = rest.exchange(
                "/customer/addresses/" + second + "/default", HttpMethod.POST,
                new HttpEntity<>(auth(token)), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("isDefault")).isEqualTo(true);

        List<Map> list = listAddresses(token);
        Map firstRow = list.stream().filter(a -> ((Number) a.get("id")).longValue() == first).findFirst().orElseThrow();
        assertThat(firstRow.get("isDefault")).isEqualTo(false);
    }

    @Test
    void deleteDefault_doesNotAutoPromoteAnother() {
        seedCustomer();
        String token = login("customer@it.local", "Customer123!");
        long first = addressId(createAddress(token, addressBody("Jane", "Doe")));
        createAddress(token, addressBody("John", "Doe"));

        rest.exchange("/customer/addresses/" + first, HttpMethod.DELETE,
                new HttpEntity<>(auth(token)), Void.class);

        List<Map> remaining = listAddresses(token);
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).get("isDefault")).isEqualTo(false);
    }

    @Test
    void customerCannotUpdateAnotherCustomersAddress() {
        seedCustomer();
        String ownerToken = login("customer@it.local", "Customer123!");
        long addressId = addressId(createAddress(ownerToken, addressBody("Jane", "Doe")));

        seedUser("other@it.local", "Other123!", com.enunas.backend.user.Role.CUSTOMER);
        customerRepository.save(com.enunas.backend.customer.Customer.builder()
                .user(userRepository.findByEmail("other@it.local").orElseThrow())
                .build());
        String otherToken = login("other@it.local", "Other123!");

        ResponseEntity<Map> resp = rest.exchange(
                "/customer/addresses/" + addressId, HttpMethod.PUT,
                new HttpEntity<>(addressBody("Mallory", "Hacker"), auth(otherToken)), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    // ===== helpers =====

    private Map<String, Object> addressBody(String firstName, String lastName) {
        Map<String, Object> body = new HashMap<>();
        body.put("firstName", firstName);
        body.put("lastName", lastName);
        body.put("street", "Hauptstrasse");
        body.put("houseNumber", "1");
        body.put("postalCode", "10115");
        body.put("city", "Berlin");
        body.put("country", "DE");
        return body;
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> createAddress(String token, Map<String, Object> body) {
        return rest.exchange("/customer/addresses", HttpMethod.POST,
                new HttpEntity<>(body, auth(token)), Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map> listAddresses(String token) {
        ResponseEntity<List> resp = rest.exchange("/customer/addresses", HttpMethod.GET,
                new HttpEntity<>(auth(token)), List.class);
        return resp.getBody();
    }

    private long addressId(ResponseEntity<Map> resp) {
        return ((Number) resp.getBody().get("id")).longValue();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -Dtest=UserAddressDtoValidationTest`

Expected: compile failure — `UserAddressDto` doesn't exist yet.

(The integration test will also fail to compile for the same reason — both are expected to fail at this step.)

- [ ] **Step 3: Write the DTOs**

Create `backend/src/main/java/com/enunas/backend/customer/dto/UserAddressDto.java`:

```java
package com.enunas.backend.customer.dto;

import com.enunas.backend.validation.NoHtml;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Shared create/update request for a saved address. Every field is required on both paths — an
 * address is a value object, so update is a full replace, not a partial patch (unlike
 * {@code UpdateCustomerProfileDto} elsewhere in this codebase). {@code country} is intentionally
 * NOT restricted to {@code AllowedShippingCountries} here — see {@code UserAddress}'s javadoc.
 */
@Data
public class UserAddressDto {

    @NotBlank
    @Size(max = 100)
    @NoHtml
    private String firstName;

    @NotBlank
    @Size(max = 100)
    @NoHtml
    private String lastName;

    @NotBlank
    @Size(max = 255)
    @NoHtml
    private String street;

    @NotBlank
    @Size(max = 16)
    @Pattern(regexp = "^[A-Za-z0-9 /-]{1,16}$", message = "must be a valid house number")
    private String houseNumber;

    @Size(max = 255)
    @NoHtml
    private String addressLine2;

    @NotBlank
    @Size(max = 16)
    @NoHtml
    private String postalCode;

    @NotBlank
    @Size(max = 128)
    @NoHtml
    private String city;

    @NotBlank
    @Size(min = 2, max = 2)
    @NoHtml
    private String country;
}
```

Create `backend/src/main/java/com/enunas/backend/customer/dto/UserAddressResponseDto.java`:

```java
package com.enunas.backend.customer.dto;

import com.enunas.backend.customer.UserAddress;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class UserAddressResponseDto {

    private Long id;
    private String firstName;
    private String lastName;
    private String street;
    private String houseNumber;
    private String addressLine2;
    private String postalCode;
    private String city;
    private String country;

    // Explicit @JsonProperty: Jackson would otherwise serialize a Lombok-generated isDefault()
    // getter as JSON key "default" (it strips the "is" prefix from boolean getters by default),
    // not the "isDefault" the frontend expects.
    @JsonProperty("isDefault")
    private boolean isDefault;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static UserAddressResponseDto from(UserAddress address) {
        return UserAddressResponseDto.builder()
                .id(address.getId())
                .firstName(address.getFirstName())
                .lastName(address.getLastName())
                .street(address.getStreet())
                .houseNumber(address.getHouseNumber())
                .addressLine2(address.getAddressLine2())
                .postalCode(address.getPostalCode())
                .city(address.getCity())
                .country(address.getCountry())
                .isDefault(address.isDefault())
                .createdAt(address.getCreatedAt())
                .updatedAt(address.getUpdatedAt())
                .build();
    }
}
```

- [ ] **Step 4: Wire the not-found exception**

Create `backend/src/main/java/com/enunas/backend/exception/AddressNotFoundException.java`:

```java
package com.enunas.backend.exception;

public class AddressNotFoundException extends RuntimeException {
    public AddressNotFoundException(String message) {
        super(message);
    }
}
```

In `backend/src/main/java/com/enunas/backend/exception/GlobalExceptionHandler.java`, add the import and add the new exception to the existing not-found bucket:

```java
    @ExceptionHandler({OrderNotFoundException.class, BrandNotFoundException.class,
            ProductNotFoundException.class, CustomerNotFoundException.class, AddressNotFoundException.class})
    public ResponseEntity<Map<String, Object>> handleNotFound(RuntimeException ex) {
```

(This replaces the existing `@ExceptionHandler({OrderNotFoundException.class, BrandNotFoundException.class, ProductNotFoundException.class, CustomerNotFoundException.class})` line — just add `, AddressNotFoundException.class` before the closing `})`.)

- [ ] **Step 5: Write the service**

Create `backend/src/main/java/com/enunas/backend/customer/UserAddressService.java`:

```java
package com.enunas.backend.customer;

import com.enunas.backend.customer.dto.UserAddressDto;
import com.enunas.backend.customer.dto.UserAddressResponseDto;
import com.enunas.backend.exception.AddressNotFoundException;
import com.enunas.backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserAddressService {

    private final UserAddressRepository userAddressRepository;

    @Transactional(readOnly = true)
    public List<UserAddressResponseDto> getMyAddresses(User user) {
        return userAddressRepository.findByUserOrderByCreatedAtDesc(user).stream()
                .map(UserAddressResponseDto::from)
                .toList();
    }

    @Transactional
    public UserAddressResponseDto createAddress(UserAddressDto dto, User user) {
        boolean isFirst = !userAddressRepository.existsByUser(user);
        UserAddress address = UserAddress.builder()
                .user(user)
                .firstName(dto.getFirstName())
                .lastName(dto.getLastName())
                .street(dto.getStreet())
                .houseNumber(dto.getHouseNumber())
                .addressLine2(dto.getAddressLine2())
                .postalCode(dto.getPostalCode())
                .city(dto.getCity())
                .country(dto.getCountry())
                .isDefault(isFirst)
                .build();
        return UserAddressResponseDto.from(userAddressRepository.save(address));
    }

    @Transactional
    public UserAddressResponseDto updateAddress(Long id, UserAddressDto dto, User user) {
        UserAddress address = findOwned(id, user);
        address.setFirstName(dto.getFirstName());
        address.setLastName(dto.getLastName());
        address.setStreet(dto.getStreet());
        address.setHouseNumber(dto.getHouseNumber());
        address.setAddressLine2(dto.getAddressLine2());
        address.setPostalCode(dto.getPostalCode());
        address.setCity(dto.getCity());
        address.setCountry(dto.getCountry());
        return UserAddressResponseDto.from(userAddressRepository.save(address));
    }

    @Transactional
    public void deleteAddress(Long id, User user) {
        userAddressRepository.delete(findOwned(id, user));
    }

    /** No auto-promotion of another address to default on delete — see plan design notes. */
    @Transactional
    public UserAddressResponseDto setDefault(Long id, User user) {
        UserAddress toDefault = findOwned(id, user);
        userAddressRepository.findByUserOrderByCreatedAtDesc(user).forEach(a -> {
            if (!a.getId().equals(toDefault.getId()) && a.isDefault()) {
                a.setDefault(false);
                userAddressRepository.save(a);
            }
        });
        toDefault.setDefault(true);
        return UserAddressResponseDto.from(userAddressRepository.save(toDefault));
    }

    private UserAddress findOwned(Long id, User user) {
        return userAddressRepository.findByIdAndUser(id, user)
                .orElseThrow(() -> new AddressNotFoundException("Address not found: " + id));
    }
}
```

- [ ] **Step 6: Write the controller**

Create `backend/src/main/java/com/enunas/backend/customer/UserAddressController.java`:

```java
package com.enunas.backend.customer;

import com.enunas.backend.customer.dto.UserAddressDto;
import com.enunas.backend.customer.dto.UserAddressResponseDto;
import com.enunas.backend.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/customer/addresses")
@RequiredArgsConstructor
@PreAuthorize("hasRole('CUSTOMER')")
public class UserAddressController {

    private final UserAddressService userAddressService;

    @GetMapping
    public ResponseEntity<List<UserAddressResponseDto>> getMyAddresses(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(userAddressService.getMyAddresses(user));
    }

    @PostMapping
    public ResponseEntity<UserAddressResponseDto> createAddress(
            @Valid @RequestBody UserAddressDto dto,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED).body(userAddressService.createAddress(dto, user));
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserAddressResponseDto> updateAddress(
            @PathVariable Long id,
            @Valid @RequestBody UserAddressDto dto,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(userAddressService.updateAddress(id, dto, user));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAddress(
            @PathVariable Long id,
            @AuthenticationPrincipal User user) {
        userAddressService.deleteAddress(id, user);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/default")
    public ResponseEntity<UserAddressResponseDto> setDefault(
            @PathVariable Long id,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(userAddressService.setDefault(id, user));
    }
}
```

- [ ] **Step 7: Add `user_addresses` to the integration test suite's cleanup**

In `backend/src/test/java/com/enunas/backend/discount/integration/AbstractDiscountIntegrationTest.java`, find:

```java
        jdbc.execute("TRUNCATE TABLE settlement_runs, ledger_entries, payments, order_items, orders, listings, " +
                "product_variants, product_colors, products, discount_codes, brand_economics, " +
                "brand_partners, customers, users RESTART IDENTITY CASCADE");
```

Replace with:

```java
        jdbc.execute("TRUNCATE TABLE settlement_runs, ledger_entries, payments, order_items, orders, listings, " +
                "product_variants, product_colors, products, discount_codes, brand_economics, " +
                "brand_partners, user_addresses, customers, users RESTART IDENTITY CASCADE");
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `./mvnw test -Dtest=UserAddressDtoValidationTest,UserAddressIntegrationTest`

Expected: PASS (5/5 unit tests, 5/5 integration tests green)

- [ ] **Step 9: Leave the change in the working tree** (no commit — see banner)

---

### Task 5: Checkout integration — `savedAddressId` support, exactly-one-of contract

**Files:**
- Create: `backend/src/main/java/com/enunas/backend/order/validation/ExactlyOneAddressSource.java`
- Create: `backend/src/main/java/com/enunas/backend/order/validation/ExactlyOneAddressSourceValidator.java`
- Modify: `backend/src/main/java/com/enunas/backend/order/dto/CreateOrderDto.java`
- Modify: `backend/src/main/java/com/enunas/backend/order/dto/ShippingAddressDto.java` (append one static factory method)
- Modify: `backend/src/main/java/com/enunas/backend/order/OrderService.java` (inject `UserAddressRepository`, add address resolution)
- Test: `backend/src/test/java/com/enunas/backend/order/CreateOrderDtoValidationTest.java`
- Test: `backend/src/test/java/com/enunas/backend/order/integration/CheckoutAddressIntegrationTest.java`

**Interfaces:**
- Consumes: `UserAddress`/`UserAddressRepository` (Task 3), `UserAddressDto`/`UserAddressResponseDto`/`UserAddressService` REST endpoints (Task 4, used by the integration test to seed a saved address), `AllowedShippingCountries.isAllowed(String)` (Task 1), `AddressNotFoundException` (Task 4).
- Produces: nothing consumed by a later task — this is the final task.

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/com/enunas/backend/order/CreateOrderDtoValidationTest.java`:

```java
package com.enunas.backend.order;

import com.enunas.backend.order.dto.CreateOrderDto;
import com.enunas.backend.order.dto.OrderItemRequestDto;
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

class CreateOrderDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Test
    void inlineAddressOnly_isValid() {
        CreateOrderDto dto = baseDto();
        dto.setShippingAddress(validAddress());
        dto.setSavedAddressId(null);

        Set<ConstraintViolation<CreateOrderDto>> violations = validator.validate(dto);

        assertThat(violations).isEmpty();
    }

    @Test
    void savedAddressIdOnly_isValid() {
        CreateOrderDto dto = baseDto();
        dto.setShippingAddress(null);
        dto.setSavedAddressId(42L);

        Set<ConstraintViolation<CreateOrderDto>> violations = validator.validate(dto);

        assertThat(violations).isEmpty();
    }

    @Test
    void bothProvided_isInvalid() {
        CreateOrderDto dto = baseDto();
        dto.setShippingAddress(validAddress());
        dto.setSavedAddressId(42L);

        Set<ConstraintViolation<CreateOrderDto>> violations = validator.validate(dto);

        assertThat(violations).isNotEmpty();
    }

    @Test
    void neitherProvided_isInvalid() {
        CreateOrderDto dto = baseDto();
        dto.setShippingAddress(null);
        dto.setSavedAddressId(null);

        Set<ConstraintViolation<CreateOrderDto>> violations = validator.validate(dto);

        assertThat(violations).isNotEmpty();
    }

    private CreateOrderDto baseDto() {
        CreateOrderDto dto = new CreateOrderDto();
        OrderItemRequestDto item = new OrderItemRequestDto();
        item.setListingId(1L);
        item.setQuantity(1);
        dto.setItems(List.of(item));
        return dto;
    }

    private ShippingAddressDto validAddress() {
        ShippingAddressDto address = new ShippingAddressDto();
        address.setFirstName("Jane");
        address.setLastName("Doe");
        address.setStreet("Hauptstrasse");
        address.setHouseNumber("1");
        address.setCity("Berlin");
        address.setPostalCode("10115");
        address.setCountry("DE");
        return address;
    }
}
```

Create `backend/src/test/java/com/enunas/backend/order/integration/CheckoutAddressIntegrationTest.java`:

```java
package com.enunas.backend.order.integration;

import com.enunas.backend.discount.integration.AbstractDiscountIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CheckoutAddressIntegrationTest extends AbstractDiscountIntegrationTest {

    @Test
    void checkout_withSavedAddressId_usesThatAddress() {
        var brand = seedBrand("BrandA", "brand-a", "0.18");
        long listing = seedListing(brand.brand(), brand.user(), "50.00", 10);
        seedCustomer();
        String token = login("customer@it.local", "Customer123!");

        long addressId = createSavedAddress(token, "Jane", "Doe");

        Map<String, Object> body = new HashMap<>();
        body.put("items", List.of(item(listing, 1)));
        body.put("savedAddressId", addressId);
        ResponseEntity<Map> resp = rest.exchange("/orders", HttpMethod.POST,
                new HttpEntity<>(body, auth(token)), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        Map shippingAddress = (Map) resp.getBody().get("shippingAddress");
        assertThat(shippingAddress.get("firstName")).isEqualTo("Jane");
        assertThat(shippingAddress.get("country")).isEqualTo("DE");
    }

    @Test
    void checkout_withInlineAddress_stillWorks() {
        var brand = seedBrand("BrandA", "brand-a", "0.18");
        long listing = seedListing(brand.brand(), brand.user(), "50.00", 10);
        seedCustomer();
        String token = login("customer@it.local", "Customer123!");

        Map<String, Object> body = new HashMap<>();
        body.put("items", List.of(item(listing, 1)));
        body.put("shippingAddress", Map.of(
                "firstName", "Jane", "lastName", "Doe", "street", "Hauptstrasse",
                "houseNumber", "1", "city", "Berlin", "postalCode", "10115", "country", "DE"));
        ResponseEntity<Map> resp = rest.exchange("/orders", HttpMethod.POST,
                new HttpEntity<>(body, auth(token)), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void checkout_withBothAddressSources_badRequest() {
        var brand = seedBrand("BrandA", "brand-a", "0.18");
        long listing = seedListing(brand.brand(), brand.user(), "50.00", 10);
        seedCustomer();
        String token = login("customer@it.local", "Customer123!");
        long addressId = createSavedAddress(token, "Jane", "Doe");

        Map<String, Object> body = new HashMap<>();
        body.put("items", List.of(item(listing, 1)));
        body.put("savedAddressId", addressId);
        body.put("shippingAddress", Map.of(
                "firstName", "Jane", "lastName", "Doe", "street", "Hauptstrasse",
                "houseNumber", "1", "city", "Berlin", "postalCode", "10115", "country", "DE"));
        ResponseEntity<Map> resp = rest.exchange("/orders", HttpMethod.POST,
                new HttpEntity<>(body, auth(token)), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void checkout_withNeitherAddressSource_badRequest() {
        var brand = seedBrand("BrandA", "brand-a", "0.18");
        long listing = seedListing(brand.brand(), brand.user(), "50.00", 10);
        seedCustomer();
        String token = login("customer@it.local", "Customer123!");

        Map<String, Object> body = new HashMap<>();
        body.put("items", List.of(item(listing, 1)));
        ResponseEntity<Map> resp = rest.exchange("/orders", HttpMethod.POST,
                new HttpEntity<>(body, auth(token)), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void checkout_withAnotherCustomersSavedAddressId_notFound() {
        var brand = seedBrand("BrandA", "brand-a", "0.18");
        long listing = seedListing(brand.brand(), brand.user(), "50.00", 10);
        seedCustomer();
        String ownerToken = login("customer@it.local", "Customer123!");
        long addressId = createSavedAddress(ownerToken, "Jane", "Doe");

        seedUser("other@it.local", "Other123!", com.enunas.backend.user.Role.CUSTOMER);
        customerRepository.save(com.enunas.backend.customer.Customer.builder()
                .user(userRepository.findByEmail("other@it.local").orElseThrow())
                .build());
        String otherToken = login("other@it.local", "Other123!");

        Map<String, Object> body = new HashMap<>();
        body.put("items", List.of(item(listing, 1)));
        body.put("savedAddressId", addressId);
        ResponseEntity<Map> resp = rest.exchange("/orders", HttpMethod.POST,
                new HttpEntity<>(body, auth(otherToken)), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void checkout_withSavedAddressInDisallowedCountry_badRequest() {
        var brand = seedBrand("BrandA", "brand-a", "0.18");
        long listing = seedListing(brand.brand(), brand.user(), "50.00", 10);
        seedCustomer();
        String token = login("customer@it.local", "Customer123!");

        Map<String, Object> frAddress = new HashMap<>();
        frAddress.put("firstName", "Jean");
        frAddress.put("lastName", "Dupont");
        frAddress.put("street", "Rue de Paris");
        frAddress.put("houseNumber", "1");
        frAddress.put("postalCode", "75001");
        frAddress.put("city", "Paris");
        frAddress.put("country", "FR");
        ResponseEntity<Map> created = rest.exchange("/customer/addresses", HttpMethod.POST,
                new HttpEntity<>(frAddress, auth(token)), Map.class);
        long addressId = ((Number) created.getBody().get("id")).longValue();

        Map<String, Object> body = new HashMap<>();
        body.put("items", List.of(item(listing, 1)));
        body.put("savedAddressId", addressId);
        ResponseEntity<Map> resp = rest.exchange("/orders", HttpMethod.POST,
                new HttpEntity<>(body, auth(token)), Map.class);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    private long createSavedAddress(String token, String firstName, String lastName) {
        Map<String, Object> body = new HashMap<>();
        body.put("firstName", firstName);
        body.put("lastName", lastName);
        body.put("street", "Hauptstrasse");
        body.put("houseNumber", "1");
        body.put("postalCode", "10115");
        body.put("city", "Berlin");
        body.put("country", "DE");
        ResponseEntity<Map> resp = rest.exchange("/customer/addresses", HttpMethod.POST,
                new HttpEntity<>(body, auth(token)), Map.class);
        return ((Number) resp.getBody().get("id")).longValue();
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw test -Dtest=CreateOrderDtoValidationTest`

Expected: compile failure — `setSavedAddressId` doesn't exist yet on `CreateOrderDto`.

- [ ] **Step 3: Write the exactly-one-of constraint**

Create `backend/src/main/java/com/enunas/backend/order/validation/ExactlyOneAddressSource.java`:

```java
package com.enunas.backend.order.validation;

import com.enunas.backend.order.dto.CreateOrderDto;
import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = ExactlyOneAddressSourceValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ExactlyOneAddressSource {

    String message() default "exactly one of savedAddressId or shippingAddress must be provided";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
```

Create `backend/src/main/java/com/enunas/backend/order/validation/ExactlyOneAddressSourceValidator.java`:

```java
package com.enunas.backend.order.validation;

import com.enunas.backend.order.dto.CreateOrderDto;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ExactlyOneAddressSourceValidator implements ConstraintValidator<ExactlyOneAddressSource, CreateOrderDto> {

    @Override
    public boolean isValid(CreateOrderDto dto, ConstraintValidatorContext context) {
        if (dto == null) {
            return true;
        }
        boolean hasSaved = dto.getSavedAddressId() != null;
        boolean hasInline = dto.getShippingAddress() != null;
        if (hasSaved == hasInline) { // both true (both given) or both false (neither given)
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                    .addPropertyNode("shippingAddress")
                    .addConstraintViolation();
            return false;
        }
        return true;
    }
}
```

- [ ] **Step 4: Update `CreateOrderDto`**

Replace `backend/src/main/java/com/enunas/backend/order/dto/CreateOrderDto.java` with:

```java
package com.enunas.backend.order.dto;

import com.enunas.backend.order.validation.ExactlyOneAddressSource;
import com.enunas.backend.validation.NoHtml;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
@ExactlyOneAddressSource
public class CreateOrderDto {

    @NotEmpty
    @Valid
    private List<OrderItemRequestDto> items;

    /**
     * A complete inline shipping address. Exactly one of this or {@code savedAddressId} must be
     * set — enforced by {@link ExactlyOneAddressSource}. Validated identically regardless of how
     * the frontend obtained it — this backend does not know or care whether it came from manual
     * entry, an autocomplete widget, or anything else.
     */
    @Valid
    private ShippingAddressDto shippingAddress;

    /** References one of the caller's saved {@code UserAddress} rows. */
    private Long savedAddressId;

    @Size(max = 1000)
    @NoHtml
    private String notes;

    /** Optional single discount code (max one per order — no stacking). */
    @Size(max = 32)
    @Pattern(regexp = "^[A-Za-z0-9_-]*$", message = "must contain only letters, digits, - and _")
    private String discountCode;
}
```

- [ ] **Step 5: Add the saved-address mapper to `ShippingAddressDto`**

Append this static factory method to the `ShippingAddressDto` class body (from Task 2), just before its closing `}`:

```java

    public static ShippingAddressDto from(com.enunas.backend.customer.UserAddress address) {
        ShippingAddressDto dto = new ShippingAddressDto();
        dto.setFirstName(address.getFirstName());
        dto.setLastName(address.getLastName());
        dto.setStreet(address.getStreet());
        dto.setHouseNumber(address.getHouseNumber());
        dto.setAddressLine2(address.getAddressLine2());
        dto.setPostalCode(address.getPostalCode());
        dto.setCity(address.getCity());
        dto.setCountry(address.getCountry());
        return dto;
    }
```

- [ ] **Step 6: Wire address resolution into `OrderService`**

In `backend/src/main/java/com/enunas/backend/order/OrderService.java`, add these imports:

```java
import com.enunas.backend.customer.UserAddress;
import com.enunas.backend.customer.UserAddressRepository;
import com.enunas.backend.exception.AddressNotFoundException;
import com.enunas.backend.order.dto.ShippingAddressDto;
import com.enunas.backend.order.validation.AllowedShippingCountries;
```

Add a new field to the `@RequiredArgsConstructor`-injected field list (alongside the other `private final` fields):

```java
    private final UserAddressRepository userAddressRepository;
```

Find the address-building block (updated in Task 2):

```java
        ShippingAddress address = ShippingAddress.builder()
                .firstName(dto.getShippingAddress().getFirstName())
                .lastName(dto.getShippingAddress().getLastName())
                .street(dto.getShippingAddress().getStreet())
                .houseNumber(dto.getShippingAddress().getHouseNumber())
                .addressLine2(dto.getShippingAddress().getAddressLine2())
                .city(dto.getShippingAddress().getCity())
                .postalCode(dto.getShippingAddress().getPostalCode())
                .country(dto.getShippingAddress().getCountry())
                .phone(dto.getShippingAddress().getPhone())
                .build();
```

Replace it with:

```java
        ShippingAddressDto resolvedAddress = resolveShippingAddress(dto, buyer);

        ShippingAddress address = ShippingAddress.builder()
                .firstName(resolvedAddress.getFirstName())
                .lastName(resolvedAddress.getLastName())
                .street(resolvedAddress.getStreet())
                .houseNumber(resolvedAddress.getHouseNumber())
                .addressLine2(resolvedAddress.getAddressLine2())
                .city(resolvedAddress.getCity())
                .postalCode(resolvedAddress.getPostalCode())
                .country(resolvedAddress.getCountry())
                .phone(resolvedAddress.getPhone())
                .build();
```

Then add this new private method to `OrderService` (near the other private helpers, e.g. next to `generateOrderNumber`):

```java
    /**
     * Resolves whichever address source the caller supplied (exactly one, enforced by
     * {@code @ExactlyOneAddressSource} at the DTO level) into a common {@link ShippingAddressDto}
     * shape. A saved address is loaded with an ownership check — a customer can never use another
     * customer's saved address — and re-checked against {@link AllowedShippingCountries}, since a
     * saved address's country was never restricted at save time (see {@code UserAddress} javadoc).
     */
    private ShippingAddressDto resolveShippingAddress(CreateOrderDto dto, User buyer) {
        if (dto.getSavedAddressId() != null) {
            UserAddress saved = userAddressRepository.findByIdAndUser(dto.getSavedAddressId(), buyer)
                    .orElseThrow(() -> new AddressNotFoundException(
                            "Saved address not found: " + dto.getSavedAddressId()));
            if (!AllowedShippingCountries.isAllowed(saved.getCountry())) {
                throw new IllegalArgumentException(
                        "Shipping to " + saved.getCountry() + " is not currently available");
            }
            return ShippingAddressDto.from(saved);
        }
        return dto.getShippingAddress();
    }
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `./mvnw test -Dtest=CreateOrderDtoValidationTest,CheckoutAddressIntegrationTest`

Expected: PASS (4/4 unit tests, 6/6 integration tests green)

- [ ] **Step 8: Run the full backend suite**

Run: `./mvnw test` (from `backend/`)

Expected: every test across the whole module passes, except the one pre-existing, unrelated `SettlementIntegrationTest.closedPeriodGuard_...` failure (date-boundary issue in the settlement module, present before this plan started, unconnected to address handling). Any other failure means something this plan touched broke a caller that wasn't in this plan's scope — find it and fix it before finishing.

- [ ] **Step 9: Leave the change in the working tree** (no commit — see banner)
