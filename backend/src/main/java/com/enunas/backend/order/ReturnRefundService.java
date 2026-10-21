package com.enunas.backend.order;

import com.enunas.backend.discount.DiscountService;
import com.enunas.backend.exception.OrderNotFoundException;
import com.enunas.backend.exception.PaymentException;
import com.enunas.backend.exception.PaymentRejectedException;
import com.enunas.backend.exception.PaymentRetryLaterException;
import com.enunas.backend.ledger.LedgerService;
import com.enunas.backend.order.dto.OrderRefundPreviewDto;
import com.enunas.backend.order.dto.OrderResponseDto;
import com.enunas.backend.order.dto.ReconcileReturnRefundDto;
import com.enunas.backend.order.dto.RefundOverviewRowDto;
import com.enunas.backend.order.dto.RefundPreviewDto;
import com.enunas.backend.payment.Payment;
import com.enunas.backend.payment.PaymentProvider;
import com.enunas.backend.payment.PaymentRepository;
import com.enunas.backend.payment.PaymentStatus;
import com.enunas.backend.payment.RefundCommand;
import com.enunas.backend.payment.RefundStatusResult;
import com.enunas.backend.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Releases a return's refund (spec docs/superpowers/specs/2026-10-07-partial-returns-exact-refunds-design.md,
 * "Release"). Same shape as {@link OrderItemCancellationService}: claim (short write transaction under
 * the orders row lock, stores the whole decision on the return) → Mollie (no transaction) → finalize
 * (records the refund id and the ledger). The refund then stays PENDING until the provider confirms it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReturnRefundService {

    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final ReturnOrderRepository returnOrderRepository;
    private final OrderShippingSnapshotRepository orderShippingSnapshotRepository;
    private final LedgerService ledgerService;
    private final DiscountService discountService;
    private final PaymentProvider paymentProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    /** What the refund step needs from a committed claim. */
    private record Claim(String claimKey, Long orderId, String orderNumber, String returnNumber,
                         String transactionId, BigDecimal total, String idempotencyKey, boolean resend) {}

    @PreAuthorize("hasRole('ADMIN')")
    public OrderResponseDto release(String returnNumber, String overrideReason, User admin) {
        Claim claim = transactionTemplate.execute(s -> claim(returnNumber, overrideReason, admin));
        String refundId = refund(claim);
        try {
            transactionTemplate.executeWithoutResult(s -> finalizeRefund(claim, refundId));
        } catch (RuntimeException e) {
            log.error("RETURN_REFUND_RECORDING_FAILED: return {} claim {} — refund {} succeeded at the provider but "
                            + "was not recorded; reconcile it: {}",
                    claim.returnNumber(), claim.claimKey(), refundId, e.getMessage());
            throw e;
        }
        settleNow(claim);
        return orderService.getOrderById(claim.orderId());
    }

    /**
     * One status poll after a refund was recorded: a refund that is final at once, or whose webhook
     * arrived before the finalize commit and was skipped, would otherwise stay PENDING for good.
     */
    private void settleNow(Claim claim) {
        try {
            onProviderRefundStatus(claim.transactionId());
        } catch (RuntimeException e) {
            log.error("RETURN_REFUND_STATUS_FAILED: return {}: {}", claim.returnNumber(), e.getMessage());
        }
    }

    /**
     * Resolves a stuck claim by an admin's explicit decision. RECORD settles it with a refund found at
     * Mollie (after its payment and amount are verified); RELEASE undoes it. Both reuse the release steps.
     */
    @PreAuthorize("hasRole('ADMIN')")
    public OrderResponseDto reconcile(String returnNumber, ReconcileReturnRefundDto dto, User admin) {
        if (dto.action() == ReconcileReturnRefundDto.Action.REFRESH) return refresh(returnNumber);
        boolean record = dto.action() == ReconcileReturnRefundDto.Action.RECORD;
        if (record && (dto.refundId() == null || dto.refundId().isBlank())) {
            throw new IllegalArgumentException("RECORD needs the refund id from Mollie.");
        }
        Claim claim = transactionTemplate.execute(s -> stuckClaim(returnNumber));
        if (record) {
            verifyAtProvider(claim, dto.refundId());
            transactionTemplate.executeWithoutResult(s -> {
                stuckClaim(returnNumber);   // re-check under the lock: nothing moved while we asked Mollie
                finalizeRefund(claim, dto.refundId());
            });
            settleNow(claim);
        } else {
            transactionTemplate.executeWithoutResult(s -> clearClaim(stuckClaim(returnNumber)));
        }
        log.warn("RETURN_REFUND_RECONCILED: admin {} {} claim {} on return {}{}", admin.getEmail(), dto.action(),
                claim.claimKey(), returnNumber, record ? " with refund " + dto.refundId() : "");
        return orderService.getOrderById(claim.orderId());
    }

    /** Re-polls the provider for a PENDING refund; the status becomes REFUNDED, FAILED, or stays PENDING. */
    private OrderResponseDto refresh(String returnNumber) {
        Claim claim = transactionTemplate.execute(s -> {
            ReturnOrder ret = orderService.lockedReturn(returnNumber);
            if (ret.getRefundState() != ReturnRefundState.PENDING) {
                throw new IllegalStateException("Return " + returnNumber + " has no pending refund to refresh.");
            }
            Payment payment = paymentRepository.findByOrderId(ret.getOrder().getId()).orElseThrow();
            return claimOf(ret, ret.getOrder(), payment, false);
        });
        pollRefund(claim.transactionId(), returnNumber,
                returnOrderRepository.findByReturnNumber(returnNumber).orElseThrow().getRefundTransactionId());
        return orderService.getOrderById(claim.orderId());
    }

    /** Admin overview of returns by refund state: DUE, IN_PROGRESS, PENDING, FAILED or REFUNDED. */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public Page<RefundOverviewRowDto> overview(String state, Pageable pageable) {
        Page<ReturnOrder> page = switch (state == null ? "" : state) {
            case "DUE" -> returnOrderRepository.findRefundDue(pageable);
            case "IN_PROGRESS" -> returnOrderRepository.findRefundInProgress(pageable);
            case "PENDING", "FAILED", "REFUNDED" ->
                    returnOrderRepository.findByRefundState(ReturnRefundState.valueOf(state), pageable);
            default -> throw new IllegalArgumentException(
                    "state must be one of DUE, IN_PROGRESS, PENDING, FAILED, REFUNDED");
        };
        boolean due = "DUE".equals(state);   // nothing is stored before release: show what release would send
        return page.map(r -> {
            RefundPreviewDto p = null;
            if (due) {
                try {
                    p = preview(r);
                } catch (IllegalStateException e) {   // legacy/inconsistent snapshot: blank this row only
                    log.warn("REFUND_PREVIEW_UNAVAILABLE: return {}: {}", r.getReturnNumber(), e.getMessage());
                }
            }
            return new RefundOverviewRowDto(r.getReturnNumber(), r.getOrder().getOrderNumber(),
                r.getBrand().getId(), r.getBrand().getBrandName(), state,
                due ? (p == null ? null : p.itemsAmount()) : r.getRefundItemsAmount(),
                due ? (p == null ? null : p.shippingAmount()) : r.getRefundShippingAmount(),
                due ? (p == null ? null : p.total()) : r.getRefundAmount(), r.getRefundReleasedBy(),
                r.getRefundOverrideReason(), r.getRefundClaimKey(), r.getRefundTransactionId(), r.getReceivedAt(),
                r.getReceivedBy(), r.getRefundClaimedAt(), r.getRefundedAt());
        });
    }

    /** Read-only: the refund {@link #claim} would take for this return, from the same quote and shipping code. */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public RefundPreviewDto preview(String returnNumber) {
        return preview(returnOrderRepository.findByReturnNumber(returnNumber)
                .orElseThrow(() -> new IllegalArgumentException("Return not found: " + returnNumber)));
    }

    /** Previews every return of the order that is not refunded yet. */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public OrderRefundPreviewDto previewOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException("Order not found: " + orderId));
        List<RefundPreviewDto> previews = returnOrderRepository.findByOrder_IdOrderByIdAsc(orderId).stream()
                .filter(r -> r.getStatus() != ReturnStatus.REFUNDED).map(this::preview).toList();
        return new OrderRefundPreviewDto(orderId, order.getOrderNumber(), previews,
                previews.stream().map(RefundPreviewDto::total).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private RefundPreviewDto preview(ReturnOrder ret) {
        Order order = ret.getOrder();
        List<ReturnOrder> claimed = returnOrderRepository.findClaimedByOrderId(order.getId());
        boolean stored = ret.getRefundClaimKey() != null;
        RefundQuote quote = stored
                ? quote(ret, claimed, ret.getRefundClaimedAt(), ret.getRefundShippingAmount())
                : quote(ret, claimed, LocalDateTime.now(), shippingFor(order, ret, claimed));
        List<RefundPreviewDto.Line> lines = new java.util.ArrayList<>();
        for (int i = 0; i < quote.lines().size(); i++) {
            OrderItem item = ret.getItems().get(i).getOrderItem();
            lines.add(new RefundPreviewDto.Line(item.getId(), item.getProductSnapshotName(),
                    item.getVariantSnapshotColor(), item.getVariantSnapshotSize(), quote.lines().get(i).units(),
                    item.getQuantity(), quote.lines().get(i).customerAmount()));
        }
        return new RefundPreviewDto(ret.getReturnNumber(), ret.getStatus(), ret.getRefundState(),
                ret.getBrand().getId(), ret.getBrand().getBrandName(), lines,
                stored ? ret.getRefundItemsAmount() : quote.itemsAmount(),
                stored ? ret.getRefundShippingAmount() : quote.shippingAmount(),
                stored ? ret.getRefundAmount() : quote.total(), stored);
    }

    /** The claim of a return whose attempt is in flight (state null) and older than {@link OrderItem#STUCK_AFTER}. */
    private Claim stuckClaim(String returnNumber) {
        ReturnOrder ret = orderService.lockedReturn(returnNumber);
        if (ret.getRefundClaimKey() == null || ret.getRefundState() != null || ret.getRefundClaimedAt() == null
                || ret.getRefundClaimedAt().isAfter(LocalDateTime.now().minus(OrderItem.STUCK_AFTER))) {
            throw new IllegalStateException("Return " + returnNumber + " has no refund claim that is stuck — it is "
                    + "still in progress (reconcile only after " + OrderItem.STUCK_AFTER.toMinutes() + " minutes).");
        }
        Payment payment = paymentRepository.findByOrderId(ret.getOrder().getId()).orElseThrow();
        return claimOf(ret, ret.getOrder(), payment, ret.getRefundResendCount() > 0);
    }

    private void verifyAtProvider(Claim claim, String refundId) {
        RefundStatusResult status;
        try {
            status = paymentProvider.getRefundStatus(claim.transactionId(), refundId);
        } catch (PaymentRetryLaterException e) {
            throw e;
        } catch (PaymentRejectedException e) {
            throw new IllegalStateException("Mollie has no refund " + refundId + " on this payment: " + e.getMessage());
        }
        if (!claim.transactionId().equals(status.paymentId())) {
            throw new IllegalStateException("Refund " + refundId + " belongs to payment " + status.paymentId()
                    + ", not " + claim.transactionId() + ".");
        }
        if (status.amount() == null || status.amount().compareTo(claim.total()) != 0) {
            throw new IllegalStateException("Refund " + refundId + " is for " + status.amount()
                    + ", the claim is for " + claim.total() + ".");
        }
        if (status.isFinalFailure()) {
            throw new IllegalStateException("Refund " + refundId + " has " + status.status()
                    + " at Mollie — it moved no money, so it cannot be recorded. Use RELEASE instead.");
        }
    }

    /**
     * Provider callback: settles every PENDING refund on this payment. Each return is handled on its
     * own and errors are logged, never rethrown — a refund hiccup must not break the payment webhook.
     */
    public void onProviderRefundStatus(String paymentTransactionId) {
        Payment payment = paymentRepository.findByTransactionId(paymentTransactionId).orElse(null);
        if (payment == null) return;
        Long orderId = payment.getOrder().getId();
        for (ReturnOrder r : returnOrderRepository.findByOrder_IdOrderByIdAsc(orderId)) {
            if (r.getRefundState() != ReturnRefundState.PENDING) continue;
            try {
                pollRefund(paymentTransactionId, r.getReturnNumber(), r.getRefundTransactionId());
            } catch (RuntimeException e) {
                log.error("RETURN_REFUND_STATUS_FAILED: return {}: {}", r.getReturnNumber(), e.getMessage());
            }
        }
    }

    private void pollRefund(String paymentTransactionId, String rn, String refundId) {
        RefundStatusResult status = paymentProvider.getRefundStatus(paymentTransactionId, refundId);
        if (status.isFinalSuccess() || status.isFinalFailure()) {
            transactionTemplate.executeWithoutResult(s -> settle(rn, status.isFinalSuccess()));
        }
    }

    private Claim claim(String returnNumber, String overrideReason, User admin) {
        ReturnOrder ret = orderService.lockedReturn(returnNumber);
        Order order = ret.getOrder();
        Long orderId = order.getId();
        if (ret.getBrand() == null) {
            throw new IllegalStateException("Return " + returnNumber + " has no brand — cannot scope the refund");
        }
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("No payment record for order " + orderId));

        if (ret.getRefundState() == ReturnRefundState.FAILED) {
            // Resend: same claim, same amounts, new Mollie refund. State goes back to "in flight" (null)
            // so a second overlapping resend sees a claim in progress and 409s.
            ret.setRefundResendCount(ret.getRefundResendCount() + 1);
            ret.setRefundState(null);
            ret.setRefundClaimedAt(LocalDateTime.now());   // "stuck" is judged by the latest attempt
            returnOrderRepository.save(ret);
            return claimOf(ret, order, payment, true);
        }
        if (ret.getRefundClaimKey() != null) {
            throw new IllegalStateException("A refund for return " + returnNumber + " is already in progress or released.");
        }
        boolean override = ret.getStatus() == ReturnStatus.APPROVED;
        if (override && (overrideReason == null || overrideReason.isBlank())) {
            throw new IllegalStateException("Ware noch nicht bestätigt — override needs a reason");
        }
        if (!override && ret.getStatus() != ReturnStatus.RECEIVED) {
            throw new IllegalStateException("Can only refund a received return. Current: " + ret.getStatus());
        }
        if (order.hasUnsettledCancellationClaim()) {
            throw new IllegalStateException("Order " + order.getOrderNumber()
                    + " has an item cancellation in progress — resolve it first.");
        }

        List<ReturnOrder> claimed = returnOrderRepository.findClaimedByOrderId(orderId);
        LocalDateTime now = LocalDateTime.now();
        BigDecimal shipping = shippingFor(order, ret, claimed);
        RefundQuote quote = quote(ret, claimed, now, shipping);

        // Safety net: open claims count, whatever their state — a stuck claim may still have refunded.
        BigDecimal claimedSoFar = PaymentRefundStatus.refundedSoFar(order, claimed,
                r -> !r.getId().equals(ret.getId()), orderShippingSnapshotRepository);
        if (claimedSoFar.add(quote.total()).compareTo(payment.getAmount()) > 0) {
            log.error("RETURN_REFUND_OVER_TOTAL: return {} would refund {} on top of {} already claimed; payment is {}",
                    returnNumber, quote.total(), claimedSoFar, payment.getAmount());
            throw new IllegalStateException("Refunding return " + returnNumber + " would exceed the payment amount of "
                    + "order " + order.getOrderNumber() + ".");
        }

        ret.setRefundClaimKey("return-refund-" + returnNumber + "-" + UUID.randomUUID());
        ret.setRefundItemsAmount(quote.itemsAmount());
        ret.setRefundShippingAmount(quote.shippingAmount());
        ret.setRefundAmount(quote.total());
        ret.setRefundClaimedAt(now);
        ret.setRefundReleasedBy(admin.getEmail());
        ret.setRefundOverrideReason(override ? overrideReason.trim() : null);
        returnOrderRepository.save(ret);
        return claimOf(ret, order, payment, false);
    }

    private Claim claimOf(ReturnOrder ret, Order order, Payment payment, boolean resend) {
        String key = ret.getRefundClaimKey();
        return new Claim(key, order.getId(), order.getOrderNumber(), ret.getReturnNumber(), payment.getTransactionId(),
                ret.getRefundAmount(), resend ? key + "-r" + ret.getRefundResendCount() : key, resend);
    }

    /**
     * This return's units priced after every claim that was taken before it (the lock serializes claims).
     * "Before" by claimedAt is unambiguous because the V15 index allows one active return per (order, brand).
     */
    private RefundQuote quote(ReturnOrder ret, List<ReturnOrder> claimed, LocalDateTime claimedAt, BigDecimal shipping) {
        List<ReturnOrder> before = claimed.stream()
                .filter(r -> !r.getId().equals(ret.getId())
                        && (r.getRefundClaimedAt() == null || !r.getRefundClaimedAt().isAfter(claimedAt)))
                .toList();
        return RefundQuote.of(ret.getItems().stream().map(ri -> {
            long already = before.stream().flatMap(r -> r.getItems().stream())
                    .filter(o -> o.getOrderItem().getId().equals(ri.getOrderItem().getId()))
                    .mapToInt(ReturnItem::getQuantityReturned).sum();
            return RefundQuote.LineInput.of(ri.getOrderItem(), (int) already, ri.getQuantityReturned());
        }).toList(), shipping);
    }

    /**
     * The brand's shipping goes back once: on the first release after every non-cancelled unit of the
     * brand is on a return, unless a reversal (cancellation) or another claim already carries it.
     */
    private BigDecimal shippingFor(Order order, ReturnOrder ret, List<ReturnOrder> claimed) {
        Long orderId = order.getId();
        Long brandId = ret.getBrand().getId();
        boolean alreadyClaimed = claimed.stream().anyMatch(r -> !r.getId().equals(ret.getId())
                && r.getBrand() != null && brandId.equals(r.getBrand().getId())
                && r.getRefundShippingAmount() != null && r.getRefundShippingAmount().signum() > 0);
        if (alreadyClaimed || ledgerService.isShippingReversed(orderId, brandId)) return BigDecimal.ZERO;
        Map<Long, Integer> returned = returnOrderRepository.returnedUnits(orderId);
        boolean allReturned = order.activeItemsOf(brandId).stream()
                .allMatch(i -> returned.getOrDefault(i.getId(), 0) >= i.getQuantity());
        if (!allReturned) return BigDecimal.ZERO;
        return orderShippingSnapshotRepository.brandShippingTotal(orderId, brandId);
    }

    private String refund(Claim claim) {
        try {
            String refundId = paymentProvider.refundPayment(new RefundCommand(
                    claim.transactionId(),
                    claim.total(),
                    "Refund for return " + claim.returnNumber() + " (order " + claim.orderNumber() + ")",
                    claim.idempotencyKey(),
                    Map.of("claimKey", claim.claimKey(), "returnNumber", claim.returnNumber()))).refundId();
            if (refundId == null || refundId.isBlank()) {
                throw new PaymentException("the provider returned no refund id");
            }
            return refundId;
        } catch (PaymentRetryLaterException e) {
            abandon(claim);
            log.info("RETURN_REFUND_RETRY_LATER: return {} claim {} — provider is rate limiting, claim released",
                    claim.returnNumber(), claim.claimKey());
            throw e;
        } catch (PaymentRejectedException e) {
            abandon(claim);
            throw new PaymentException("The payment provider rejected the refund — nothing was refunded: "
                    + e.getMessage(), e);
        } catch (RuntimeException e) {
            log.error("RETURN_REFUND_AMBIGUOUS: return {} claim {} — refund outcome unknown: {}",
                    claim.returnNumber(), claim.claimKey(), e.getMessage());
            throw new IllegalStateException("The refund outcome is unknown. Check Mollie for claim "
                    + claim.claimKey() + " and reconcile return " + claim.returnNumber() + ".");
        }
    }

    /** Undoes a claim that moved no money: clears a fresh claim, puts a resend back to FAILED. */
    private void abandon(Claim claim) {
        try {
            transactionTemplate.executeWithoutResult(s -> clearClaim(claim));
        } catch (RuntimeException e) {
            log.error("RETURN_REFUND_RELEASE_FAILED: return {} claim {} — provider rejected the refund but releasing "
                    + "the claim also failed: {}", claim.returnNumber(), claim.claimKey(), e.getMessage());
        }
    }

    private void clearClaim(Claim claim) {
        ReturnOrder ret = orderService.lockedReturn(claim.returnNumber());
        if (claim.resend()) {
            ret.setRefundState(ReturnRefundState.FAILED);
        } else {
            ret.setRefundClaimKey(null);
            ret.setRefundItemsAmount(null);
            ret.setRefundShippingAmount(null);
            ret.setRefundAmount(null);
            ret.setRefundClaimedAt(null);
            ret.setRefundReleasedBy(null);
            ret.setRefundOverrideReason(null);
        }
        returnOrderRepository.save(ret);
    }

    private void finalizeRefund(Claim claim, String refundId) {
        ReturnOrder ret = orderService.lockedReturn(claim.returnNumber());
        Order order = ret.getOrder();
        if (!claim.claimKey().equals(ret.getRefundClaimKey())) {
            throw new IllegalStateException("Claim " + claim.claimKey() + " is no longer on return " + claim.returnNumber());
        }
        if (ret.getRefundTransactionId() != null && !claim.resend()) {
            throw new IllegalStateException("Return " + claim.returnNumber() + " already has refund "
                    + ret.getRefundTransactionId());
        }
        if (ledgerService.isRefundAlreadyRecorded(refundId)) {
            throw new IllegalStateException("Refund id " + refundId + " is already recorded.");
        }
        if (!claim.resend()) {
            // Store-and-read: the stored shipping and the claims taken before this one reproduce the claim's quote.
            RefundQuote quote = quote(ret, returnOrderRepository.findClaimedByOrderId(order.getId()),
                    ret.getRefundClaimedAt(), ret.getRefundShippingAmount());
            if (quote.total().compareTo(ret.getRefundAmount()) != 0) {
                throw new IllegalStateException("Recomputed refund " + quote.total() + " differs from the claimed "
                        + ret.getRefundAmount() + " for return " + claim.returnNumber());
            }
            ledgerService.recordReturnReversal(order, ret.getBrand().getId(), quote, refundId);
        }
        ret.setRefundTransactionId(refundId);
        ret.setRefundState(ReturnRefundState.PENDING);
        returnOrderRepository.save(ret);
        applyPaymentRule(order);
        orderRepository.save(orderService.syncOrderStatus(order));
    }

    /** PENDING → REFUNDED (provider confirmed) or FAILED. Re-checks under the lock, so replays are no-ops. */
    private void settle(String returnNumber, boolean success) {
        ReturnOrder ret = orderService.lockedReturn(returnNumber);
        if (ret.getRefundState() != ReturnRefundState.PENDING) return;
        Order order = ret.getOrder();
        if (!success) {
            ret.setRefundState(ReturnRefundState.FAILED);
            returnOrderRepository.save(ret);
            applyPaymentRule(order);
            log.error("RETURN_REFUND_FAILED: return {} refund {} failed at the provider — resend it",
                    returnNumber, ret.getRefundTransactionId());
            return;
        }
        ret.setRefundState(ReturnRefundState.REFUNDED);
        ret.setStatus(ReturnStatus.REFUNDED);
        ret.setRefundedAt(LocalDateTime.now());
        returnOrderRepository.save(ret);
        applyPaymentRule(order);
        orderRepository.save(orderService.syncOrderStatus(order));
        // A fully refunded order no longer has a discounted sale to its name; a partial refund keeps it.
        if (order.getStatus() == OrderStatus.REFUNDED) discountService.releaseUsageOnce(order);
        eventPublisher.publishEvent(new RefundCompletedEvent(order.getBuyer().getEmail(), order.getOrderNumber(),
                returnNumber, ret.getBrand().getBrandName(), ret.getRefundAmount(), order.getCurrency()));
    }

    private void applyPaymentRule(Order order) {
        Payment payment = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        BigDecimal refunded = PaymentRefundStatus.refundedSoFar(order,
                returnOrderRepository.findClaimedByOrderId(order.getId()),
                r -> r.getRefundState() == ReturnRefundState.PENDING || r.getRefundState() == ReturnRefundState.REFUNDED,
                orderShippingSnapshotRepository);
        PaymentRefundStatus.apply(payment, refunded);
        // A failed refund can leave REFUNDED unmet; the cancellation path never downgrades, so only this one does.
        if (payment.getStatus() == PaymentStatus.REFUNDED && refunded.compareTo(payment.getAmount()) < 0) {
            payment.setStatus(PaymentStatus.PAID);
        }
        paymentRepository.save(payment);
    }
}
