package com.enunas.backend.settlement.accounting.dto;

import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
public class UpsertAccountingInputDto {
    private BigDecimal mollieFees;
    private Boolean mollieFeesIncludedInActualPayout;
    private String payoutReference;
    private LocalDate mollieSettlementDate;
    private String notes;
}
