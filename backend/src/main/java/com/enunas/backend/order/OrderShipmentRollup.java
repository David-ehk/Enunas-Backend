package com.enunas.backend.order;

import com.enunas.backend.brandpartner.BrandPartner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Per-brand shipment progress rolled up onto {@link Order#getStatus()}; shared by order and item-cancellation flows. */
@Component
@RequiredArgsConstructor
class OrderShipmentRollup {

    private final OrderShipmentRepository orderShipmentRepository;

    /**
     * Rolls up per-brand {@link OrderShipment} progress onto {@link Order#getStatus()} — mirrors
     * {@code OrderService.syncOrderStatus} for returns. Only advances the order while it is genuinely in the
     * shipping phase (PAID → PARTIALLY_SHIPPED → SHIPPED) and only ever forward: an order already
     * moved into a RETURN_-prefixed status, REFUNDED, or CANCELLED, or an admin's own order-wide
     * SHIPPING_PROBLEM/AWAITING_ADMIN/MANUAL_REVIEW escalation, is left untouched — those remain a deliberate,
     * order-wide admin lever, never overridden by one brand's local shipment event.
     *
     * <p>Also re-run by OrderItemCancellationService after a cancellation empties a brand (spec D27);
     * it only ever moves forward, so a re-run is always safe.
     */
    Order syncShipmentStatus(Order order) {
        if (shippingPhaseRank(order.getStatus()) < 0) return order;

        Set<Long> orderBrandIds = brandsOnOrder(order).keySet();
        if (orderBrandIds.isEmpty()) return order;

        Map<Long, ShipmentStatus> byBrand = orderShipmentRepository.findByOrder_IdOrderByIdAsc(order.getId())
                .stream()
                .collect(Collectors.toMap(s -> s.getBrand().getId(), OrderShipment::getStatus));

        boolean allShipped = orderBrandIds.stream().allMatch(id -> byBrand.get(id) == ShipmentStatus.SHIPPED);
        // Specifically SHIPPED, not "has a row at all" — a brand that only reported a PROBLEM has a
        // row but has dispatched nothing, and must never make the order read as partially shipped.
        boolean anyShipped = orderBrandIds.stream().anyMatch(id -> byBrand.get(id) == ShipmentStatus.SHIPPED);
        OrderStatus computed = allShipped ? OrderStatus.SHIPPED
                : anyShipped ? OrderStatus.PARTIALLY_SHIPPED
                : OrderStatus.PAID;

        if (shippingPhaseRank(computed) > shippingPhaseRank(order.getStatus())) {
            order.setStatus(computed);
        }
        return order;
    }

    /** True once any brand on this order has actually dispatched its items. */
    boolean anyBrandHasShipped(Order order) {
        return orderShipmentRepository.findByOrder_IdOrderByIdAsc(order.getId()).stream()
                .anyMatch(s -> s.getStatus() == ShipmentStatus.SHIPPED);
    }

    /** A brand whose every item on this order was cancelled has nothing to ship or report on (spec D18). */
    void assertBrandHasActiveItems(Order order, BrandPartner brand) {
        if (order.activeItemsOf(brand.getId()).isEmpty()) {
            throw new IllegalStateException("All of " + brand.getBrandName() + "'s items on order "
                    + order.getOrderNumber() + " were cancelled — there is nothing to ship.");
        }
    }

    /**
     * Every brand with at least one line item on this order, in first-seen order. Read from the
     * items' own {@code variant.product.brand} — already loaded alongside the order — so a caller
     * that needs the entity, not just the id, never has to look it back up.
     */
    Map<Long, BrandPartner> brandsOnOrder(Order order) {
        Map<Long, BrandPartner> brands = new LinkedHashMap<>();
        for (OrderItem item : order.getItems()) {
            if (item.isCancelled()) continue; // nothing left to ship for it (spec D9)
            BrandPartner brand = item.getVariant().getProduct().getBrand();
            if (brand != null && brand.getId() != null) {
                brands.putIfAbsent(brand.getId(), brand);
            }
        }
        return brands;
    }

    /** PAID < PARTIALLY_SHIPPED < SHIPPED, -1 for anything outside the shipping phase (see
     *  {@link #syncShipmentStatus}, which never runs outside this band). */
    private int shippingPhaseRank(OrderStatus status) {
        return switch (status) {
            case PAID -> 0;
            case PARTIALLY_SHIPPED -> 1;
            case SHIPPED -> 2;
            default -> -1;
        };
    }
}
