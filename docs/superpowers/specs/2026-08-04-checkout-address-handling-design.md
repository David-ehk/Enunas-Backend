# Checkout Address Handling — Design

## Context

This is sub-project 1 of 2 decomposed from a combined "Google OAuth + checkout security" request. Sub-project 2 (Google OAuth authentication) is a separate, independent design and implementation cycle, not covered here.

Today, `OrderController.createOrder` already requires an authenticated `CUSTOMER` (`@PreAuthorize` + `@AuthenticationPrincipal User buyer`) and `OrderService.createOrder` already builds a fresh `ShippingAddress` embeddable from the request DTO at order-creation time, never re-reading a live address afterward. So "checkout requires auth" and "orders snapshot their address immutably" are **already true** — this design does not change that flow's sequencing (authenticate → validate cart/stock/prices → create order → build address snapshot → create Mollie payment stays exactly as-is). It changes *what the address looks like* and *where it can come from*.

## Goals

1. Persisted, self-service saved addresses (`UserAddress`) for customers.
2. Address field shape matching German checkout needs: `firstName`/`lastName`/`street`/`houseNumber`/`postalCode`/`city`/`country`, plus optional `addressLine2`/`phone`.
3. Server-side validation that never trusts frontend-supplied address data, regardless of how the frontend obtained it (manual entry, autocomplete, or any future source) — country restricted to an easily-extensible allow-list, postal code restricted to German 5-digit format.
4. Checkout accepts a saved address by reference or a fresh inline address — never trusting either without full validation, and always snapshotting into the order at creation time.

## Non-goals

- Changing the cart/stock/price validation logic in `OrderService` (already correct, untouched).
- Multi-country shipping support (structured so it's a one-line config change later, not built now).
- Address geocoding, deliverability verification, or any third-party address-validation API call.

## Architecture

### `UserAddress` — new entity, customer-owned saved addresses

New table `user_addresses`. Full CRUD via a new `UserAddressController` under `/customer/addresses`, `@PreAuthorize("hasRole('CUSTOMER')")`, following the exact ownership pattern `CustomerController`/`CustomerService` already use.

**`UserAddress` is a customer convenience feature only. Business documents (orders, invoices, returns, shipments, refunds) always use the immutable Order `ShippingAddress` snapshot and must never read from `UserAddress` after order creation.**

Endpoints:
- `GET /customer/addresses` — list the caller's saved addresses.
- `POST /customer/addresses` — create one. The customer's first saved address auto-becomes `isDefault = true`.
- `PUT /customer/addresses/{id}` — full replace (ownership-checked; 404 if not the caller's). Every field is required on this DTO — same validation shape as create — not a partial patch: an address is a value object, so "keep some fields, replace others" has no clean semantic here, unlike `UpdateCustomerProfileDto`'s partial-patch style elsewhere in this codebase.
- `DELETE /customer/addresses/{id}` — delete (ownership-checked). If the deleted address was the caller's default, no other address is auto-promoted to default — the caller simply has no default until they set one explicitly. Simplest behavior, avoids surprising the customer with a silent default change.
- `POST /customer/addresses/{id}/default` — set as default; service unsets `isDefault` on the caller's other rows in the same transaction (application-level invariant, matching this codebase's existing style — no DB partial-unique-index).

### `ShippingAddressDto` / `ShippingAddress` — restructured order-time address

Fields: `firstName`, `lastName`, `street`, `houseNumber`, `addressLine2` (optional), `postalCode`, `city`, `country`, `phone` (optional). Drops `fullName` and `state` (Germany doesn't use states for shipping).

`country` stays `private String country` — no enum, no DB constraint. Checkout-allowed values are enforced by a new `AllowedShippingCountries` constants class (`Set.of("DE")` today) behind a custom `@ValidShippingCountry` bean-validation constraint, mirroring the existing `@ValidCatalogueCategory` custom-validator pattern in `com.enunas.backend.product.validation`. Expanding to Austria/DACH/EU later is a one-line edit to that `Set` — never a migration, never a code change to the DTO itself.

### Checkout integration

`CreateOrderDto` accepts either a complete inline shipping address supplied by the frontend, or a `savedAddressId` referencing one of the caller's `UserAddress` rows. The backend does not know or care how the frontend obtained the address — it validates whatever arrives, the same way either way.

**API contract: exactly one of `savedAddressId` or `shippingAddress` must be supplied. Supplying both or neither is a validation error (HTTP 400).** Enforced via a class-level custom constraint on `CreateOrderDto` (same convention again).

`OrderService.createOrder` gains one resolution step before the existing snapshot-build: resolve the address — either bean-validate the inline `shippingAddress`, or load-and-ownership-check the `UserAddress` by `savedAddressId` and map it into the same shape — then build the `ShippingAddress` embeddable exactly as it does today. Everything downstream (payment creation, ordering, transaction boundaries) is untouched.

## Data model & migration (`V18`)

New table `user_addresses`:
```
id, user_id (FK -> users), first_name, last_name, street, house_number,
address_line2, postal_code, city, country, is_default, created_at, updated_at
```

`orders` table: **add** `first_name`, `last_name`, `house_number` (all nullable). Best-effort backfill for existing rows: split existing `full_name` on the first space into `first_name`/`last_name`. **Do not drop** `full_name` or `state` — leave them in place, unused by new code going forward, rather than risk a lossy destructive migration on free-text data. A later cleanup migration can drop them once confirmed safe. The existing `street2` column is kept, but the Java field is renamed `addressLine2` (`@Column(name = "street2")`) to match the DTO — no DB rename, `phone` column is unchanged in both name and mapping.

The DE-only + 5-digit-postal-code rule is enforced **only** by bean validation on the incoming DTO, never as a DB CHECK constraint — so historical non-DE order rows remain readable and valid.

## Validation rules

Both `UserAddress`-backing DTOs and `ShippingAddressDto`:
- `firstName`, `lastName`, `street`, `city`: `@NotBlank @Size(max=100) @NoHtml`.
- `houseNumber`: `@NotBlank @Size(max=16) @Pattern(regexp="^[A-Za-z0-9 /-]{1,16}$")` — German house numbers aren't purely numeric (`"12a"`, `"12-14"`, `"3/2"`).
- `addressLine2` (optional): `@Size(max=255) @NoHtml`.
- `postalCode`: `@NotBlank @Pattern(regexp="^\\d{5}$")` (German 5-digit format, per spec).
- `country`: `@NotBlank @ValidShippingCountry` (custom constraint backed by `AllowedShippingCountries`).
- `phone` (optional): reuses the existing `@Size(max=30) @Pattern` from the prior validation-hardening work.

All new/changed DTOs reuse `com.enunas.backend.validation.NoHtml` (already shipped) rather than redefining anything.

## Testing

- Bean-validation unit tests for the new/changed DTOs — direct `Validation.buildDefaultValidatorFactory()` usage, no Spring context, matching the style already established in this codebase's DTO validation tests.
- Unit tests for `@ValidShippingCountry` / `AllowedShippingCountries` and the `CreateOrderDto` exactly-one-of constraint (both-supplied and neither-supplied cases).
- Integration test(s) covering both checkout paths (inline address, saved-address-by-id) end to end, and the ownership check (one customer cannot checkout using another customer's saved address).
- `UserAddressController` CRUD covered by tests mirroring the existing `CustomerController`/`CustomerService` test conventions, including the default-address auto-set-on-first-create and default-reassignment-on-set-default behaviors.

## Open items intentionally deferred (not in scope for this plan)

- Dropping the legacy `full_name`/`state` columns from `orders` (future cleanup migration).
- Multi-country shipping (structurally ready via `AllowedShippingCountries`, not activated).
- Address geocoding/deliverability verification against a third-party API.
