package com.enunas.backend.discount.dto;

import com.enunas.backend.discount.DiscountCode;
import com.enunas.backend.discount.DiscountType;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
public class DiscountResponseDto {

    private Long id;
    private String code;
    private DiscountType type;
    private BigDecimal percent;
    private Long brandId;
    private String brandName;
    private LocalDateTime validFrom;
    private LocalDateTime validUntil;
    private Integer maxUses;
    private Integer usedCount;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static DiscountResponseDto from(DiscountCode d) {
        return DiscountResponseDto.builder()
                .id(d.getId())
                .code(d.getCode())
                .type(d.getType())
                .percent(d.getPercent())
                .brandId(d.getBrand() != null ? d.getBrand().getId() : null)
                .brandName(d.getBrand() != null ? d.getBrand().getBrandName() : null)
                .validFrom(d.getValidFrom())
                .validUntil(d.getValidUntil())
                .maxUses(d.getMaxUses())
                .usedCount(d.getUsedCount())
                .active(d.isActive())
                .createdAt(d.getCreatedAt())
                .updatedAt(d.getUpdatedAt())
                .build();
    }
}
