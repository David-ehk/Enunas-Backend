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
            // The OrderItem snapshot is authoritative — it already folds in any discount and VAT.
            // Post-V5 items carry the explicit net/VAT split; legacy items recompute on the gross.
            BigDecimal rate = item.getCommissionRate() != null
                    ? item.getCommissionRate() : resolveBrandRate(brandId);

            BigDecimal total, fee, payout, commissionNet, commissionVat, brandNetRevenue;
            if (item.getCommissionNet() != null) {
                commissionNet   = item.getCommissionNet();
                commissionVat   = item.getCommissionVat();
                brandNetRevenue = item.getBrandNetRevenue();
                payout          = item.getBrandPayout();
                total           = item.getCustomerGrossAfterDiscount() != null
                        ? item.getCustomerGrossAfterDiscount() : item.getLineTotal();
                fee             = commissionNet; // platformFee mirrors net commission going forward
            } else {
                // Legacy pre-V5 order: gross fee, no VAT split.
                BigDecimal lineTotal = item.getLineTotal();
                fee    = item.getPlatformFeeAmount() != null
                        ? item.getPlatformFeeAmount()
                        : lineTotal.multiply(rate).setScale(2, RoundingMode.HALF_UP);
                payout = item.getBrandPayoutAmount() != null
                        ? item.getBrandPayoutAmount()
                        : lineTotal.subtract(fee);
                total  = lineTotal;
                commissionNet = null; commissionVat = null; brandNetRevenue = null;
            }

            entries.add(LedgerEntry.builder()
                    .orderId(order.getId())
                    .orderItemId(item.getId())
                    .brandPartnerId(brandId)
                    .totalAmount(total)
                    .platformFee(fee)
                    .brandPayout(payout)
                    .commissionNet(commissionNet)
                    .commissionVat(commissionVat)
                    .brandNetRevenue(brandNetRevenue)
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
     * Order-wide reversal: pro-rates the refund across EVERY brand on the order by their share of
     * orderTotal. Correct only when the whole order is being reversed — an admin cancel, or a
     * chargeback against the entire payment.
     *
     * <p>For a return, use {@link #recordRefund(Order, Long, BigDecimal, String)} instead. Reversing
     * a single brand's return through this method deducts from brands whose goods never came back
     * and can push them into {@code outstandingDebt}.
     */
    @Transactional
    public void recordRefund(Order order, BigDecimal refundAmount, String externalRefundId) {
        recordRefund(order, null, refundAmount, externalRefundId);
    }

    /**
     * Creates REFUND_REVERSAL entries and deducts from brand balances.
     * Deduction order: pendingBalance → payoutBalance → outstandingDebt.
     * Idempotent when externalRefundId is provided — safe to call on duplicate webhooks.
     *
     * @param brandId when non-null, restricts the reversal to that brand and pro-rates against
     *                THAT BRAND's gross share rather than the order total — so refunding one
     *                brand's return leaves every other brand's ledger untouched. When null, the
     *                whole order is reversed (see the 3-arg overload).
     */
    @Transactional
    public void recordRefund(Order order, Long brandId, BigDecimal refundAmount, String externalRefundId) {
        if (externalRefundId != null &&
                ledgerRepository.existsByExternalReferenceIdAndEntryType(externalRefundId, LedgerEntryType.REFUND_REVERSAL)) {
            log.warn("LedgerService: REFUND_REVERSAL already recorded for externalRefundId={}; skipping", externalRefundId);
            return;
        }

        // Reverse against the immutable per-item snapshot (already discount-adjusted), not a
        // gross recompute — so a full refund nets each brand's credited payout exactly to zero.
        Map<Long, BigDecimal> brandPayouts = new HashMap<>(); // credited brand payout per brand
        Map<Long, BigDecimal> brandFees    = new HashMap<>(); // credited platform fee (net) per brand
        Map<Long, BigDecimal> brandVats    = new HashMap<>(); // credited commission VAT per brand
        Map<Long, BigDecimal> brandRates   = new HashMap<>();
        Map<Long, BigDecimal> brandGross   = new HashMap<>(); // gross basis the fraction is taken against
        for (OrderItem item : order.getItems()) {
            Long bId = item.getBrandId();
            if (bId == null) continue;
            // Brand-scoped reversal: ignore every other brand's lines outright.
            if (brandId != null && !brandId.equals(bId)) continue;
            BigDecimal rate = item.getCommissionRate() != null
                    ? item.getCommissionRate() : resolveBrandRate(bId);
            // Post-V5: platform fee = commissionNet, plus a separate VAT line. Legacy: gross fee, vat 0.
            BigDecimal fee = item.getCommissionNet() != null
                    ? item.getCommissionNet()
                    : (item.getPlatformFeeAmount() != null
                        ? item.getPlatformFeeAmount()
                        : item.getLineTotal().multiply(rate).setScale(2, RoundingMode.HALF_UP));
            BigDecimal vat = item.getCommissionVat() != null ? item.getCommissionVat() : BigDecimal.ZERO;
            BigDecimal payout = item.getBrandPayoutAmount() != null
                    ? item.getBrandPayoutAmount()
                    : item.getLineTotal().subtract(fee);
            BigDecimal gross = item.getLineGross() != null ? item.getLineGross() : item.getLineTotal();
            brandPayouts.merge(bId, payout, BigDecimal::add);
            brandFees.merge(bId, fee, BigDecimal::add);
            brandVats.merge(bId, vat, BigDecimal::add);
            brandGross.merge(bId, gross, BigDecimal::add);
            brandRates.putIfAbsent(bId, rate);
        }

        if (brandId != null && brandPayouts.isEmpty()) {
            log.warn("LedgerService: no order items for brand {} on order {} — nothing to reverse",
                     brandId, order.getId());
            return;
        }

        // Basis the refund fraction is taken against: that brand's gross for a brand-scoped
        // reversal, the whole order for an order-wide one. Using orderTotal for a single brand's
        // return would under-reverse that brand and wrongly hit the others.
        BigDecimal basis = brandId != null
                ? brandGross.getOrDefault(brandId, BigDecimal.ZERO)
                : order.getTotal();
        BigDecimal fraction = basis.signum() == 0
                ? BigDecimal.ZERO
                : refundAmount.divide(basis, 6, RoundingMode.HALF_UP);
        if (fraction.compareTo(BigDecimal.ONE) > 0) {
            log.warn("LedgerService: refund {} exceeds basis {} for order {} brand {} — capping at 1.0",
                     refundAmount, basis, order.getId(), brandId);
            fraction = BigDecimal.ONE;
        }

        List<LedgerEntry> reversals = new ArrayList<>();

        for (Long reversedBrandId : brandPayouts.keySet()) {
            BigDecimal rate            = brandRates.get(reversedBrandId);
            BigDecimal platformPortion = brandFees.get(reversedBrandId).multiply(fraction).setScale(2, RoundingMode.HALF_UP);
            BigDecimal vatPortion      = brandVats.get(reversedBrandId).multiply(fraction).setScale(2, RoundingMode.HALF_UP);
            BigDecimal brandPortion    = brandPayouts.get(reversedBrandId).multiply(fraction).setScale(2, RoundingMode.HALF_UP);

            List<LedgerEntry> originals = ledgerRepository
                    .findActivePaymentEntriesByOrderAndBrand(order.getId(), reversedBrandId);

            reversals.add(LedgerEntry.builder()
                    .orderId(order.getId())
                    .orderItemId(originals.isEmpty() ? null : originals.get(0).getOrderItemId())
                    .brandPartnerId(reversedBrandId)
                    .totalAmount(platformPortion.add(vatPortion).add(brandPortion).negate())
                    .platformFee(platformPortion.negate())
                    .brandPayout(brandPortion.negate())
                    .commissionNet(platformPortion.negate())
                    .commissionVat(vatPortion.negate())
                    .commissionRate(rate)
                    .currency(order.getCurrency())
                    .entryType(LedgerEntryType.REFUND_REVERSAL)
                    .status(LedgerEntryStatus.REVERSED)
                    .payoutEligibleAt(LocalDateTime.now())
                    .movedToAvailable(false)
                    .reversalOfEntryId(originals.isEmpty() ? null : originals.get(0).getId())
                    .externalReferenceId(externalRefundId)
                    .build());

            BrandEconomics eco = brandEconomicsRepository.findByBrandPartner_Id(reversedBrandId)
                    .orElseThrow(() -> new IllegalStateException(
                            "BrandEconomics missing for brand " + reversedBrandId + " — cannot record REFUND_REVERSAL"));
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
                         reversedBrandId, remaining, order.getId());
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
