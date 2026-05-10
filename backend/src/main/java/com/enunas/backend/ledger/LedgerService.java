package com.enunas.backend.ledger;

import com.enunas.backend.brandpartner.brandeconomics.BrandEconomics;
import com.enunas.backend.brandpartner.brandeconomics.BrandEconomicsRepository;
import com.enunas.backend.order.Order;
import com.enunas.backend.order.OrderItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LedgerService {

    private final LedgerRepository ledgerRepository;
    private final BrandEconomicsRepository brandEconomicsRepository;

    @Value("${enunas.platform.commission-rate:0.18}")
    private BigDecimal globalCommissionRate;

    @Value("${enunas.payout.hold-days:7}")
    private int holdDays;

    /**
     * Creates one LedgerEntry (ORDER_PAYMENT) per OrderItem and increments each brand's
     * pendingBalance and lifetimeRevenue. Idempotent — safe to call on duplicate webhooks.
     */
    @Transactional
    public void recordOrderPayment(Order order) {
        if (ledgerRepository.existsByOrderIdAndEntryType(order.getId(), LedgerEntryType.ORDER_PAYMENT)) {
            log.warn("LedgerService: ORDER_PAYMENT already recorded for orderId={}; skipping", order.getId());
            return;
        }

        LocalDateTime eligibleAt = LocalDateTime.now().plusDays(holdDays);
        Map<Long, BigDecimal> pendingIncrement  = new HashMap<>();
        Map<Long, BigDecimal> lifetimeIncrement = new HashMap<>();
        List<LedgerEntry> entries = new ArrayList<>();

        for (OrderItem item : order.getItems()) {
            Long brandId = item.getBrandId();
            if (brandId == null) {
                log.warn("LedgerService: skipping item {} with no brand (orderId={})", item.getId(), order.getId());
                continue;
            }
            BigDecimal rate      = resolveBrandRate(brandId);
            BigDecimal lineTotal = item.getLineTotal();
            BigDecimal fee       = lineTotal.multiply(rate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal payout    = lineTotal.subtract(fee);

            entries.add(LedgerEntry.builder()
                    .orderId(order.getId())
                    .orderItemId(item.getId())
                    .brandPartnerId(brandId)
                    .totalAmount(lineTotal)
                    .platformFee(fee)
                    .brandPayout(payout)
                    .commissionRate(rate)
                    .currency(order.getCurrency())
                    .entryType(LedgerEntryType.ORDER_PAYMENT)
                    .status(LedgerEntryStatus.PENDING_RELEASE)
                    .payoutEligibleAt(eligibleAt)
                    .movedToAvailable(false)
                    .build());

            pendingIncrement.merge(brandId, payout, BigDecimal::add);
            lifetimeIncrement.merge(brandId, payout, BigDecimal::add);
        }

        ledgerRepository.saveAll(entries);

        for (Long brandId : pendingIncrement.keySet()) {
            BrandEconomics eco = brandEconomicsRepository.findByBrandPartner_Id(brandId)
                    .orElseThrow(() -> new IllegalStateException(
                            "BrandEconomics missing for brand " + brandId + " — cannot record ORDER_PAYMENT"));
            eco.setPendingBalance(eco.getPendingBalance().add(pendingIncrement.get(brandId)));
            eco.setLifetimeRevenue(eco.getLifetimeRevenue().add(lifetimeIncrement.get(brandId)));
            brandEconomicsRepository.save(eco);
        }

        log.info("LedgerService: recorded {} entries for orderId={}", entries.size(), order.getId());
    }

    /**
     * Creates REFUND_REVERSAL entries and deducts from brand balances.
     * Pro-rates the refund amount across brands by their share of orderTotal.
     * Deduction order: pendingBalance → payoutBalance → outstandingDebt.
     * Idempotent when externalRefundId is provided — safe to call on duplicate webhooks.
     */
    @Transactional
    public void recordRefund(Order order, BigDecimal refundAmount, String externalRefundId) {
        if (externalRefundId != null &&
                ledgerRepository.existsByExternalReferenceIdAndEntryType(externalRefundId, LedgerEntryType.REFUND_REVERSAL)) {
            log.warn("LedgerService: REFUND_REVERSAL already recorded for externalRefundId={}; skipping", externalRefundId);
            return;
        }

        BigDecimal orderTotal = order.getTotal();
        Map<Long, BigDecimal> brandSubtotals = new HashMap<>();
        for (OrderItem item : order.getItems()) {
            if (item.getBrandId() != null) {
                brandSubtotals.merge(item.getBrandId(), item.getLineTotal(), BigDecimal::add);
            }
        }

        List<LedgerEntry> reversals = new ArrayList<>();

        for (Map.Entry<Long, BigDecimal> entry : brandSubtotals.entrySet()) {
            Long brandId             = entry.getKey();
            BigDecimal brandSubtotal = entry.getValue();

            BigDecimal brandRefund = refundAmount
                    .multiply(brandSubtotal)
                    .divide(orderTotal, 2, RoundingMode.HALF_UP);

            BigDecimal rate              = resolveBrandRate(brandId);
            BigDecimal platformPortion   = brandRefund.multiply(rate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal brandPortion      = brandRefund.subtract(platformPortion);

            List<LedgerEntry> originals = ledgerRepository
                    .findActivePaymentEntriesByOrderAndBrand(order.getId(), brandId);

            reversals.add(LedgerEntry.builder()
                    .orderId(order.getId())
                    .orderItemId(originals.isEmpty() ? null : originals.get(0).getOrderItemId())
                    .brandPartnerId(brandId)
                    .totalAmount(brandRefund.negate())
                    .platformFee(platformPortion.negate())
                    .brandPayout(brandPortion.negate())
                    .commissionRate(rate)
                    .currency(order.getCurrency())
                    .entryType(LedgerEntryType.REFUND_REVERSAL)
                    .status(LedgerEntryStatus.REVERSED)
                    .payoutEligibleAt(LocalDateTime.now())
                    .movedToAvailable(false)
                    .reversalOfEntryId(originals.isEmpty() ? null : originals.get(0).getId())
                    .externalReferenceId(externalRefundId)
                    .build());

            BrandEconomics eco = brandEconomicsRepository.findByBrandPartner_Id(brandId)
                    .orElseThrow(() -> new IllegalStateException(
                            "BrandEconomics missing for brand " + brandId + " — cannot record REFUND_REVERSAL"));
            BigDecimal remaining = brandPortion;

            if (eco.getPendingBalance().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal fromPending = remaining.min(eco.getPendingBalance());
                eco.setPendingBalance(eco.getPendingBalance().subtract(fromPending));
                remaining = remaining.subtract(fromPending);
            }

            if (remaining.compareTo(BigDecimal.ZERO) > 0
                    && eco.getPayoutBalance().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal fromAvailable = remaining.min(eco.getPayoutBalance());
                eco.setPayoutBalance(eco.getPayoutBalance().subtract(fromAvailable));
                remaining = remaining.subtract(fromAvailable);
            }

            if (remaining.compareTo(BigDecimal.ZERO) > 0) {
                eco.setOutstandingDebt(eco.getOutstandingDebt().add(remaining));
                log.warn("LedgerService: brand {} incurred debt of {} after refund on order {}",
                         brandId, remaining, order.getId());
            }

            brandEconomicsRepository.save(eco);
        }

        ledgerRepository.saveAll(reversals);
        log.info("LedgerService: recorded {} reversal entries for orderId={}", reversals.size(), order.getId());
    }

    /**
     * Records a completed bank transfer to a brand.
     *
     * netAmount      — what was actually wired to the brand's bank account.
     * debtAbsorbed   — portion of payoutBalance kept by the platform to offset outstandingDebt.
     *                  Pass ZERO when there is no outstanding debt.
     *
     * Together they clear the full payoutBalance: payoutBalance -= (netAmount + debtAbsorbed).
     * Idempotent — safe to call multiple times with the same externalPayoutId.
     */
    @Transactional
    public void recordPayoutTransfer(Long brandPartnerId, BigDecimal netAmount,
                                     BigDecimal debtAbsorbed, String externalPayoutId) {
        if (ledgerRepository.existsByExternalReferenceIdAndEntryType(externalPayoutId, LedgerEntryType.PAYOUT_TRANSFER)) {
            log.warn("LedgerService: PAYOUT_TRANSFER already recorded for {}; skipping", externalPayoutId);
            return;
        }

        LedgerEntry entry = LedgerEntry.builder()
                .brandPartnerId(brandPartnerId)
                .totalAmount(netAmount)
                .platformFee(BigDecimal.ZERO)
                .brandPayout(netAmount)
                .commissionRate(BigDecimal.ZERO)
                .currency("EUR")
                .entryType(LedgerEntryType.PAYOUT_TRANSFER)
                .status(LedgerEntryStatus.PAID_OUT)
                .payoutEligibleAt(LocalDateTime.now())
                .movedToAvailable(true)
                .externalReferenceId(externalPayoutId)
                .build();

        ledgerRepository.save(entry);

        BrandEconomics ecoP = brandEconomicsRepository.findByBrandPartner_Id(brandPartnerId)
                .orElseThrow(() -> new IllegalStateException(
                        "BrandEconomics missing for brand " + brandPartnerId + " — cannot record PAYOUT_TRANSFER"));
        BigDecimal totalCleared = netAmount.add(debtAbsorbed);
        ecoP.setPayoutBalance(ecoP.getPayoutBalance().subtract(totalCleared).max(BigDecimal.ZERO));
        ecoP.setPaidOutTotal(ecoP.getPaidOutTotal().add(netAmount));
        if (debtAbsorbed.compareTo(BigDecimal.ZERO) > 0) {
            ecoP.setOutstandingDebt(ecoP.getOutstandingDebt().subtract(debtAbsorbed).max(BigDecimal.ZERO));
            log.info("LedgerService: absorbed debt={} for brand {} on payout {}", debtAbsorbed, brandPartnerId, externalPayoutId);
        }
        brandEconomicsRepository.save(ecoP);

        log.info("LedgerService: PAYOUT_TRANSFER net={} debtAbsorbed={} brand={} ref={}", netAmount, debtAbsorbed, brandPartnerId, externalPayoutId);
    }

    /**
     * Moves PENDING_RELEASE entries past their hold window to AVAILABLE,
     * shifting pendingBalance → payoutBalance in BrandEconomics.
     * Outstanding debt is absorbed before adding to payoutBalance.
     */
    @Transactional
    public void releasePendingBalances() {
        List<LedgerEntry> releasable = ledgerRepository.findReleasableEntries(LocalDateTime.now());
        if (releasable.isEmpty()) {
            log.debug("PayoutRelease: no entries to release");
            return;
        }

        Map<Long, BigDecimal> brandReleaseAmount = new HashMap<>();
        for (LedgerEntry entry : releasable) {
            brandReleaseAmount.merge(entry.getBrandPartnerId(), entry.getBrandPayout(), BigDecimal::add);
        }

        for (Map.Entry<Long, BigDecimal> e : brandReleaseAmount.entrySet()) {
            Long relBrandId = e.getKey();
            BrandEconomics eco = brandEconomicsRepository.findByBrandPartner_Id(relBrandId)
                    .orElseThrow(() -> new IllegalStateException(
                            "BrandEconomics missing for brand " + relBrandId + " — cannot release pending balance"));
            BigDecimal amount = e.getValue();

            if (eco.getOutstandingDebt().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal absorbed = amount.min(eco.getOutstandingDebt());
                eco.setOutstandingDebt(eco.getOutstandingDebt().subtract(absorbed));
                amount = amount.subtract(absorbed);
                log.info("PayoutRelease: absorbed {} of debt for brand {}", absorbed, relBrandId);
            }

            // Safety floor: pendingBalance may have been partially consumed by a concurrent refund
            eco.setPendingBalance(eco.getPendingBalance().subtract(e.getValue()).max(BigDecimal.ZERO));
            eco.setPayoutBalance(eco.getPayoutBalance().add(amount));
            brandEconomicsRepository.save(eco);
        }

        List<Long> ids = releasable.stream().map(LedgerEntry::getId).collect(Collectors.toList());
        ledgerRepository.markAsAvailable(ids);

        log.info("PayoutRelease: released {} entries across {} brand(s)", releasable.size(), brandReleaseAmount.size());
    }

    private BigDecimal resolveBrandRate(Long brandId) {
        return brandEconomicsRepository.findByBrandPartner_Id(brandId)
                .map(BrandEconomics::getDefaultCommissionRate)
                .filter(r -> r != null && r.compareTo(BigDecimal.ZERO) > 0)
                .orElse(globalCommissionRate);
    }
}
