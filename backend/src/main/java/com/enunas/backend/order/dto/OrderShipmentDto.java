package com.enunas.backend.order.dto;

import com.enunas.backend.order.OrderShipment;
import com.enunas.backend.order.ShipmentStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/** One brand's shipment progress on an order. A multi-brand order carries several of these — see
 *  {@link com.enunas.backend.order.OrderShipment}. */
@Getter
@Builder
public class OrderShipmentDto {

    /** The brand whose parcel this is. Null only for a row whose brand FK failed to resolve. */
    private Long brandId;
    private String brandName;

    /** Never null: the entity defaults to AWAITING_SHIPMENT and the column is NOT NULL. */
    private ShipmentStatus status;

    /** Set together with SHIPPED. Absent when an admin force-shipped the order (no carrier known). */
    private String carrier;

    /** Set together with SHIPPED. Absent on the admin override, same as {@code carrier}. Max 100 chars. */
    private String trackingNumber;

    /** When this brand dispatched. Never null while {@link #status} is SHIPPED (V29 check constraint). */
    private LocalDateTime shippedAt;

    /** Max 1000 chars, per the column. Set together with PROBLEM. */
    private String problemDescription;

    private LocalDateTime problemReportedAt;

    /**
     * The view a brand gets before it has recorded anything at all. A row is only created the
     * moment a brand first ships or reports a problem (same convention as ReturnOrder: no row =
     * nothing has happened yet), so brand-scoped responses synthesise this rather than making the
     * caller tell "no row" apart from "confirmed not shipped". Mirrors
     * {@link OrderShipment#awaiting}, which does the same for the entity.
     */
    public static OrderShipmentDto awaiting(Long brandId, String brandName) {
        return OrderShipmentDto.builder()
                .brandId(brandId)
                .brandName(brandName)
                .status(ShipmentStatus.AWAITING_SHIPMENT)
                .build();
    }

    /** Maps a persisted row. Never called with null — see the call sites in OrderService, which
     *  map over stream elements and an Optional, so a null guard here would be unreachable. */
    public static OrderShipmentDto from(OrderShipment s) {
        return OrderShipmentDto.builder()
                .brandId(s.getBrand() != null ? s.getBrand().getId() : null)
                .brandName(s.getBrand() != null ? s.getBrand().getBrandName() : null)
                .status(s.getStatus())
                .carrier(s.getCarrier())
                .trackingNumber(s.getTrackingNumber())
                .shippedAt(s.getShippedAt())
                .problemDescription(s.getProblemDescription())
                .problemReportedAt(s.getProblemReportedAt())
                .build();
    }
}
