package com.enunas.backend.order;

import java.math.BigDecimal;

/**
 * One order line as shown to the customer in an order-confirmation or shipment-confirmation email
 * — product name, chosen color/size, quantity, and (order confirmation only) the line total.
 * Shared by {@link OrderConfirmationEvent} and {@link ShipmentConfirmedEvent} so both events
 * describe an item the same way. {@code lineTotal} is null on a shipment line — a shipment mail
 * describes what's in the parcel, not what was paid for it.
 *
 * <p>Replaces what used to be a pre-formatted {@code List<String>} on both events: the display
 * string ("2x Product (Color, Size) – €X") was built once, at publish time, which meant the plain
 * text email was the only shape either event's items could ever take. Carrying the fields
 * separately lets each listener render its own layout — an HTML table row now, anything else later
 * — from the same event.
 */
public record OrderItemLine(
        String productName,
        String color,
        String size,
        int quantity,
        BigDecimal lineTotal
) {}
