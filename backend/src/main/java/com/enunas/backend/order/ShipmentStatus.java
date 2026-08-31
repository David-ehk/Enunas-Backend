package com.enunas.backend.order;

/** Per-(order, brand) shipment progress — see {@link OrderShipment}. */
public enum ShipmentStatus {
    AWAITING_SHIPMENT,
    SHIPPED,
    PROBLEM
}
