package com.enunas.backend.order.dto;

import com.enunas.backend.order.ReceivedBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** One row of the admin refund overview. {@code state}: DUE, IN_PROGRESS, PENDING, FAILED or REFUNDED. */
public record RefundOverviewRowDto(String returnNumber, String orderNumber, Long brandId, String brandName,
                                   String state, BigDecimal itemsAmount, BigDecimal shippingAmount,
                                   BigDecimal total, String releasedBy, String overrideReason, String claimKey,
                                   String refundTransactionId, LocalDateTime receivedAt, ReceivedBy receivedBy,
                                   LocalDateTime claimedAt, LocalDateTime refundedAt) {}
