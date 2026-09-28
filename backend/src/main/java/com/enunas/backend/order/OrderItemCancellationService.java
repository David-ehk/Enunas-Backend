package com.enunas.backend.order;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.discount.DiscountService;
import com.enunas.backend.exception.PaymentException;
import com.enunas.backend.exception.PaymentRejectedException;
import com.enunas.backend.ledger.LedgerService;
import com.enunas.backend.order.dto.CancelOrderItemsDto;
import com.enunas.backend.order.dto.ReconcileItemCancellationDto;
import com.enunas.backend.payment.Payment;
import com.enunas.backend.payment.PaymentProvider;
import com.enunas.backend.payment.PaymentRepository;
import com.enunas.backend.payment.PaymentStatus;
import com.enunas.backend.payment.RefundCommand;
import com.enunas.backend.product.productvariant.ProductVariantRepository;
import com.enunas.backend.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Pre-shipment cancellation of one brand's line items on a paid order (spec
 * docs/superpowers/specs/2026-09-28-per-item-cancellation-design.md).
 *
 * <p>Claim → refund → finalize. The claim (a short write transaction under the orders row lock)
 * stores the whole decision on the items — everything but the refund id — so a second overlapping
 * call sees them taken and 409s before money moves (D15). The Mollie call runs outside any
 * transaction. Finalize reads everything back from the database, so the normal path and the
 * reconcile RECORD action are the same code.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderItemCancellationService {

    /** CANCELLABLE ∪ {PARTIALLY_SHIPPED} (spec D7). PENDING passes this gate and is refused by D14. */
    private static final Set<OrderStatus> ITEM_CANCELLABLE = EnumSet.of(
            OrderStatus.PENDING, OrderStatus.PAID, OrderStatus.PARTIALLY_SHIPPED,
            OrderStatus.SHIPPING_PROBLEM, OrderStatus.AWAITING_ADMIN, OrderStatus.MANUAL_REVIEW);

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final OrderShipmentRepository orderShipmentRepository;
    private final OrderShippingSnapshotRepository orderShippingSnapshotRepository;
    private final ProductVariantRepository productVariantRepository;
    private final LedgerService ledgerService;
    private final DiscountService discountService;
    private final PaymentProvider paymentProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final OrderLock orderLock;
    private final OrderShipmentRollup shipmentRollup;

    /** What the refund step needs from a committed claim. */
    private record Claim(String claimKey, String orderNumber, String brandName, int itemCount,
                         String transactionId, BigDecimal refundAmount) {}

    @PreAuthorize("hasRole('ADMIN')")
    public void cancelItems(Long orderId, CancelOrderItemsDto dto, User admin) {
        Claim claim = transactionTemplate.execute(s -> claim(orderId, dto, admin));
        String refundId = refund(orderId, claim);
        try {
            transactionTemplate.executeWithoutResult(s -> finalizeClaim(orderId, claim.claimKey(), refundId));
        } catch (RuntimeException e) {
            log.error("ITEM_CANCEL_RECORDING_FAILED: order {} claim {} — refund {} succeeded at the provider but "
                            + "was not recorded; reconcile with RECORD and this refund id: {}",
                    claim.orderNumber(), claim.claimKey(), refundId, e.getMessage());
            throw e;
        }
    }

    /**
     * Resolves a stuck claim by an admin's explicit decision, never automatically and never by a
     * second Mollie call (spec D26). RECORD settles with a refund the admin found at Mollie; RELEASE
     * frees the items when the admin confirmed no refund exists.
     */
    @PreAuthorize("hasRole('ADMIN')")
    public void reconcile(Long orderId, ReconcileItemCancellationDto dto, User admin) {
        boolean record = dto.getAction() == ReconcileItemCancellationDto.Action.RECORD;
        if (record && (dto.getRefundId() == null || dto.getRefundId().isBlank())) {
            throw new IllegalArgumentException("RECORD needs the refund id from Mollie.");
        }
        transactionTemplate.executeWithoutResult(s -> {
            assertStuck(orderId, dto.getClaimKey());
            if (record) {
                finalizeClaim(orderId, dto.getClaimKey(), dto.getRefundId());
            } else {
                releaseClaim(orderId, dto.getClaimKey());
            }
        });
        log.warn("ITEM_CANCEL_RECONCILED: admin {} {} claim {} on order {}{}", admin.getEmail(), dto.getAction(),
                dto.getClaimKey(), orderId, record ? " with refund " + dto.getRefundId() : "");
    }

    private void assertStuck(Long orderId, String claimKey) {
        List<OrderItem> items = claimItems(orderLock.lockFresh(orderId), claimKey);
        if (items.stream().anyMatch(OrderItem::isCancellationSettled)) {
            throw new IllegalStateException("Claim " + claimKey + " is already settled — nothing to reconcile.");
        }
        if (!items.get(0).isClaimStuck(LocalDateTime.now())) {
            throw new IllegalStateException("Claim " + claimKey + " is still in progress — reconcile only after "
                    + OrderItem.STUCK_AFTER.toMinutes() + " minutes.");
        }
    }

    private Claim claim(Long orderId, CancelOrderItemsDto dto, User admin) {
        Order order = orderLock.lockFresh(orderId);
        if (!ITEM_CANCELLABLE.contains(order.getStatus())) {
            throw new IllegalStateException("Items of order " + order.getOrderNumber()
                    + " cannot be cancelled from " + order.getStatus() + ".");
        }
        Payment payment = paymentRepository.findByOrderId(orderId)
                .filter(p -> p.getPaidAt() != null)
                .orElseThrow(() -> new IllegalStateException("Order " + order.getOrderNumber()
                        + " has no captured payment — use the whole-order cancel."));

        Set<Long> ids = new LinkedHashSet<>(dto.getOrderItemIds());
        List<OrderItem> targets = order.getItems().stream().filter(i -> ids.contains(i.getId())).toList();
        if (targets.size() != ids.size()) {
            throw new IllegalArgumentException("Not all of " + ids + " are items of order " + order.getOrderNumber() + ".");
        }
        Set<Long> brandIds = targets.stream().map(OrderItem::getBrandId).collect(Collectors.toSet());
        if (brandIds.size() != 1 || brandIds.contains(null)) {
            throw new IllegalArgumentException("All items of one cancellation must belong to exactly one brand.");
        }
        Long brandId = brandIds.iterator().next();

        for (OrderItem item : targets) {
            if (item.getCustomerGrossAfterDiscount() == null) {
                throw new IllegalStateException("Item " + item.getId() + " predates per-item pricing (legacy order) "
                        + "— use the whole-order cancel.");
            }
            if (item.isCancelled()) {
                throw new IllegalStateException(alreadyCancelledMessage(item));
            }
        }
        if (orderShipmentRepository.brandShipped(orderId, brandId)) {
            throw new IllegalStateException("This brand has already shipped its items on order "
                    + order.getOrderNumber() + " — use the return flow.");
        }

        boolean includesShipping = order.activeItemsOf(brandId).stream().allMatch(i -> ids.contains(i.getId()));
        String claimKey = "item-cancel-" + order.getOrderNumber() + "-" + UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        for (OrderItem item : targets) {
            item.claimCancellation(claimKey, dto.getReason(), dto.getNote(), admin.getEmail(), includesShipping, now);
        }
        orderRepository.save(order);

        BrandPartner brand = targets.get(0).getVariant().getProduct().getBrand();
        return new Claim(claimKey, order.getOrderNumber(), brand.getBrandName(), targets.size(),
                payment.getTransactionId(), refundAmountOf(orderId, targets));
    }

    private String refund(Long orderId, Claim claim) {
        try {
            String refundId = paymentProvider.refundPayment(new RefundCommand(
                    claim.transactionId(),
                    claim.refundAmount(),
                    "Cancelled " + claim.itemCount() + " item(s) from " + claim.brandName()
                            + " on order " + claim.orderNumber(),
                    claim.claimKey())).refundId();
            if (refundId == null || refundId.isBlank()) {
                throw new PaymentException("the provider returned no refund id");
            }
            return refundId;
        } catch (PaymentRejectedException e) {
            try {
                transactionTemplate.executeWithoutResult(s -> releaseClaim(orderId, claim.claimKey()));
            } catch (RuntimeException releaseFailure) {
                log.error("ITEM_CANCEL_RELEASE_FAILED: order {} claim {} — provider rejected the refund but releasing "
                                + "the claim also failed: {}", claim.orderNumber(), claim.claimKey(), releaseFailure.getMessage());
            }
            throw new PaymentException("The payment provider rejected the refund — the items were NOT cancelled: "
                    + e.getMessage(), e);
        } catch (RuntimeException e) {
            log.error("ITEM_CANCEL_AMBIGUOUS: order {} claim {} — refund outcome unknown: {}",
                    claim.orderNumber(), claim.claimKey(), e.getMessage());
            throw new IllegalStateException("The refund outcome is unknown. Check Mollie for claim "
                    + claim.claimKey() + ", then reconcile it (RECORD with the refund id, or RELEASE if no "
                    + "refund exists).");
        }
    }

    /** Settles a claim with a refund that exists at the provider. Shared with reconcile RECORD. */
    void finalizeClaim(Long orderId, String claimKey, String refundId) {
        Order order = orderLock.lockFresh(orderId);
        List<OrderItem> items = claimItems(order, claimKey);
        if (items.stream().anyMatch(OrderItem::isCancellationSettled)) {
            throw new IllegalStateException("Claim " + claimKey + " is already settled.");
        }
        if (ledgerService.isRefundAlreadyRecorded(refundId)) {
            throw new IllegalStateException("Refund id " + refundId + " is already recorded against a different "
                    + "cancellation or return — check for a typo before reconciling claim " + claimKey + ".");
        }
        Long brandId = items.get(0).getBrandId();

        for (OrderItem item : items) {
            item.settleCancellation(refundId);
            productVariantRepository.restoreStock(item.getVariant().getId(), item.getQuantity());
        }
        Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
        payment.setStatus(PaymentStatus.REFUNDED);
        paymentRepository.save(payment);

        ledgerService.recordItemCancellationReversal(order, items, refundId);
        if (Boolean.TRUE.equals(items.get(0).getCancellationIncludesShipping())) {
            ledgerService.reverseShippingForEmptiedBrand(order, brandId, refundId);
        }
        shipmentRollup.syncShipmentStatus(order);
        if (order.getItems().stream().allMatch(OrderItem::isCancellationSettled)) {
            order.setStatus(OrderStatus.CANCELLED);
            discountService.releaseUsageOnce(order);
        }
        orderRepository.save(order);

        BrandPartner brand = items.get(0).getVariant().getProduct().getBrand();
        eventPublisher.publishEvent(new OrderItemsCancelledEvent(
                order.getBuyer().getEmail(), order.getOrderNumber(), brand.getBrandName(), brand.notificationEmail(),
                items.stream().map(i -> i.getProductSnapshotName() + " (" + i.getVariantSnapshotColor() + ", "
                        + i.getVariantSnapshotSize() + ") × " + i.getQuantity()).toList(),
                refundAmountOf(orderId, items), order.getCurrency()));
        log.info("Items {} of order {} cancelled, refund {}", items.stream().map(OrderItem::getId).toList(),
                order.getOrderNumber(), refundId);
    }

    /** Undoes a claim that moved no money. Shared by the definitive-failure path and reconcile RELEASE. */
    void releaseClaim(Long orderId, String claimKey) {
        Order order = orderLock.lockFresh(orderId);
        List<OrderItem> items = claimItems(order, claimKey);
        if (items.stream().anyMatch(OrderItem::isCancellationSettled)) {
            throw new IllegalStateException("Claim " + claimKey + " is settled — it cannot be released.");
        }
        items.forEach(OrderItem::releaseCancellationClaim);
        orderRepository.save(order);

        Long brandId = items.get(0).getBrandId();
        if (orderShipmentRepository.brandShipped(orderId, brandId)) {
            log.error("ITEM_CANCEL_ABORTED_AFTER_SHIPMENT: order {} claim {} — released items {} now read as part "
                            + "of a SHIPPED brand although nothing left for them; resolve by hand.",
                    order.getOrderNumber(), claimKey, items.stream().map(OrderItem::getId).toList());
        }
    }

    List<OrderItem> claimItems(Order order, String claimKey) {
        List<OrderItem> items = order.getItems().stream()
                .filter(i -> claimKey.equals(i.getCancellationClaimKey()))
                .toList();
        if (items.isEmpty()) {
            throw new IllegalStateException("No cancellation claim " + claimKey + " on order " + order.getOrderNumber() + ".");
        }
        return items;
    }

    /** Σ what the customer paid for the items, plus the brand's shipping when the claim empties it (D11/D12). */
    private BigDecimal refundAmountOf(Long orderId, List<OrderItem> items) {
        BigDecimal amount = items.stream().map(OrderItem::getCustomerGrossAfterDiscount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (Boolean.TRUE.equals(items.get(0).getCancellationIncludesShipping())) {
            Long brandId = items.get(0).getBrandId();
            amount = amount.add(orderShippingSnapshotRepository.findByOrderIdOrderByIdAsc(orderId).stream()
                    .filter(s -> brandId.equals(s.getBrandPartnerId()))
                    .map(OrderShippingSnapshot::getAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
        }
        return amount;
    }

    private String alreadyCancelledMessage(OrderItem item) {
        if (item.isCancellationSettled()) {
            return "Item " + item.getId() + " is already cancelled (refund " + item.getRefundTransactionId() + ").";
        }
        return item.isClaimStuck(LocalDateTime.now())
                ? "A cancellation of item " + item.getId() + " is stuck since " + item.getCancelledAt()
                        + " (claim " + item.getCancellationClaimKey() + ") — reconcile it."
                : "A cancellation of item " + item.getId() + " by " + item.getCancelledByAdminEmail()
                        + " is in progress (claim " + item.getCancellationClaimKey() + ").";
    }
}
