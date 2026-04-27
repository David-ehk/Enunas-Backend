package com.enunas.backend.product.productlisting;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductListingService {

    private final ProductListingRepository productListingRepository;
    private final ProductRepository productRepository;
    private final ProductVariantRepository variantRepository;

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
                .price(dto.getPrice())
                .discountPrice(dto.getDiscountPrice())
                .shippingCost(dto.getShippingCost())
                .currency(dto.getCurrency() != null ? dto.getCurrency() : "EUR")
                .stock(dto.getStock())
                .region(dto.getRegion())
                .dropDate(dto.getDropDate())
                .availableFrom(dto.getAvailableFrom())
                .availableUntil(dto.getAvailableUntil())
                .build();

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

        if (dto.getPrice() != null) productListing.setPrice(dto.getPrice());
        if (dto.getDiscountPrice() != null) productListing.setDiscountPrice(dto.getDiscountPrice());
        if (dto.getShippingCost() != null) productListing.setShippingCost(dto.getShippingCost());
        if (dto.getStock() != null) productListing.setStock(dto.getStock());
        if (dto.getActive() != null) productListing.setActive(dto.getActive());
        if (dto.getRegion() != null) productListing.setRegion(dto.getRegion());
        if (dto.getDropDate() != null) productListing.setDropDate(dto.getDropDate());
        if (dto.getAvailableFrom() != null) productListing.setAvailableFrom(dto.getAvailableFrom());
        if (dto.getAvailableUntil() != null) productListing.setAvailableUntil(dto.getAvailableUntil());

        return ListingResponseDto.from(productListingRepository.save(productListing));
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
