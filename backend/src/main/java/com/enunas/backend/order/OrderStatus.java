package com.enunas.backend.order;

public enum OrderStatus {
    PENDING,           // placed, awaiting payment
    PAID,              // payment confirmed
    SHIPPED,           // dispatched to customer
    DELIVERED,         // received by customer

    // Neu für Probleme:
    SHIPPING_PROBLEM,     // BrandPartner hat Problem gemeldet
    AWAITING_ADMIN,       // Admin muss entscheiden
    MANUAL_REVIEW,        // Manuelle Prüfung nötig

    RETURN_REQUESTED,  // customer initiated return
    RETURN_APPROVED,   // admin approved; return label issued
    RETURN_RECEIVED,   // admin received goods back; stock restored
    REFUNDED,          // money returned to customer
    CANCELLED          // admin cancelled (only from PENDING)
}
