package com.enunas.backend.order;

import com.enunas.backend.ledger.LedgerService;
import com.enunas.backend.order.dto.OrderResponseDto;
import com.enunas.backend.payment.Payment;
import com.enunas.backend.payment.PaymentRepository;
import com.enunas.backend.payment.PaymentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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

    @Transactional
    OrderResponseDto persist(Long orderId, BigDecimal refundAmount, String mollieRefundId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalStateException("Order not found: " + orderId));

        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("No payment record for order " + orderId));
        payment.setStatus(PaymentStatus.REFUNDED);
        paymentRepository.save(payment);

        ReturnOrder returnOrder = returnOrderRepository.findByOrder_Id(orderId)
                .orElseThrow(() -> new IllegalStateException("No return found for order: " + order.getOrderNumber()));
        returnOrder.setStatus(ReturnStatus.REFUNDED);
        returnOrder.setRefundedAt(LocalDateTime.now());
        returnOrder.setRefundAmount(refundAmount);
        returnOrderRepository.save(returnOrder);

        ledgerService.recordRefund(order, refundAmount, mollieRefundId);

        order.setStatus(OrderStatus.REFUNDED);
        log.info("Refund of {} persisted for order {}", refundAmount, order.getOrderNumber());
        return OrderResponseDto.from(orderRepository.save(order));
    }
}
