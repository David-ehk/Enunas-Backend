package com.enunas.backend.payment.mock;

import com.enunas.backend.payment.PaymentStatus;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class MockPayment {

    private final String id;
    private final BigDecimal amount;
    private PaymentStatus status;
    private String refundId;

    public MockPayment(String id, BigDecimal amount) {
        this.id = id;
        this.amount = amount;
        this.status = PaymentStatus.PENDING;
    }
}
