package com.enunas.backend.settlement;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.BrandPartnerRepository;
import com.enunas.backend.exception.BrandNotFoundException;
import com.enunas.backend.exception.PeriodNotClosedException;
import com.enunas.backend.ledger.LedgerRepository;
import com.enunas.backend.settlement.dto.SettlementRowDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read-only monthly aggregation over the (already-frozen) ledger, plus the settlement marker that
 * freezes a period's figures and prevents a brand/month being paid out twice. No money-logic
 * redesign — every amount already lives on the ledger; this only sums what is present.
 */
@Service
@RequiredArgsConstructor
public class SettlementService {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");

    private final LedgerRepository ledgerRepository;
    private final SettlementRunRepository settlementRunRepository;
    private final BrandPartnerRepository brandPartnerRepository;

    /** Brands with activity in the period that are NOT yet settled. Default = last closed month. */
    @Transactional(readOnly = true)
    public List<SettlementRowDto> getUnsettled(String period) {
        YearMonth ym = (period == null || period.isBlank()) ? lastClosedMonth() : parsePeriod(period);
        Set<Long> settled = settlementRunRepository.findByPeriod(ym.toString()).stream()
                .map(SettlementRun::getBrandId)
                .collect(Collectors.toSet());
        return aggregate(ym).stream()
                .filter(row -> !settled.contains(row.getBrandId()))
                .toList();
    }

    /**
     * Freezes a brand's period figures into a settlement_run. Guards, in order:
     * 1) period format (→ 400), 2) period must be fully closed (→ 422), 3) not already settled (→ 409).
     */
    @Transactional
    public SettlementRowDto settle(Long brandId, String period, String invoiceReference) {
        YearMonth ym = parsePeriod(period);

        // Earliest settleable day is the 1st of the FOLLOWING month (Berlin) — not the month's last
        // day, otherwise a late entry on the 30th at 23:00 could be silently dropped after settling.
        LocalDate nextPeriodStart = ym.plusMonths(1).atDay(1);
        if (LocalDate.now(BERLIN).isBefore(nextPeriodStart)) {
            throw new PeriodNotClosedException("Nur abgeschlossene Monate können abgerechnet werden.");
        }
        if (settlementRunRepository.existsByBrandIdAndPeriod(brandId, ym.toString())) {
            throw new IllegalStateException(
                    "Settlement already exists for brand " + brandId + " period " + ym);
        }

        SettlementRowDto row = aggregateForBrand(brandId, ym);
        settlementRunRepository.save(SettlementRun.builder()
                .brandId(brandId)
                .period(ym.toString())
                .settledAt(LocalDateTime.now())
                .invoiceReference(invoiceReference)
                .commissionNet(row.getCommissionNet())
                .commissionVat(row.getCommissionVat())
                .payoutAmount(row.getPayoutAmount())
                .build());
        return row;
    }

    // ===== internals =====

    private List<SettlementRowDto> aggregate(YearMonth ym) {
        LocalDateTime startUtc = ym.atDay(1).atStartOfDay(BERLIN)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime endUtc = ym.plusMonths(1).atDay(1).atStartOfDay(BERLIN)
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        return ledgerRepository.aggregateByBrandForPeriod(startUtc, endUtc).stream()
                .map(a -> SettlementRowDto.from(a, brandPartnerRepository.findById(a.getBrandId()).orElse(null)))
                .toList();
    }

    private SettlementRowDto aggregateForBrand(Long brandId, YearMonth ym) {
        return aggregate(ym).stream()
                .filter(r -> brandId.equals(r.getBrandId()))
                .findFirst()
                .orElseGet(() -> zeroRow(brandId));
    }

    /** A no-activity brand still settles (to zeros), carrying its metadata. */
    private SettlementRowDto zeroRow(Long brandId) {
        BrandPartner brand = brandPartnerRepository.findById(brandId)
                .orElseThrow(() -> new BrandNotFoundException("Brand not found: " + brandId));
        return SettlementRowDto.builder()
                .brandId(brandId)
                .brandName(brand.getBrandName())
                .domestic(brand.isDomestic())
                .vatId(brand.getVatId())
                .taxNumber(brand.getTaxNumber())
                .commissionNet(BigDecimal.ZERO)
                .commissionVat(BigDecimal.ZERO)
                .commissionGross(BigDecimal.ZERO)
                .payoutAmount(BigDecimal.ZERO)
                .orderCount(0L)
                .refundCount(0L)
                .creditNote(false)
                .build();
    }

    private YearMonth parsePeriod(String period) {
        try {
            return YearMonth.parse(period);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid period format, expected YYYY-MM: " + period);
        }
    }

    private YearMonth lastClosedMonth() {
        return YearMonth.now(BERLIN).minusMonths(1);
    }
}
