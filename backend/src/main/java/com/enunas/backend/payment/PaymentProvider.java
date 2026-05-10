package com.enunas.backend.payment;

/**
 * Single abstraction over any payment gateway.
 * MolliePaymentService (prod) and MockPaymentService (mock-payments profile) both implement this.
 * OrderService and MollieWebhookController depend only on this interface — never on a concrete impl.
 */
public interface PaymentProvider {

    PaymentResult createPayment(CreatePaymentCommand command);

    RefundResult refundPayment(RefundCommand command);

    PaymentDetails getPaymentDetails(String paymentId);
}
