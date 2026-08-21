package com.enunas.backend.order;

/** Published after a brand partner's shipment-confirmation transaction commits. */
public record ShipmentConfirmedEvent(
        String buyerEmail,
        String orderNumber,
        String carrier,
        String trackingNumber,
        String note
) {
}
