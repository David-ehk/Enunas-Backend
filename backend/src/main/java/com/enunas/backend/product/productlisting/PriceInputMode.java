package com.enunas.backend.product.productlisting;

/**
 * Declares whether a brand-entered listing price is a NET or GROSS figure, so the
 * net/gross pair can be derived unambiguously at listing creation. VAT is always
 * {@code gross − net}; it is never stored as a separate field.
 */
public enum PriceInputMode {
    NET, GROSS
}
