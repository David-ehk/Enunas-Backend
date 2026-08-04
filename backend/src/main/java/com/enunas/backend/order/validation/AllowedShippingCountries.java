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
