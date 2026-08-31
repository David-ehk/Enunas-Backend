package com.enunas.backend.order;

import java.util.List;

/**
 * Published after a brand partner's shipment-confirmation transaction commits — once per BRAND,
 * not once per order. A multi-brand order produces one of these per brand as each dispatches its
 * own parcel, each with that brand's own carrier and tracking number, so {@code brandName} is
 * required to tell the customer which part of their order this message is about.
 */
public record ShipmentConfirmedEvent(
        String buyerEmail,
        String orderNumber,
        String brandName,
        String carrier,
        String trackingNumber,
        String note,
        // The order's line items belonging to THIS brand — already filtered by the publisher, so
        // the listener never sees another brand's articles. Without it the mail can only say "the
        // articles from X", which on a multi-brand order leaves the customer guessing which of
        // their things are actually in the parcel this tracking number covers.
        List<String> itemLines,
        // Same order-detail URL the confirmation mail links to.
        String orderLink
) {
}
