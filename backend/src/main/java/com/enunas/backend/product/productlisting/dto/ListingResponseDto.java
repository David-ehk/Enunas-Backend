package com.enunas.backend.product.productlisting.dto;

import com.enunas.backend.product.productlisting.PriceInputMode;
import com.enunas.backend.product.productlisting.ProductListing;
import com.enunas.backend.product.productvariant.ColorFamily;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
public class ListingResponseDto {

    private Long id;
    private Long productId;
    private String productName;
    private Long variantId;
    private String variantSku;
    private String variantColor;
    private ColorFamily variantColorFamily;
    private String variantSize;
    private Long colorId;
    /** Live stock pulled from the variant (the single source of truth). */
    private int variantStockQuantity;
    /**
     * What a customer pays for THIS listing, and the price to strike through.
     *
     * <p>Same contract as {@code ProductResponseDto.originalPrice}: {@code originalPrice} is null
     * unless there is an actual reduction, so "on sale" is exactly "originalPrice is present".
     * These exist so the rule for deciding that — {@code discountPrice} counts only when it is
     * non-null AND greater than zero, see {@link ProductListing#getCurrentPrice()} — lives here
     * rather than being reimplemented by every client reading the raw pair below.
     *
     * <p>Note the naming, which differs from {@code ProductResponseDto} by necessity: there,
     * {@code price} IS the charged amount. Here {@code price} is the list price, because it has
     * meant that since before this DTO had a charged-amount field, and renaming it would break
     * every existing consumer. On this DTO, read {@code currentPrice}.
     *
     * <p>Use these per variant. The product-level pair is the cheapest sellable listing across the
     * whole product — right for a card, wrong once the customer has picked a colour and size.
     */
    private BigDecimal currentPrice;
    private BigDecimal originalPrice;

    // price/discountPrice are the GROSS (customer-facing) figures, kept for API stability.
    // The *Net / *Vat fields expose the Netto · USt · Brutto breakdown (vat = gross − net).
    private BigDecimal price;
    private BigDecimal discountPrice;
    private PriceInputMode priceInputMode;
    private BigDecimal priceNet;
    private BigDecimal priceGross;
    private BigDecimal priceVat;
    private BigDecimal discountPriceNet;
    private BigDecimal discountPriceGross;
    private BigDecimal discountPriceVat;
    private String currency;
    private boolean active;
    private String region;
    private LocalDateTime dropDate;
    private LocalDateTime availableFrom;
    private LocalDateTime availableUntil;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ListingResponseDto from(ProductListing productListing) {
        return ListingResponseDto.builder()
                .id(productListing.getId())
                .productId(productListing.getProduct().getId())
                .productName(productListing.getProduct().getName())
                .variantId(productListing.getVariant().getId())
                .variantSku(productListing.getVariant().getSku())
                .variantColor(productListing.getVariant().getColor())
                .variantColorFamily(productListing.getVariant().getColorFamily())
                .variantSize(productListing.getVariant().getSize())
                .colorId(productListing.getVariant().getProductColor() != null
                        ? productListing.getVariant().getProductColor().getId() : null)
                .variantStockQuantity(productListing.getVariant().getStockQuantity())
                .currentPrice(productListing.getCurrentPrice())
                .originalPrice(isDiscounted(productListing) ? productListing.getPrice() : null)
                .price(productListing.getPrice())
                .discountPrice(productListing.getDiscountPrice())
                .priceInputMode(productListing.getPriceInputMode())
                .priceNet(productListing.getPriceNet())
                .priceGross(productListing.getPrice())
                .priceVat(vat(productListing.getPrice(), productListing.getPriceNet()))
                .discountPriceNet(productListing.getDiscountPriceNet())
                .discountPriceGross(productListing.getDiscountPrice())
                .discountPriceVat(vat(productListing.getDiscountPrice(), productListing.getDiscountPriceNet()))
                .currency(productListing.getCurrency())
                .active(productListing.isActive())
                .region(productListing.getRegion())
                .dropDate(productListing.getDropDate())
                .availableFrom(productListing.getAvailableFrom())
                .availableUntil(productListing.getAvailableUntil())
                .createdAt(productListing.getCreatedAt())
                .updatedAt(productListing.getUpdatedAt())
                .build();
    }

    /**
     * True only when {@link ProductListing#getCurrentPrice()} is an actual reduction. Derived by
     * comparing against the list price rather than by re-testing discountPrice, so this cannot
     * drift from whatever getCurrentPrice() decides — including its {@code > 0} guard, which is
     * what stops a zero discountPrice being advertised as a sale.
     */
    private static boolean isDiscounted(ProductListing listing) {
        BigDecimal current = listing.getCurrentPrice();
        return current != null && listing.getPrice() != null
                && current.compareTo(listing.getPrice()) < 0;
    }

    /** VAT = gross − net; null when either side is missing (legacy listings without a stored net). */
    private static BigDecimal vat(BigDecimal gross, BigDecimal net) {
        return (gross != null && net != null) ? gross.subtract(net) : null;
    }
}
