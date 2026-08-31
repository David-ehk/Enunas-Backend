package com.enunas.backend.customer.dto;

import java.math.BigDecimal;

/**
 * One buyer's row from the batched stats query ({@code OrderRepository.getOrderStatsByBuyerIds}),
 * carrying the user id the aggregate was grouped by. {@link CustomerOrderStatsDto} is the shape the
 * API returns and has no id, since it is always rendered against a customer the caller already has.
 */
public record CustomerOrderStatsRow(Long userId, Long totalOrders, BigDecimal totalSpent) {}
