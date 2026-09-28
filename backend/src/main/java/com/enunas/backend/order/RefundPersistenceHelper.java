package com.enunas.backend.order;

import com.enunas.backend.discount.DiscountService;
import com.enunas.backend.ledger.LedgerService;
import com.enunas.backend.media.storage.MediaUrlResolver;
import com.enunas.backend.order.dto.OrderResponseDto;
import com.enunas.backend.payment.Payment;
import com.enunas.backend.payment.PaymentRepository;
import com.enunas.backend.payment.PaymentStatus;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Holds the transactional DB work for a refund, isolated from the upstream Mollie HTTP call.
 * Keeping this in a separate Spring bean (not OrderService itself) ensures the
 * @Transactional proxy is active — Spring AOP cannot intercept self-calls.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class RefundPersistenceHelper {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final ReturnOrderRepository returnOrderRepository;
    private final LedgerService ledgerService;
    private final DiscountService discountService;
    private final ApplicationEventPublisher eventPublisher;
    private final MediaUrlResolver mediaUrlResolver;
    private final EntityManager entityManager;

    /**
     * Persists one BRAND return's refund. The ledger reversal is scoped to that return's brand —
     * reversing against the whole order would claw back payout from brands whose goods never came
     * back. Payment status flips to REFUNDED once any refund lands (Mollie tracks the amounts).
     */
    @Transactional
    OrderResponseDto persist(Long returnOrderId, BigDecimal refundAmount, String mollieRefundId) {
        // See StaleSessionGuard: without this, a plain findById here could hand back the SAME
        // managed ReturnOrder instance processRefund already validated as RECEIVED, even after a
        // concurrent refund committed and flipped it to REFUNDED while processRefund's outbound
        // Mollie call was still in flight.
        StaleSessionGuard.clear(entityManager);
        ReturnOrder returnOrder = returnOrderRepository.findById(returnOrderId)
                .orElseThrow(() -> new IllegalStateException("Return not found: " + returnOrderId));

        // Re-check under the fresh read, BEFORE any write, so throwing here is safe (nothing to roll
        // back) and a return already refunded by a concurrent winner is never refunded twice.
        if (returnOrder.getStatus() != ReturnStatus.RECEIVED) {
            throw new IllegalStateException("Return " + returnOrder.getReturnNumber() + " is no longer RECEIVED "
                    + "(now " + returnOrder.getStatus() + ") — refusing to persist a duplicate refund.");
        }

        Order order = returnOrder.getOrder();

        Payment payment = paymentRepository.findByOrderId(order.getId())
                .orElseThrow(() -> new IllegalStateException("No payment record for order " + order.getId()));
        payment.setStatus(PaymentStatus.REFUNDED);
        paymentRepository.save(payment);

        returnOrder.setStatus(ReturnStatus.REFUNDED);
        returnOrder.setRefundedAt(LocalDateTime.now());
        returnOrder.setRefundAmount(refundAmount);
        returnOrderRepository.save(returnOrder);

        Long brandId = returnOrder.getBrand() != null ? returnOrder.getBrand().getId() : null;
        if (brandId == null) {
            throw new IllegalStateException(
                    "Return " + returnOrder.getReturnNumber() + " has no brand — cannot scope the ledger reversal");
        }
        ledgerService.recordRefund(order, brandId, refundAmount, mollieRefundId);

        // The order is only REFUNDED once every brand return is refunded and every item is covered;
        // otherwise it stays at RETURN_RECEIVED while the remaining brands are worked through.
        List<ReturnOrder> returns = returnOrderRepository.findByOrder_IdOrderByIdAsc(order.getId());
        boolean orderFullyRefunded = returns.stream().allMatch(r -> r.getStatus() == ReturnStatus.REFUNDED)
                && ReturnCoverage.allItemsCovered(order, returns);
        if (orderFullyRefunded) {
            order.setStatus(OrderStatus.REFUNDED);
            // A fully-refunded order no longer has a discounted sale to its name — release the
            // usage it reserved at checkout. A partial (single-brand) refund on a multi-brand order
            // deliberately does NOT release it: the order as a whole is still a live, discounted sale.
            discountService.releaseUsageOnce(order);
        }

        log.info("Refund of {} persisted for return {} (order {}, brand {})",
                refundAmount, returnOrder.getReturnNumber(), order.getOrderNumber(), brandId);

        // AFTER_COMMIT so a mail failure can never roll back an already-persisted refund.
        eventPublisher.publishEvent(new RefundCompletedEvent(
                order.getBuyer().getEmail(),
                order.getOrderNumber(),
                returnOrder.getReturnNumber(),
                returnOrder.getBrand() != null ? returnOrder.getBrand().getBrandName() : null,
                refundAmount,
                order.getCurrency()));

        return OrderResponseDto.withReturns(orderRepository.save(order), returns, mediaUrlResolver);
    }
}
