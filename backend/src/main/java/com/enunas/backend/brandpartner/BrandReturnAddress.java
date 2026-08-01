package com.enunas.backend.brandpartner;

/**
 * Where a brand wants returned goods sent, resolved at a point in time.
 *
 * <p>This is the ONE place the fallback rule lives: a brand that has nominated a returns
 * destination gets it, and a brand that has not falls back to its §22f business address. Callers
 * must never reach for {@code brand.getAddressStreet()} directly for a return — that field is tax
 * master data and reading it as a shipping address is exactly the bug this type exists to prevent.
 *
 * <p>Resolving is deliberately separated from storing: {@code ReturnOrder} snapshots the resolved
 * values at request time, so a return already in flight keeps the address the customer was told
 * even after the brand moves warehouses.
 */
public record BrandReturnAddress(
        String recipient,
        String street,
        String postalCode,
        String city,
        String country,
        String instructions) {

    /** Shown when a brand has neither a nominated return address nor usable §22f master data. */
    public static final String UNAVAILABLE =
            "Bitte kontaktiere den Verkäufer für die Retourenadresse.";

    /**
     * Resolves the address to ship returns to for this brand. Falls back to the §22f business
     * address when no returns destination is nominated ({@code returnStreet} is the discriminator —
     * a nominated address without a street is not usable).
     */
    public static BrandReturnAddress of(BrandPartner brand) {
        if (brand == null) {
            return new BrandReturnAddress(null, null, null, null, null, null);
        }
        if (isBlank(brand.getReturnStreet())) {
            return new BrandReturnAddress(
                    brand.getLegalName(),
                    brand.getAddressStreet(),
                    brand.getAddressPostalCode(),
                    brand.getAddressCity(),
                    brand.getAddressCountry(),
                    null);
        }
        return new BrandReturnAddress(
                isBlank(brand.getReturnRecipient()) ? brand.getLegalName() : brand.getReturnRecipient(),
                brand.getReturnStreet(),
                brand.getReturnPostalCode(),
                brand.getReturnCity(),
                brand.getReturnCountry(),
                brand.getReturnInstructions());
    }

    /** True when this address is complete enough to actually route a parcel. */
    public boolean isRoutable() {
        return !isBlank(street) && !isBlank(city) && !isBlank(postalCode);
    }

    /**
     * Renders the plain-text block used in the return email and the in-app order view.
     * Returns {@link #UNAVAILABLE} when nothing usable is present.
     */
    public String formatted() {
        StringBuilder addr = new StringBuilder();
        if (!isBlank(recipient)) addr.append(recipient).append("\n");
        if (!isBlank(street)) addr.append(street).append("\n");
        if (!isBlank(postalCode) || !isBlank(city)) {
            if (!isBlank(postalCode)) addr.append(postalCode).append(" ");
            if (!isBlank(city)) addr.append(city);
            addr.append("\n");
        }
        if (!isBlank(country)) addr.append(country).append("\n");
        if (!isBlank(instructions)) addr.append("\n").append(instructions);
        String result = addr.toString().trim();
        return result.isEmpty() ? UNAVAILABLE : result;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
