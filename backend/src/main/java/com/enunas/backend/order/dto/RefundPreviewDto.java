package com.enunas.backend.order.dto;

import com.enunas.backend.order.ReturnRefundState;
import com.enunas.backend.order.ReturnStatus;

import java.math.BigDecimal;
import java.util.List;

/** What releasing a return's refund sends. {@code stored}: already released, so the amounts are the stored claim. */
public record RefundPreviewDto(String returnNumber, ReturnStatus returnStatus, ReturnRefundState refundState,
                               Long brandId, String brandName, List<Line> lines, BigDecimal itemsAmount,
                               BigDecimal shippingAmount, BigDecimal total, boolean stored) {

    public record Line(Long orderItemId, String productName, String color, String size, int unitsReturned,
                       int unitsOrdered, BigDecimal amount) {}
}
