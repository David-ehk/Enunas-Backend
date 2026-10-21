package com.enunas.backend.order;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Whether every item on an order is accounted for — returned, or cancelled with its refund recorded
 * — the precondition for an order reading REFUNDED. The one copy shared by OrderService and
 * the cancellation services (spec D19). Keyed on
 * isCancellationSettled, never isCancelled: a stuck claim has not recorded its money (spec D24).
 */
final class ReturnCoverage {

    private ReturnCoverage() {}

    /** Quantity-aware: only REFUNDED returns count, and a line is covered once all its units are refunded. */
    static boolean allItemsCovered(Order order, List<ReturnOrder> returns) {
        Map<Long, Integer> refundedUnits = returns.stream()
                .filter(r -> r.getStatus() == ReturnStatus.REFUNDED)
                .flatMap(r -> r.getItems().stream())
                .collect(Collectors.groupingBy(ri -> ri.getOrderItem().getId(),
                        Collectors.summingInt(ReturnItem::getQuantityReturned)));
        return order.getItems().stream().allMatch(i -> i.isCancellationSettled()
                || refundedUnits.getOrDefault(i.getId(), 0) >= i.getQuantity());
    }
}
