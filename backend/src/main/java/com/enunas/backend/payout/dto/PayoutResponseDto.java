package com.enunas.backend.payout.dto;

import com.enunas.backend.payout.Payout;
import com.enunas.backend.payout.PayoutStatus;
import com.enunas.backend.payout.PayoutType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PayoutResponseDto(
        Long id,
        Long brandPartnerId,
        PayoutType type,
        BigDecimal amount,
        BigDecimal debtAbsorbed,
        PayoutStatus status,
        String iban,
        String bankAccountHolder,
        String currency,
        LocalDateTime createdAt,
        LocalDateTime approvedAt,
        String approvedByAdminEmail,
        LocalDateTime paidAt,
        String paidByAdminEmail,
        String externalReference
) {
    public static PayoutResponseDto from(Payout p) {
        return new PayoutResponseDto(
                p.getId(), p.getBrandPartnerId(), p.getType(), p.getAmount(), p.getDebtAbsorbed(),
                p.getStatus(), p.getIban(), p.getBankAccountHolder(), p.getCurrency(),
                p.getCreatedAt(), p.getApprovedAt(), p.getApprovedByAdminEmail(),
                p.getPaidAt(), p.getPaidByAdminEmail(), p.getExternalReference()
        );
    }
}
