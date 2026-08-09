package com.enunas.backend.ledger;

import com.enunas.backend.brandpartner.brandeconomics.BrandEconomics;
import com.enunas.backend.brandpartner.brandeconomics.BrandEconomicsRepository;
import com.enunas.backend.order.Order;
import com.enunas.backend.order.OrderItem;
import com.enunas.backend.order.OrderShippingSnapshot;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
     * Creates one LedgerEntry (SHIPPING_REVENUE) per {@link OrderShippingSnapshot} and increments
     * each brand's pendingBalance/lifetimeRevenue — the same hold-then-release pipeline product
     * entries use. platformFee/commissionNet/commissionVat are always ZERO here, so shipping money
     * cannot leak into commission math by construction, not just by convention. Idempotent — safe
     * to call on duplicate webhooks.
     */
    @Transactional
    public void recordShippingRevenue(Order order, List<OrderShippingSnapshot> snapshots) {
        if (snapshots.isEmpty()) return;
        if (ledgerRepository.existsByOrderIdAndEntryType(order.getId(), LedgerEntryType.SHIPPING_REVENUE)) {
            log.warn("LedgerService: SHIPPING_REVENUE already recorded for orderId={}; skipping", order.getId());
            return;
        }

        LocalDateTime eligibleAt = LocalDateTime.now().plusDays(holdDays);
        Map<Long, BigDecimal> pendingIncrement  = new HashMap<>();
        Map<Long, BigDecimal> lifetimeIncrement = new HashMap<>();
        List<LedgerEntry> entries = new ArrayList<>();

        for (OrderShippingSnapshot snapshot : snapshots) {
            entries.add(LedgerEntry.builder()
                    .orderId(order.getId())
                    .brandPartnerId(snapshot.getBrandPartnerId())
                    .totalAmount(snapshot.getAmount())
                    .platformFee(BigDecimal.ZERO)
                    .brandPayout(snapshot.getAmount())
                    .commissionNet(BigDecimal.ZERO)
                    .commissionVat(BigDecimal.ZERO)
                    .commissionRate(BigDecimal.ZERO)
                    .currency(snapshot.getCurrency())
                    .entryType(LedgerEntryType.SHIPPING_REVENUE)
                    .status(LedgerEntryStatus.PENDING_RELEASE)
                    .payoutEligibleAt(eligibleAt)
                    .movedToAvailable(false)
                    .build());

            pendingIncrement.merge(snapshot.getBrandPartnerId(), snapshot.getAmount(), BigDecimal::add);
            lifetimeIncrement.merge(snapshot.getBrandPartnerId(), snapshot.getAmount(), BigDecimal::add);
        }

        ledgerRepository.saveAll(entries);

        for (Long brandId : pendingIncrement.keySet()) {
            BrandEconomics eco = brandEconomicsRepository.findByBrandPartner_Id(brandId)
                    .orElseThrow(() -> new IllegalStateException(
                            "BrandEconomics missing for brand " + brandId + " — cannot record SHIPPING_REVENUE"));
            eco.setPendingBalance(eco.getPendingBalance().add(pendingIncrement.get(brandId)));
            eco.setLifetimeRevenue(eco.getLifetimeRevenue().add(lifetimeIncrement.get(brandId)));
            brandEconomicsRepository.save(eco);
        }

        log.info("LedgerService: recorded {} SHIPPING_REVENUE entries for orderId={}", entries.size(), order.getId());
    }

    /**
     * Order-wide reversal: reverses EVERY brand's product AND shipping entries at the same
     * fraction of {@code order.getTotal()}. Correct only when the whole order is being reversed —
     * an admin pre-shipment cancel, or a chargeback against the entire payment. A full-order
     * reversal undoes everything, product and shipping alike.
     *
     * <p>For a return, use {@link #recordRefund(Order, Long, BigDecimal, String)} instead — it
     * deliberately touches product entries only. See {@link #reverseShippingEntries} for why.
     *
     * <p>Both idempotency guards (product and shipping) are checked ONCE per call, before looping
     * brands — a duplicate webhook for the same {@code externalRefundId} must skip the whole
     * reversal, not just the first brand touched.
     */
    @Transactional
    public void recordRefund(Order order, BigDecimal refundAmount, String externalRefundId) {
        boolean productAlreadyDone = externalRefundId != null &&
                ledgerRepository.existsByExternalReferenceIdAndEntryType(externalRefundId, LedgerEntryType.REFUND_REVERSAL);
        boolean shippingAlreadyDone = externalRefundId != null &&
                ledgerRepository.existsByExternalReferenceIdAndEntryType(shippingRef(externalRefundId), LedgerEntryType.REFUND_REVERSAL);
        if (productAlreadyDone && shippingAlreadyDone) {
            log.warn("LedgerService: REFUND_REVERSAL already recorded for externalRefundId={}; skipping", externalRefundId);
            return;
        }

        Set<Long> brandIds = new HashSet<>();
        for (OrderItem item : order.getItems()) {
            if (item.getBrandId() != null) brandIds.add(item.getBrandId());
        }

        BigDecimal fraction = fractionOf(refundAmount, order.getTotal());
        for (Long brandId : brandIds) {
            if (!productAlreadyDone) reverseProductEntries(order, brandId, fraction, externalRefundId);
            if (!shippingAlreadyDone) reverseShippingEntries(order, brandId, fraction, externalRefundId);
        }
    }

    /**
     * Creates REFUND_REVERSAL entries for a single BRAND's return, reversing that brand's product
     * entries only. Deduction order: pendingBalance → payoutBalance → outstandingDebt. Idempotent
     * when externalRefundId is provided — safe to call on duplicate webhooks.
     *
     * <p><b>Shipping is deliberately untouched here.</b> This version doesn't refund shipping on a
     * partial per-brand item return (standard policy: shipping is charged once per shipment, not
     * per item) — but it stays fully traceable via its own snapshot and ledger entries for when
     * that policy question needs answering. The seam for that decision is a future
     * {@code RefundPolicyService} — e.g. "the customer returned every item from this brand, should
     * its shipping refund too?" — not this method.
     *
     * @param brandId restricts the reversal to that brand and pro-rates against THAT BRAND's gross
     *                share rather than the order total — so refunding one brand's return leaves
     *                every other brand's ledger untouched.
     */
    @Transactional
    public void recordRefund(Order order, Long brandId, BigDecimal refundAmount, String externalRefundId) {
        if (externalRefundId != null &&
                ledgerRepository.existsByExternalReferenceIdAndEntryType(externalRefundId, LedgerEntryType.REFUND_REVERSAL)) {
            log.warn("LedgerService: REFUND_REVERSAL already recorded for externalRefundId={}; skipping", externalRefundId);
            return;
        }

        BigDecimal basis = brandProductGross(order, brandId);
        if (basis == null) {
            log.warn("LedgerService: no order items for brand {} on order {} — nothing to reverse",
                     brandId, order.getId());
            return;
        }

        BigDecimal fraction = fractionOf(refundAmount, basis);
        reverseProductEntries(order, brandId, fraction, externalRefundId);
    }

    /**
     * Reverses ORDER_PAYMENT entries for one brand at the given fraction — the product/commission
     * side of a refund. Never touches SHIPPING_REVENUE entries; see {@link #reverseShippingEntries}.
     */
    private void reverseProductEntries(Order order, Long brandId, BigDecimal fraction, String externalRefundId) {
        BigDecimal fee = BigDecimal.ZERO, vat = BigDecimal.ZERO, payout = BigDecimal.ZERO;
        BigDecimal rate = null;
        for (OrderItem item : order.getItems()) {
            if (!brandId.equals(item.getBrandId())) continue;
            BigDecimal itemRate = item.getCommissionRate() != null
                    ? item.getCommissionRate() : resolveBrandRate(brandId);
            BigDecimal itemFee = item.getCommissionNet() != null
                    ? item.getCommissionNet()
                    : (item.getPlatformFeeAmount() != null
                        ? item.getPlatformFeeAmount()
                        : item.getLineTotal().multiply(itemRate).setScale(2, RoundingMode.HALF_UP));
            BigDecimal itemVat = item.getCommissionVat() != null ? item.getCommissionVat() : BigDecimal.ZERO;
            BigDecimal itemPayout = item.getBrandPayoutAmount() != null
                    ? item.getBrandPayoutAmount()
                    : item.getLineTotal().subtract(itemFee);
            fee = fee.add(itemFee);
            vat = vat.add(itemVat);
            payout = payout.add(itemPayout);
            if (rate != null && rate.compareTo(itemRate) != 0) {
                log.warn("LedgerService: brand {} order {} has items with differing commission rates ({} vs {}); "
                        + "reversal entry will record the last one seen (audit field only, not used in amount math)",
                        brandId, order.getId(), rate, itemRate);
            }
            rate = itemRate;
        }
        if (rate == null) {
            log.warn("LedgerService: no order items for brand {} on order {} — nothing to reverse (product)",
                    brandId, order.getId());
            return;
        }

        BigDecimal platformPortion = fee.multiply(fraction).setScale(2, RoundingMode.HALF_UP);
        BigDecimal vatPortion      = vat.multiply(fraction).setScale(2, RoundingMode.HALF_UP);
        BigDecimal brandPortion    = payout.multiply(fraction).setScale(2, RoundingMode.HALF_UP);

        List<LedgerEntry> originals = ledgerRepository
                .findActivePaymentEntriesByOrderAndBrand(order.getId(), brandId);

        LedgerEntry reversal = LedgerEntry.builder()
                .orderId(order.getId())
                .orderItemId(originals.isEmpty() ? null : originals.get(0).getOrderItemId())
                .brandPartnerId(brandId)
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
                .build();

        ledgerRepository.save(reversal);
        applyBrandDebit(brandId, brandPortion);
        log.info("LedgerService: reversed product entry for orderId={} brandId={} amount={}",
                order.getId(), brandId, brandPortion);
    }

    /**
     * Reverses SHIPPING_REVENUE entries for one brand at the given fraction. Called only from the
     * order-wide refund path — see this method group's javadoc for why the per-brand return path
     * never calls this.
     */
    private void reverseShippingEntries(Order order, Long brandId, BigDecimal fraction, String externalRefundId) {
        List<LedgerEntry> originals = ledgerRepository.findActiveShippingEntriesByOrderAndBrand(order.getId(), brandId);
        if (originals.isEmpty()) return;

        BigDecimal shippingAmount = originals.stream()
                .map(LedgerEntry::getBrandPayout)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal reversedPortion = shippingAmount.multiply(fraction).setScale(2, RoundingMode.HALF_UP);
        if (reversedPortion.signum() == 0) return;

        LedgerEntry reversal = LedgerEntry.builder()
                .orderId(order.getId())
                .brandPartnerId(brandId)
                .totalAmount(reversedPortion.negate())
                .platformFee(BigDecimal.ZERO)
                .brandPayout(reversedPortion.negate())
                .commissionNet(BigDecimal.ZERO)
                .commissionVat(BigDecimal.ZERO)
                .commissionRate(BigDecimal.ZERO)
                .currency(order.getCurrency())
                .entryType(LedgerEntryType.REFUND_REVERSAL)
                .status(LedgerEntryStatus.REVERSED)
                .payoutEligibleAt(LocalDateTime.now())
                .movedToAvailable(false)
                .reversalOfEntryId(originals.get(0).getId())
                .externalReferenceId(shippingRef(externalRefundId))
                .build();

        ledgerRepository.save(reversal);
        applyBrandDebit(brandId, reversedPortion);
        log.info("LedgerService: reversed shipping entry for orderId={} brandId={} amount={}",
                order.getId(), brandId, reversedPortion);
    }

    private static String shippingRef(String externalRefundId) {
        return externalRefundId + ":SHIPPING";
    }

    /** Debits {@code amount} from a brand's balances: pendingBalance → payoutBalance → outstandingDebt. */
    private void applyBrandDebit(Long brandId, BigDecimal amount) {
        BrandEconomics eco = brandEconomicsRepository.findByBrandPartner_Id(brandId)
                .orElseThrow(() -> new IllegalStateException(
                        "BrandEconomics missing for brand " + brandId + " — cannot record REFUND_REVERSAL"));
        BigDecimal remaining = amount;

        if (eco.getPendingBalance().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal fromPending = remaining.min(eco.getPendingBalance());
            eco.setPendingBalance(eco.getPendingBalance().subtract(fromPending));
            remaining = remaining.subtract(fromPending);
        }
        if (remaining.compareTo(BigDecimal.ZERO) > 0 && eco.getPayoutBalance().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal fromAvailable = remaining.min(eco.getPayoutBalance());
            eco.setPayoutBalance(eco.getPayoutBalance().subtract(fromAvailable));
            remaining = remaining.subtract(fromAvailable);
        }
        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            eco.setOutstandingDebt(eco.getOutstandingDebt().add(remaining));
            log.warn("LedgerService: brand {} incurred debt of {}", brandId, remaining);
        }
        brandEconomicsRepository.save(eco);
    }

    private BigDecimal fractionOf(BigDecimal amount, BigDecimal basis) {
        if (basis.signum() == 0) return BigDecimal.ZERO;
        BigDecimal fraction = amount.divide(basis, 6, RoundingMode.HALF_UP);
        if (fraction.compareTo(BigDecimal.ONE) > 0) {
            log.warn("LedgerService: refund {} exceeds basis {} — capping fraction at 1.0", amount, basis);
            return BigDecimal.ONE;
        }
        return fraction;
    }

    /** @return that brand's summed lineGross, or {@code null} if the order has no items for that brand. */
    private BigDecimal brandProductGross(Order order, Long brandId) {
        boolean found = false;
        BigDecimal gross = BigDecimal.ZERO;
        for (OrderItem item : order.getItems()) {
            if (!brandId.equals(item.getBrandId())) continue;
            found = true;
            BigDecimal lineGross = item.getLineGross() != null ? item.getLineGross() : item.getLineTotal();
            gross = gross.add(lineGross);
        }
        return found ? gross : null;
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
