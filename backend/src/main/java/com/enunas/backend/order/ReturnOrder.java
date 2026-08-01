package com.enunas.backend.order;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.BrandReturnAddress;
import com.enunas.backend.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Deliberately has NO {@code @Builder}. A generated builder would expose fluent setters for the
 * {@code shipTo*} fields regardless of their {@code @Setter(AccessLevel.NONE)} — Lombok's
 * {@code @Builder} does not respect field-level {@code @Setter} access — silently reopening the
 * immutability {@link #applyShipToSnapshot} exists to guarantee. {@link #create} is the only
 * supported way to construct one.
 */
@Entity
@Table(name = "returns")
@Getter
@Setter
@NoArgsConstructor
public class ReturnOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String returnNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * The brand this return goes back to. A multi-brand order produces one ReturnOrder per brand —
     * goods physically travel to different places, so a single row per order could never be right.
     * Nullable in DB only because V14 backfilled existing rows; always set for new returns.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_partner_id")
    private BrandPartner brand;

    // ===== Frozen ship-to snapshot, produced by ReturnAddressSnapshotFactory at request time. =====
    // Snapshotted rather than read live for the same reason OrderItem freezes its economics: the
    // customer was told to post a parcel somewhere, and that instruction must not silently change
    // if the brand later moves warehouses. IMMUTABLE after creation: no public setters — the only
    // way to write these fields is applyShipToSnapshot(), called exactly once, before the entity is
    // ever exposed to a caller. Never re-resolve these on read.

    @Setter(AccessLevel.NONE)
    private String shipToName;

    @Setter(AccessLevel.NONE)
    private String shipToStreet;

    @Setter(AccessLevel.NONE)
    private String shipToPostalCode;

    @Setter(AccessLevel.NONE)
    private String shipToCity;

    @Setter(AccessLevel.NONE)
    private String shipToCountry;

    @Setter(AccessLevel.NONE)
    private String shipToInstructions;

    @Setter(AccessLevel.NONE)
    private LocalDateTime shipToSnapshotAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReturnStatus status = ReturnStatus.REQUESTED;

    @Enumerated(EnumType.STRING)
    private ReturnReason reason;

    @Column(length = 500)
    private String description;

    @OneToMany(mappedBy = "returnOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private List<ReturnItem> items = new ArrayList<>();

    // ===== Return shipping label. See ReturnLabelStatus for the state meanings. =====

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReturnLabelStatus labelStatus = ReturnLabelStatus.PENDING;

    private String labelCarrier;

    private String labelTrackingNumber;

    private String labelUrl;

    /** Populated only when labelStatus == FAILED — reserved for the future carrier-API path. */
    @Column(length = 500)
    private String labelFailureReason;

    private LocalDateTime labelUpdatedAt;

    // ===== Construction =====

    /**
     * The only supported way to construct a ReturnOrder. Deliberately takes just the fields that
     * are known at request time — {@code shipTo*} is NOT a parameter; it can only be written
     * afterwards via {@link #applyShipToSnapshot}, one time, before the return is handed to any
     * caller or saved anywhere it could be read back mid-construction.
     */
    public static ReturnOrder create(String returnNumber, Order order, User user, BrandPartner brand,
                                      ReturnReason reason, String description) {
        ReturnOrder r = new ReturnOrder();
        r.returnNumber = returnNumber;
        r.order = order;
        r.user = user;
        r.brand = brand;
        r.reason = reason;
        r.description = description;
        return r;
    }

    /**
     * Records a brand-supplied label. Only valid while the return is APPROVED — labelling nothing
     * (REQUESTED) or something already physically resolved (RECEIVED/REFUNDED) is a caller bug, not
     * a state this method silently accepts.
     */
    public void applyBrandUploadedLabel(String carrier, String trackingNumber, String labelUrl) {
        if (this.status != ReturnStatus.APPROVED) {
            throw new IllegalStateException(
                    "Can only upload a label for a return in APPROVED status. Current: " + this.status);
        }
        this.labelStatus = ReturnLabelStatus.UPLOADED_BY_BRAND;
        this.labelCarrier = carrier;
        this.labelTrackingNumber = trackingNumber;
        this.labelUrl = labelUrl;
        this.labelFailureReason = null;
        this.labelUpdatedAt = LocalDateTime.now();
    }

    @Column(precision = 10, scale = 2)
    private BigDecimal refundAmount;

    @Column(updatable = false)
    private LocalDateTime requestedAt;

    private LocalDateTime approvedAt;
    private LocalDateTime receivedAt;
    private LocalDateTime refundedAt;

    @PrePersist
    protected void onCreate() {
        requestedAt = LocalDateTime.now();
    }

    // ===== Ship-to snapshot =====

    /**
     * Freezes a {@link ReturnAddressSnapshotFactory}-produced snapshot onto this return. Call
     * exactly once, at request time, before this entity is handed to any caller — there is no
     * supported way to change it afterwards (see the {@code @Setter(AccessLevel.NONE)} fields above).
     */
    public void applyShipToSnapshot(ReturnAddressSnapshot snapshot) {
        BrandReturnAddress address = snapshot.address();
        this.shipToName = address.recipient();
        this.shipToStreet = address.street();
        this.shipToPostalCode = address.postalCode();
        this.shipToCity = address.city();
        this.shipToCountry = address.country();
        this.shipToInstructions = address.instructions();
        this.shipToSnapshotAt = snapshot.snapshotAt();
    }

    /** The snapshotted address as the plain-text block shown to the customer. */
    public String getShipToFormatted() {
        return new BrandReturnAddress(shipToName, shipToStreet, shipToPostalCode,
                shipToCity, shipToCountry, shipToInstructions).formatted();
    }

    // ===== Collection access =====

    public List<ReturnItem> getItems() {
        return items == null ? List.of() : Collections.unmodifiableList(items);
    }

    public void addItem(ReturnItem item) {
        if (items == null) items = new ArrayList<>();
        items.add(item);
        item.setReturnOrder(this);
    }

    // ===== equals / hashCode =====

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ReturnOrder other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
