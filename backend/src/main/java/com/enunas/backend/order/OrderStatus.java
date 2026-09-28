package com.enunas.backend.order;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum OrderStatus {
    PENDING,           // placed, awaiting payment
    PAID,              // payment confirmed
    PARTIALLY_SHIPPED, // at least one brand on this (possibly multi-brand) order has shipped, not all — see OrderShipment
    SHIPPED,           // every brand on this order has shipped — see OrderShipmentRollup.syncShipmentStatus
    DELIVERED,         // received by customer

    // Neu für Probleme:
    SHIPPING_PROBLEM,     // BrandPartner hat Problem gemeldet
    AWAITING_ADMIN,       // Admin muss entscheiden
    MANUAL_REVIEW,        // Manuelle Prüfung nötig

    RETURN_REQUESTED,  // customer initiated return
    RETURN_APPROVED,   // admin approved; return label issued
    RETURN_RECEIVED,   // admin received goods back; stock restored
    REFUNDED,          // money returned to customer
    CANCELLED;         // never fulfilled — expired/cancelled while PENDING, OR cancelled after payment
                       // (admin cancel of a PAID order, payment landing late / on a sold-out line).
                       // Status alone does NOT say whether money was taken: read Payment.paidAt.

    /**
     * Every status in which the order stands as a completed sale — i.e. everything except PENDING
     * (never paid) and CANCELLED (never fulfilled; any money captured for it is reversed in full).
     * REFUNDED is deliberately
     * included: the order was placed and paid for, and the refund is recorded separately. This is
     * the same rule {@code OrderRepository.getOrderStatsByBuyer} and
     * {@code OrderItemRepository.findVat22fLineItems} apply, stated once so the three cannot drift.
     *
     * <p>Defined as the complement of the two unpaid statuses rather than by listing the paid ones.
     * A hand-maintained list is what went wrong before: PARTIALLY_SHIPPED (added in V27) was never
     * added to the customer brand-spending list, and the three escalation statuses
     * (SHIPPING_PROBLEM, AWAITING_ADMIN, MANUAL_REVIEW) were missing from it too — all of them
     * reachable only from PAID or PARTIALLY_SHIPPED, so all of them money already taken. Written
     * this way, a status added to this enum is counted automatically; only a genuinely
     * pre-payment status needs a line changing here.
     */
    public static final Set<OrderStatus> PAID_ORDER_STATUSES =
            Collections.unmodifiableSet(EnumSet.complementOf(EnumSet.of(PENDING, CANCELLED)));
}
