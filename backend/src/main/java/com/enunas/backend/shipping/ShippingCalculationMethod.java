package com.enunas.backend.shipping;

/** Which rule produced a shipping amount — persisted on every {@code OrderShippingSnapshot} so
 * the reason is a first-class, auditable fact rather than something inferred from the number. */
public enum ShippingCalculationMethod {
    /** No brand-specific configuration found; the platform default rate was used. */
    GLOBAL_DEFAULT,
    /** The brand has an explicit, positive flat rate. */
    BRAND_FLAT_RATE,
    /** The brand has an explicit {@code 0.00} rate — deliberate free shipping. */
    BRAND_FREE_SHIPPING
}
