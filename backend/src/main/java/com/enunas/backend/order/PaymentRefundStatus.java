package com.enunas.backend.order;

import com.enunas.backend.payment.Payment;
import com.enunas.backend.payment.PaymentStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * One definition of "how much of this order's payment is on its way back", shared by the return
 * release safety net and the payment-status rule (spec "Payment status rule"): the payment is
 * REFUNDED only once the refunds add up to the whole amount, otherwise it stays PAID.
 */
final class PaymentRefundStatus {

    private PaymentRefundStatus() {}

    /**
     * Σ {@code refundAmount} of the {@code returns} the predicate selects, plus every cancelled item's
     * customer money (claimed or settled — an unsettled claim is money in flight) plus the brand's
     * shipping where its cancellation emptied the brand.
     */
    static BigDecimal refundedSoFar(Order order, List<ReturnOrder> returns, Predicate<ReturnOrder> counts,
                                    OrderShippingSnapshotRepository snapshots) {
        BigDecimal sum = returns.stream().filter(counts).map(ReturnOrder::getRefundAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        for (OrderItem item : order.getItems()) {
            if (item.isCancelled()) sum = sum.add(item.getCustomerGrossAfterDiscount());
        }
        List<Long> emptiedBrands = order.getItems().stream()
                .filter(i -> Boolean.TRUE.equals(i.getCancellationIncludesShipping()))
                .map(OrderItem::getBrandId).distinct().toList();
        for (OrderShippingSnapshot s : snapshots.findByOrderIdOrderByIdAsc(order.getId())) {
            if (emptiedBrands.contains(s.getBrandPartnerId())) sum = sum.add(s.getAmount());
        }
        return sum;
    }

    static void apply(Payment payment, BigDecimal refundedSoFar) {
        if (refundedSoFar.compareTo(payment.getAmount()) >= 0) payment.setStatus(PaymentStatus.REFUNDED);
    }
}
