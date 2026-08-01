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
    // total = subtotal − discountAmount + shippingTotal

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

    // ===== Shipping (set by BrandPartner on confirmShipment) =====

    private String shippingCarrier;
    private String trackingNumber;
    private LocalDateTime shippedAt;

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

    // ===== Shipping problem (set by BrandPartner on reportShippingProblem) =====

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
