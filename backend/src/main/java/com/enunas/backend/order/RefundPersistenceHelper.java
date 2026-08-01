package com.enunas.backend.order;

import com.enunas.backend.discount.DiscountService;
import com.enunas.backend.ledger.LedgerService;
import com.enunas.backend.order.dto.OrderResponseDto;
import com.enunas.backend.payment.Payment;
import com.enunas.backend.payment.PaymentRepository;
import com.enunas.backend.payment.PaymentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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

    /**
     * Persists one BRAND return's refund. The ledger reversal is scoped to that return's brand —
     * reversing against the whole order would claw back payout from brands whose goods never came
     * back. Payment status flips to REFUNDED once any refund lands (Mollie tracks the amounts).
     */
    @Transactional
    OrderResponseDto persist(Long returnOrderId, BigDecimal refundAmount, String mollieRefundId) {
        ReturnOrder returnOrder = returnOrderRepository.findById(returnOrderId)
                .orElseThrow(() -> new IllegalStateException("Return not found: " + returnOrderId));
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
                && allItemsCovered(order, returns);
        if (orderFullyRefunded) {
            order.setStatus(OrderStatus.REFUNDED);
            // A fully-refunded order no longer has a discounted sale to its name — release the
            // usage it reserved at checkout. A partial (single-brand) refund on a multi-brand order
            // deliberately does NOT release it: the order as a whole is still a live, discounted sale.
            if (order.getDiscountCode() != null && !order.isDiscountUsageReleased()) {
                discountService.releaseUsage(order.getDiscountCode());
                order.setDiscountUsageReleased(true);
            }
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

        return OrderResponseDto.withReturns(orderRepository.save(order), returns);
    }

    private boolean allItemsCovered(Order order, List<ReturnOrder> returns) {
        Set<Long> returned = returns.stream()
                .flatMap(r -> r.getItems().stream())
                .map(ri -> ri.getOrderItem().getId())
                .collect(Collectors.toSet());
        return order.getItems().stream().allMatch(i -> returned.contains(i.getId()));
    }
}
