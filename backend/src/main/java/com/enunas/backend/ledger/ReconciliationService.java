package com.enunas.backend.ledger;

import com.enunas.backend.brandpartner.brandeconomics.BrandEconomics;
import com.enunas.backend.brandpartner.brandeconomics.BrandEconomicsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReconciliationService {

    private final LedgerRepository ledgerRepository;
    private final BrandEconomicsRepository brandEconomicsRepository;

    /**
     * Financial drift report for one brand.
     *
     * ledgerNetOwed  = SUM(ORDER_PAYMENT) + SUM(REFUND_REVERSAL) − SUM(PAYOUT_TRANSFER)
     *                = what the platform still owes the brand, according to the ledger
     *
     * ecoNetOwed     = pendingBalance + payoutBalance − outstandingDebt
     *                = what BrandEconomics thinks the platform owes the brand
     *
     * drift = ecoNetOwed − ledgerNetOwed  (zero means clean)
     */
    public record DriftReport(
            Long brandPartnerId,
            BigDecimal ledgerNetOwed,
            BigDecimal ecoNetOwed,
            BigDecimal drift,
            boolean clean
    ) {}

    // ===== Check =====

    @Transactional(readOnly = true)
    public List<DriftReport> checkAllBrands() {
        List<DriftReport> reports = new ArrayList<>();
        for (BrandEconomics eco : brandEconomicsRepository.findAll()) {
            reports.add(buildReport(eco.getBrandPartner().getId(), eco));
        }
        return reports;
    }

    @Transactional(readOnly = true)
    public DriftReport checkBrand(Long brandId) {
        BrandEconomics eco = brandEconomicsRepository.findByBrandPartner_Id(brandId)
                .orElseThrow(() -> new IllegalArgumentException("No BrandEconomics for brand: " + brandId));
        return buildReport(brandId, eco);
    }

    // ===== Rebuild (Ledger is Truth) =====

    /**
     * Resets BrandEconomics for one brand from the ledger.
     *
     * lifetimeRevenue and paidOutTotal are exact (directly derivable).
     * pendingBalance and payoutBalance are approximated from current entry statuses
     * (original ORDER_PAYMENT entries keep their status even after refunds).
     * outstandingDebt is then derived from the net invariant.
     *
     * Use only when drift is confirmed — this overwrites BrandEconomics.
     */
    @Transactional
    public DriftReport rebuildFromLedger(Long brandId) {
        BrandEconomics eco = brandEconomicsRepository.findByBrandPartner_Id(brandId)
                .orElseThrow(() -> new IllegalArgumentException("No BrandEconomics for brand: " + brandId));

        BigDecimal orderPayments  = orZero(ledgerRepository.sumOrderPaymentsForBrand(brandId));
        BigDecimal refunds        = orZero(ledgerRepository.sumRefundReversalsForBrand(brandId)); // negative
        BigDecimal payoutsSent    = orZero(ledgerRepository.sumPayoutTransfersForBrand(brandId));
        BigDecimal pendingLedger  = orZero(ledgerRepository.sumPendingReleaseForBrand(brandId));
        BigDecimal availableLedger= orZero(ledgerRepository.sumAvailableForBrand(brandId));

        // Net the platform still owes the brand (after refunds and paid-outs)
        BigDecimal netOwed = orderPayments.add(refunds).subtract(payoutsSent);

        // Derive outstandingDebt: debt = pendingBalance + payoutBalance − netOwed
        // If positive → brand owes platform that amount (over-refunded)
        BigDecimal rawDebt       = pendingLedger.add(availableLedger).subtract(netOwed);
        BigDecimal outstandingDebt = rawDebt.max(BigDecimal.ZERO);

        log.warn("ReconciliationService: rebuilding brand={} — "
                + "lifetimeRevenue={}, paidOutTotal={}, pending≈{}, available≈{}, outstandingDebt={}",
                brandId, orderPayments, payoutsSent, pendingLedger, availableLedger, outstandingDebt);

        eco.setLifetimeRevenue(orderPayments);
        eco.setPaidOutTotal(payoutsSent);
        eco.setPendingBalance(pendingLedger);
        eco.setPayoutBalance(availableLedger);
        eco.setOutstandingDebt(outstandingDebt);
        brandEconomicsRepository.save(eco);

        return buildReport(brandId, eco);
    }

    // ===== Private helpers =====

    private DriftReport buildReport(Long brandId, BrandEconomics eco) {
        BigDecimal orderPayments = orZero(ledgerRepository.sumOrderPaymentsForBrand(brandId));
        BigDecimal refunds       = orZero(ledgerRepository.sumRefundReversalsForBrand(brandId));
        BigDecimal payoutsSent   = orZero(ledgerRepository.sumPayoutTransfersForBrand(brandId));

        BigDecimal ledgerNetOwed = orderPayments.add(refunds).subtract(payoutsSent);
        BigDecimal ecoNetOwed    = eco.getPendingBalance()
                .add(eco.getPayoutBalance())
                .subtract(eco.getOutstandingDebt());

        BigDecimal drift = ecoNetOwed.subtract(ledgerNetOwed);
        boolean clean    = drift.compareTo(BigDecimal.ZERO) == 0;

        if (!clean) {
            log.warn("DRIFT brand={}: ledgerNet={}, ecoNet={}, drift={}", brandId, ledgerNetOwed, ecoNetOwed, drift);
        }

        return new DriftReport(brandId, ledgerNetOwed, ecoNetOwed, drift, clean);
    }

    private BigDecimal orZero(Optional<BigDecimal> opt) {
        return opt.orElse(BigDecimal.ZERO);
    }
}
