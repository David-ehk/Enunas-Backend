package com.enunas.backend.order.dto;

import com.enunas.backend.order.OrderShippingSnapshot;
import com.enunas.backend.shipping.ShippingCalculationMethod;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

@Getter
@Builder
public class ShippingSnapshotDto {

    private Long brandId;
    private String brandName;
    private BigDecimal amount;
    private String currency;
    private ShippingCalculationMethod calculationMethod;

    public static ShippingSnapshotDto from(OrderShippingSnapshot snapshot, String brandName) {
        return ShippingSnapshotDto.builder()
                .brandId(snapshot.getBrandPartnerId())
                .brandName(brandName)
                .amount(snapshot.getAmount())
                .currency(snapshot.getCurrency())
                .calculationMethod(snapshot.getCalculationMethod())
                .build();
    }
}
