package com.enunas.backend.product.dto;

import java.math.BigDecimal;

/**
 * What a product card or detail page shows: the price charged, and the price it was reduced from.
 *
 * <p>{@code original} is null whenever the product is not on sale, so the storefront's rule is
 * simply "strike it through if it is there" — no comparison, and no risk of rendering an equal
 * pair as a discount. Both values always come from the same listing.
 */
public record DisplayPrice(BigDecimal current, BigDecimal original) {}
