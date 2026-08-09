package com.enunas.backend.settlement.accounting.dto;

import com.enunas.backend.settlement.accounting.BookingType;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One EÜR-relevant booking line (spec §6), fully pre-computed — no calculation performed by the
 *  consumer (Norman or otherwise) is required. */
@Getter
@Builder
public class AccountingBookingLineDto {
    private final String settlementId;
    private final LocalDate bookingDate;
    private final BookingType bookingType;
    private final BigDecimal amount;
    private final String currency;
    /** Percentage, e.g. 19 for 19% — not a fraction. */
    private final BigDecimal vatRate;
    private final String description;
    private final String externalReference;
}
