package com.enunas.backend.settlement.accounting.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
public class UpsertAccountingInputDto {

    @DecimalMin(value = "0.00", message = "mollieFees must not be negative")
    private BigDecimal mollieFees;

    private Boolean mollieFeesIncludedInActualPayout;

    @Size(max = 100, message = "payoutReference must not exceed 100 characters")
    private String payoutReference;

    private LocalDate mollieSettlementDate;

    @Size(max = 1000, message = "notes must not exceed 1000 characters")
    private String notes;
}
