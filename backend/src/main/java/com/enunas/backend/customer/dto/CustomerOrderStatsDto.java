package com.enunas.backend.customer.dto;

import java.math.BigDecimal;

/**
 * Computed on read from {@code Order} rows (see {@code OrderRepository.getOrderStatsByBuyer}) —
 * never stored. A denormalized totalOrders/totalSpent pair used to live on the Customer entity but
 * was never wired up to actually update; this has no drift to go stale in the first place.
 */
public record CustomerOrderStatsDto(Long totalOrders, BigDecimal totalSpent) {}
