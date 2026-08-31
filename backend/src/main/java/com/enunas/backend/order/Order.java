package com.enunas.backend.order;

import com.enunas.backend.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "orders")
@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Column(nullable = false, unique = true)
    private String orderNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "buyer_id", nullable = false)
    private User buyer;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private List<OrderItem> items = new ArrayList<>();

    /**
     * Coarse lifecycle status. Pre-dates per-brand returns and still mixes two concerns: payment/
     * shipping progress (PENDING…DELIVERED, CANCELLED) and return progress (RETURN_REQUESTED…
     * REFUNDED). For an order with returns, this field is synced by
     * {@link OrderService#syncOrderStatus} to the LEAST-advanced open brand return — it answers
     * "is everything on this order settled yet", not "what state is any specific brand's return in".
     * For that, read {@code OrderResponseDto.returns} (one entry per brand) — never infer a
     * per-brand state from this single field on a multi-brand order.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private OrderStatus status = OrderStatus.PENDING;

    @Embedded
    private ShippingAddress shippingAddress;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal subtotal;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal shippingTotal;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal total;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "EUR";

    // ===== Discount snapshot (immutable; null/zero when no code was applied) =====
    // total = subtotal − discountAmount + shippingTotal — see computeTotal() below, which is the
    // single source of truth OrderService.createOrder() calls; do not duplicate this arithmetic
    // elsewhere.

    private String discountCode;

    @Enumerated(EnumType.STRING)
    private com.enunas.backend.discount.DiscountType discountType;

    @Column(precision = 5, scale = 4)
    private BigDecimal discountPercent;

    @Column(precision = 10, scale = 2)
    private BigDecimal discountAmount;          // platformDiscountAmount + brandDiscountAmount

    @Column(precision = 10, scale = 2)
    private BigDecimal platformDiscountAmount;  // total absorbed by Enunas

    @Column(precision = 10, scale = 2)
    private BigDecimal brandDiscountAmount;     // total absorbed by brands

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Enumerated(EnumType.STRING)
    private CancelReason cancellationReason;

    @Column(length = 500)
    private String cancellationNote;

    private String cancelledByAdminEmail;

    // ===== Shipping — LEGACY, frozen. Pre-dates per-brand shipment tracking and could only ever
    // hold ONE brand's carrier/tracking on a multi-brand order (the bug OrderShipment/V27 fixes).
    // No longer written by OrderService — see OrderShipment for the real, per-brand data going
    // forward. Kept in place (not dropped) because existing orders have real historical values
    // here, unlike Customer.totalOrders/totalSpent which V26 could safely drop (see that migration)
    // because nothing had ever written to them.

    private String shippingCarrier;
    private String trackingNumber;
    private LocalDateTime shippedAt;

    /** True the moment any brand on this order has reported a shipping problem via
     *  {@link OrderService#reportShippingProblem} — independent of {@link #status}, which stays an
     *  honest per-brand shipment rollup (see {@link OrderService#syncShipmentStatus}) and must never
     *  be faked into SHIPPING_PROBLEM by one brand's local issue on a multi-brand order. Never
     *  cleared automatically — an admin resolving the underlying per-brand problem doesn't retroactively
     *  erase that this order once needed attention. */
    @Column(name = "has_shipping_problem", nullable = false)
    @Builder.Default
    private boolean shippingProblem = false;

    /**
     * Set once, the moment this order first reaches DELIVERED (the only path in:
     * SHIPPED → DELIVERED via {@code OrderService.updateOrderStatus}). Anchors the 14-day Widerruf
     * window in {@code OrderService.requestReturn} — NOT carrier delivery confirmation, since
     * DELIVERED itself is admin-set, not carrier-fed.
     */
    private LocalDateTime deliveredAt;

    /**
     * Guards {@code DiscountService.releaseUsage} against being called twice for the same order —
     * the repository-level decrement is conditional but not keyed to an order, so without this an
     * order touched twice on the cancel/full-refund path could under-count a code's usedCount.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean discountUsageReleased = false;

    // ===== Shipping problem — LEGACY, frozen. Same reasoning as shippingCarrier above: superseded
    // by OrderShipment's per-brand problemDescription/problemReportedAt/problemReportedBy. =====

    @Column(length = 1000)
    private String problemDescription;
    private LocalDateTime problemReportedAt;
    private String problemReportedBy;

    @Column(updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // ===== Collection access =====

    public List<OrderItem> getItems() {
        return items == null ? List.of() : Collections.unmodifiableList(items);
    }

    public void addItem(OrderItem item) {
        if (items == null) items = new ArrayList<>();
        items.add(item);
        item.setOrder(this);
    }

    public void removeItem(OrderItem item) {
        if (items != null && items.remove(item)) {
            item.setOrder(null);
        }
    }

    // ===== Derived money =====

    /**
     * total = subtotal − discountAmount + shippingTotal. Null-safe on discountAmount/shippingTotal
     * so it can be called on an order that hasn't gone through the discount branch (both stay null
     * on the no-discount path) — see {@link OrderService#createOrder}, the only caller.
     */
    public BigDecimal computeTotal() {
        BigDecimal discount = discountAmount != null ? discountAmount : BigDecimal.ZERO;
        BigDecimal shipping = shippingTotal != null ? shippingTotal : BigDecimal.ZERO;
        return subtotal.subtract(discount).add(shipping);
    }

    // ===== equals / hashCode =====
    // Based on id only; hashCode is constant so it stays stable before and after persist.

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Order other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
