package com.enunas.backend.product;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that every ProductType maps to exactly one OutfitSlot, and that
 * the @PrePersist computation in Product.computeOutfitSlot() is correct.
 */
class OutfitSlotMappingTest {

    @ParameterizedTest
    @EnumSource(value = ProductType.class, names = {"T_SHIRT", "LONGSLEEVE", "HOODIE", "ZIP_HOODIE", "SWEATER"})
    void tops_mapTo_TOP(ProductType type) {
        assertThat(buildProduct(type).getOutfitSlot()).isEqualTo(OutfitSlot.TOP);
    }

    @ParameterizedTest
    @EnumSource(value = ProductType.class, names = {"JEANS", "CARGO_PANTS", "JOGGER", "SHORTS", "PANTS"})
    void bottoms_mapTo_BOTTOM(ProductType type) {
        assertThat(buildProduct(type).getOutfitSlot()).isEqualTo(OutfitSlot.BOTTOM);
    }

    @Test
    void jacket_mapsTo_OUTERWEAR() {
        assertThat(buildProduct(ProductType.JACKET).getOutfitSlot()).isEqualTo(OutfitSlot.OUTERWEAR);
    }

    @ParameterizedTest
    @EnumSource(value = ProductType.class, names = {"SNEAKERS", "BOOTS"})
    void footwear_mapsTo_FOOTWEAR(ProductType type) {
        assertThat(buildProduct(type).getOutfitSlot()).isEqualTo(OutfitSlot.FOOTWEAR);
    }

    @ParameterizedTest
    @EnumSource(value = ProductType.class, names = {"CAP", "BEANIE", "BAG", "BELT", "JEWELRY"})
    void accessories_mapTo_ACCESSORY(ProductType type) {
        assertThat(buildProduct(type).getOutfitSlot()).isEqualTo(OutfitSlot.ACCESSORY);
    }

    @Test
    void everyProductType_hasMappedOutfitSlot() {
        for (ProductType type : ProductType.values()) {
            assertThat(buildProduct(type).getOutfitSlot())
                .as("OutfitSlot must be non-null for ProductType %s", type)
                .isNotNull();
        }
    }

    // Triggers @PrePersist via the callback method accessible via reflection,
    // or we can invoke the private method indirectly by calling the entity's
    // @PrePersist-annotated method via test helper below.
    private Product buildProduct(ProductType type) {
        Product p = Product.builder().productType(type).name("test").build();
        // Simulate @PrePersist to populate outfitSlot
        simulatePrePersist(p);
        return p;
    }

    private void simulatePrePersist(Product p) {
        try {
            var method = Product.class.getDeclaredMethod("onCreate");
            method.setAccessible(true);
            method.invoke(p);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke @PrePersist", e);
        }
    }
}
