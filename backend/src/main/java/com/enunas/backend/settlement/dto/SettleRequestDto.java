package com.enunas.backend.settlement.dto;

import lombok.Data;

/** Optional body for POST /admin/settlements/{brandId}/{period}. */
@Data
public class SettleRequestDto {
    /** Operator-supplied invoice/credit-note reference (e.g. "LEX-2026-06-001"). Optional. */
    private String invoiceReference;
}
