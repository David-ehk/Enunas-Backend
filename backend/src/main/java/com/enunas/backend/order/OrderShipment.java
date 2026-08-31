package com.enunas.backend.order;

import com.enunas.backend.brandpartner.BrandPartner;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One brand's shipment progress on a (possibly multi-brand) order — mirrors {@link ReturnOrder}'s
 * per-(order, brand) shape and the reason for it: a multi-brand order's parcels leave from
 * different warehouses at different times, so a single shipped/carrier/tracking value on
 * {@link Order} could never be right for more than one brand at once (see V27 migration notes —
 * this replaces exactly that bug). Unique per (order, brand): {@link OrderService} finds-or-creates
 * this row rather than ever inserting a second one for the same pair.
 */
@Entity
@Table(name = "order_shipments", uniqueConstraints =
        @UniqueConstraint(name = "uk_order_shipment_brand", columnNames = {"order_id", "brand_partner_id"}))
@Getter
@Setter
@NoArgsConstructor
public class OrderShipment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_partner_id", nullable = false)
    private BrandPartner brand;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ShipmentStatus status = ShipmentStatus.AWAITING_SHIPMENT;

    // ===== Set by confirmShipment while transitioning to SHIPPED =====
    // Lengths mirror V27's DDL rather than leaning on Hibernate's 255 default, which does NOT
    // match the migration for carrier/tracking_number — with ddl-auto: validate the mapping is
    // documentation, so it should state the width the column actually has.
    @Column(length = 100)
    private String carrier;

    @Column(length = 100)
    private String trackingNumber;

    /** Never null while {@link #status} is SHIPPED — enforced by V29's check constraint. */
    private LocalDateTime shippedAt;

    // ===== Set by reportShippingProblem while transitioning to PROBLEM =====
    @Column(length = 1000)
    private String problemDescription;

    private LocalDateTime problemReportedAt;

    @Column(length = 255)
    private String problemReportedBy;

    @Column(updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /**
     * A brand's shipment row at the moment it becomes relevant: nothing dispatched, no problem
     * reported. Mirrors {@link ReturnOrder#create} — a named factory rather than a Lombok
     * {@code @Builder}, for the same reason spelled out on ReturnOrder: a generated builder would
     * expose fluent setters for every field, including the ones only {@link OrderService}'s
     * ship/problem transitions may write together with {@link #status}.
     */
    public static OrderShipment awaiting(Order order, BrandPartner brand) {
        OrderShipment shipment = new OrderShipment();
        shipment.order = order;
        shipment.brand = brand;
        return shipment;
    }

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OrderShipment other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    /**
     * Own columns only. {@code order} and {@code brand} are LAZY proxies, and resolving them for a
     * log line would fire a query — or throw, if the line is written after the session closed.
     * Callers logging a shipment already have the order number and brand in hand.
     */
    @Override
    public String toString() {
        return "OrderShipment[id=" + id + ", status=" + status
                + ", carrier=" + carrier + ", trackingNumber=" + trackingNumber
                + ", shippedAt=" + shippedAt + "]";
    }
}
