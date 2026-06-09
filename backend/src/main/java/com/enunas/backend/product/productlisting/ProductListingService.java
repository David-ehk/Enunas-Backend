package com.enunas.backend.product.productlisting;

import com.enunas.backend.common.MoneyMath;
import com.enunas.backend.exception.ProductNotFoundException;
import com.enunas.backend.product.Product;
import com.enunas.backend.product.ProductRepository;
import com.enunas.backend.product.productlisting.dto.CreateListingDto;
import com.enunas.backend.product.productlisting.dto.ListingResponseDto;
import com.enunas.backend.product.productlisting.dto.UpdateListingDto;
import com.enunas.backend.product.productvariant.ProductVariant;
import com.enunas.backend.product.productvariant.ProductVariantRepository;
import com.enunas.backend.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductListingService {

    private final ProductListingRepository productListingRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;

    @Value("${enunas.vat.product-rate:0.19}")
    private BigDecimal vatRateProduct;

    @Transactional
    public ListingResponseDto createListing(Long productId, CreateListingDto dto, User creator) {
        Product product = findProductAndVerifyOwnership(productId, creator);
        ProductVariant variant = variantRepository.findById(dto.getVariantId())
                .orElseThrow(() -> new ProductNotFoundException("Variant not found with id: " + dto.getVariantId()));

        if (!variant.getProduct().getId().equals(product.getId())) {
            throw new IllegalArgumentException("Variant does not belong to this product");
        }

        ProductListing productListing = ProductListing.builder()
                .product(product)
                .variant(variant)
                .priceInputMode(dto.getPriceInputMode())
                .currency(dto.getCurrency() != null ? dto.getCurrency() : "EUR")
                .region(dto.getRegion())
                .dropDate(dto.getDropDate())
                .availableFrom(dto.getAvailableFrom())
                .availableUntil(dto.getAvailableUntil())
                .build();

        // Derive the gross/net pair from the entered figure(s) under the chosen mode.
        setPricePair(productListing, dto.getPrice(), dto.getPriceInputMode(), false);
        if (dto.getDiscountPrice() != null) {
            setPricePair(productListing, dto.getDiscountPrice(), dto.getPriceInputMode(), true);
        }

        return ListingResponseDto.from(productListingRepository.save(productListing));
    }

    @Transactional(readOnly = true)
    public ListingResponseDto getListingById(Long listingId) {
        return ListingResponseDto.from(findById(listingId));
    }

    @Transactional(readOnly = true)
    public List<ListingResponseDto> getListingsByProduct(Long productId) {
        return productListingRepository.findByProductId(productId).stream()
                .map(ListingResponseDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ListingResponseDto> getActiveListingsByProduct(Long productId) {
        return productListingRepository.findByProductIdAndActive(productId, true).stream()
                .map(ListingResponseDto::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ListingResponseDto> getActiveListingsByRegion(String region) {
        return productListingRepository.findByRegionAndActive(region, true).stream()
                .map(ListingResponseDto::from)
                .toList();
    }

    @Transactional
    public ListingResponseDto updateListing(Long productId, Long listingId, UpdateListingDto dto, User creator) {
        findProductAndVerifyOwnership(productId, creator);
        ProductListing productListing = findById(listingId);

        // Recompute the gross/net pair whenever a price field OR the input mode changes, so a
        // partial update never leaves a stale net that disagrees with its gross.
        PriceInputMode oldMode = productListing.getPriceInputMode();
        PriceInputMode effectiveMode = dto.getPriceInputMode() != null ? dto.getPriceInputMode() : oldMode;
        boolean modeChanged = dto.getPriceInputMode() != null && dto.getPriceInputMode() != oldMode;

        if (dto.getPriceInputMode() != null) productListing.setPriceInputMode(effectiveMode);

        if (dto.getPrice() != null) {
            setPricePair(productListing, dto.getPrice(), effectiveMode, false);
        } else if (modeChanged) {
            // Reinterpret the originally-entered figure under the new mode.
            setPricePair(productListing, enteredValue(productListing, oldMode, false), effectiveMode, false);
        }

        if (dto.getDiscountPrice() != null) {
            setPricePair(productListing, dto.getDiscountPrice(), effectiveMode, true);
        } else if (modeChanged && productListing.getDiscountPrice() != null) {
            setPricePair(productListing, enteredValue(productListing, oldMode, true), effectiveMode, true);
        }

        if (dto.getActive() != null) productListing.setActive(dto.getActive());
        if (dto.getRegion() != null) productListing.setRegion(dto.getRegion());
        if (dto.getDropDate() != null) productListing.setDropDate(dto.getDropDate());
        if (dto.getAvailableFrom() != null) productListing.setAvailableFrom(dto.getAvailableFrom());
        if (dto.getAvailableUntil() != null) productListing.setAvailableUntil(dto.getAvailableUntil());

        return ListingResponseDto.from(productListingRepository.save(productListing));
    }

    /**
     * Sets the gross + net pair on the listing from a single entered figure interpreted under
     * {@code mode}. NET ⇒ gross is derived; GROSS ⇒ net is derived. Defaults to GROSS if the
     * mode is somehow absent (legacy rows). {@code discount=true} targets the sale-price columns.
     */
    private void setPricePair(ProductListing listing, BigDecimal entered, PriceInputMode mode, boolean discount) {
        BigDecimal gross;
        BigDecimal net;
        if (mode == PriceInputMode.NET) {
            net = MoneyMath.round2(entered);
            gross = MoneyMath.grossFromNet(entered, vatRateProduct);
        } else {
            gross = MoneyMath.round2(entered);
            net = MoneyMath.netFromGross(entered, vatRateProduct);
        }
        if (discount) {
            listing.setDiscountPrice(gross);
            listing.setDiscountPriceNet(net);
        } else {
            listing.setPrice(gross);
            listing.setPriceNet(net);
        }
    }

    /** The figure the brand originally entered, recovered from the stored pair under {@code mode}. */
    private BigDecimal enteredValue(ProductListing listing, PriceInputMode mode, boolean discount) {
        if (mode == PriceInputMode.NET) {
            return discount ? listing.getDiscountPriceNet() : listing.getPriceNet();
        }
        return discount ? listing.getDiscountPrice() : listing.getPrice();
    }

    @Transactional
    public void deleteListing(Long productId, Long listingId, User creator) {
        findProductAndVerifyOwnership(productId, creator);
        productListingRepository.delete(findById(listingId));
    }

    private ProductListing findById(Long listingId) {
        return productListingRepository.findById(listingId)
                .orElseThrow(() -> new ProductNotFoundException("Listing not found with id: " + listingId));
    }

    private Product findProductAndVerifyOwnership(Long productId, User creator) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException("Product not found with id: " + productId));
        if (!product.getCreator().getId().equals(creator.getId())) {
            throw new SecurityException("You do not own this product");
        }
        return product;
    }
}
