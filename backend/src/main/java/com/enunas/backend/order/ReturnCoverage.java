package com.enunas.backend.order;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Whether every item on an order is accounted for — returned, or cancelled with its refund recorded
 * — the precondition for an order reading REFUNDED. The one copy shared by OrderService and
 * RefundPersistenceHelper, which used to carry identical private versions (spec D19). Keyed on
 * isCancellationSettled, never isCancelled: a stuck claim has not recorded its money (spec D24).
 */
final class ReturnCoverage {

    private ReturnCoverage() {}

    static boolean allItemsCovered(Order order, List<ReturnOrder> returns) {
        Set<Long> returned = returns.stream()
                .flatMap(r -> r.getItems().stream())
                .map(ri -> ri.getOrderItem().getId())
                .collect(Collectors.toSet());
        return order.getItems().stream()
                .allMatch(i -> i.isCancellationSettled() || returned.contains(i.getId()));
    }
}
