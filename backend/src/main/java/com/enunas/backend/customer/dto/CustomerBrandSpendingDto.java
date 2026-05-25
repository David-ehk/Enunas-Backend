package com.enunas.backend.customer.dto;

import java.math.BigDecimal;

public record CustomerBrandSpendingDto(
        String brandName,
        BigDecimal totalSpent,
        Long itemCount
) {}
