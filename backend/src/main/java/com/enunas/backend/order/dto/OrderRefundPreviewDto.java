package com.enunas.backend.order.dto;

import java.math.BigDecimal;
import java.util.List;

/** Every return of the order that is not yet refunded, previewed; {@code total} is their sum. */
public record OrderRefundPreviewDto(Long orderId, String orderNumber, List<RefundPreviewDto> returns,
                                    BigDecimal total) {}
