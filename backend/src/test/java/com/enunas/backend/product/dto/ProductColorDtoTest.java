package com.enunas.backend.product.dto;

import com.enunas.backend.product.productvariant.ColorFamily;
import com.enunas.backend.product.productvariant.ProductColor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductColorDtoTest {

    @Test
    void from_mapsAllFields() {
        ProductColor colour = ProductColor.builder()
                .id(3L).sku("ABC123").color("Black").colorFamily(ColorFamily.BLACK).build();

        ProductColorDto dto = ProductColorDto.from(colour);

        assertThat(dto.getId()).isEqualTo(3L);
        assertThat(dto.getSku()).isEqualTo("ABC123");
        assertThat(dto.getColor()).isEqualTo("Black");
        assertThat(dto.getColorFamily()).isEqualTo(ColorFamily.BLACK);
    }
}
