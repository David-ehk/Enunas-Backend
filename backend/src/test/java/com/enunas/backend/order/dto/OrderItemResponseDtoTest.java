package com.enunas.backend.order.dto;

import com.enunas.backend.media.storage.MediaStorageProperties;
import com.enunas.backend.media.storage.MediaUrlResolver;
import com.enunas.backend.order.OrderItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class OrderItemResponseDtoTest {

    private MediaUrlResolver resolver() {
        MediaStorageProperties properties = new MediaStorageProperties();
        properties.setCdnBaseUrl("https://cdn.it.local");
        return new MediaUrlResolver(properties);
    }

    @Test
    void from_snapshotKeyPresent_resolvesImageUrl() {
        OrderItem item = OrderItem.builder()
                .id(1L).productSnapshotName("Tee").variantSnapshotSku("ABC12345")
                .variantSnapshotColor("Black").variantSnapshotSize("M")
                .variantSnapshotImageKey("products/1/images/abc.jpg")
                .priceAtPurchase(new BigDecimal("19.99")).quantity(1)
                .lineTotal(new BigDecimal("19.99")).build();

        OrderItemResponseDto dto = OrderItemResponseDto.from(item, resolver());

        assertThat(dto.getImageUrl()).isEqualTo("https://cdn.it.local/products/1/images/abc.jpg");
    }

    @Test
    void from_noSnapshotKey_imageUrlIsNull() {
        OrderItem item = OrderItem.builder()
                .id(1L).productSnapshotName("Tee").variantSnapshotSku("ABC12345")
                .variantSnapshotColor("Black").variantSnapshotSize("M")
                .priceAtPurchase(new BigDecimal("19.99")).quantity(1)
                .lineTotal(new BigDecimal("19.99")).build();

        assertThat(OrderItemResponseDto.from(item, resolver()).getImageUrl()).isNull();
    }
}
