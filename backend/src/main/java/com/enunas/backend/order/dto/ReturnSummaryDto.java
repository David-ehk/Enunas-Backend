package com.enunas.backend.order.dto;

import com.enunas.backend.order.ReturnLabelStatus;
import com.enunas.backend.order.ReturnOrder;
import com.enunas.backend.order.ReturnStatus;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * One brand's return within an order. A multi-brand order carries several of these, each with the
 * items that belong to that brand and the address THOSE items must be posted to — the customer has
 * to be able to tell which parcel goes where.
 */
@Getter
@Builder
public class ReturnSummaryDto {

    private Long id;
    private String returnNumber;
    private ReturnStatus status;
    private Long brandId;
    private String brandName;
    private String reason;
    private String description;
    /** Ship-to as frozen when the return was requested — never re-derived from the brand. */
    private String shipToAddress;
    private List<Long> orderItemIds;
    private BigDecimal refundAmount;
    private LocalDateTime requestedAt;
    private LocalDateTime approvedAt;
    private LocalDateTime receivedAt;
    private LocalDateTime refundedAt;

    private ReturnLabelStatus labelStatus;
    private String labelCarrier;
    private String labelTrackingNumber;
    private String labelUrl;

    public static ReturnSummaryDto from(ReturnOrder ret) {
        return ReturnSummaryDto.builder()
                .id(ret.getId())
                .returnNumber(ret.getReturnNumber())
                .status(ret.getStatus())
                .brandId(ret.getBrand() != null ? ret.getBrand().getId() : null)
                .brandName(ret.getBrand() != null ? ret.getBrand().getBrandName() : null)
                .reason(ret.getReason() != null ? ret.getReason().name() : null)
                .description(ret.getDescription())
                .shipToAddress(ret.getShipToFormatted())
                .orderItemIds(ret.getItems().stream()
                        .map(ri -> ri.getOrderItem().getId())
                        .toList())
                .refundAmount(ret.getRefundAmount())
                .requestedAt(ret.getRequestedAt())
                .approvedAt(ret.getApprovedAt())
                .receivedAt(ret.getReceivedAt())
                .refundedAt(ret.getRefundedAt())
                .labelStatus(ret.getLabelStatus())
                .labelCarrier(ret.getLabelCarrier())
                .labelTrackingNumber(ret.getLabelTrackingNumber())
                .labelUrl(ret.getLabelUrl())
                .build();
    }
}
