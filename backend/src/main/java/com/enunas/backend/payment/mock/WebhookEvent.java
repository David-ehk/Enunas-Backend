package com.enunas.backend.payment.mock;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class WebhookEvent {

    private String paymentId;
    private String refundId;   // null for payment events
    private String eventType;

    public WebhookEvent(String paymentId, String eventType) {
        this(paymentId, null, eventType);
    }
}
