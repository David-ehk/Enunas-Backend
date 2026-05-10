package com.enunas.backend.payout.dto;

import java.math.BigDecimal;
import java.util.List;

public record PayoutDashboardDto(
        long pendingCount,
        BigDecimal pendingTotal,
        long approvedCount,
        BigDecimal approvedTotal,
        long paidCount,
        BigDecimal paidTotal,
        List<NegativeBalanceBrand> negativeBrands
) {
    public record NegativeBalanceBrand(
            Long brandPartnerId,
            BigDecimal outstandingDebt,
            BigDecimal payoutBalance
    ) {}
}
