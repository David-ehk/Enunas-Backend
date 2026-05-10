package com.enunas.backend.payment.mock;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Simulates Mollie's async webhook delivery with a 1-second delay.
 * Each dispatch is fire-and-forget; errors are logged but do not propagate.
 */
@Slf4j
@Component
@Profile("mock-payments")
public class MockWebhookDispatcher {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    @Value("${app.mock.webhook-base-url:http://localhost:8080}")
    private String baseUrl;

    public void dispatchPaymentPaid(String paymentId) {
        executor.schedule(() -> {
            try {
                restTemplate.postForEntity(
                        baseUrl + "/webhooks/mock/payment",
                        new WebhookEvent(paymentId, "payment.paid"),
                        Void.class);
                log.debug("MockWebhookDispatcher: dispatched payment.paid paymentId={}", paymentId);
            } catch (Exception e) {
                log.error("MockWebhookDispatcher: payment.paid dispatch failed paymentId={}: {}",
                        paymentId, e.getMessage());
            }
        }, 1, TimeUnit.SECONDS);
    }

    public void dispatchRefundCreated(String refundId, String paymentId) {
        executor.schedule(() -> {
            try {
                restTemplate.postForEntity(
                        baseUrl + "/webhooks/mock/refund",
                        new WebhookEvent(paymentId, refundId, "refund.created"),
                        Void.class);
                log.debug("MockWebhookDispatcher: dispatched refund.created refundId={} paymentId={}",
                        refundId, paymentId);
            } catch (Exception e) {
                log.error("MockWebhookDispatcher: refund.created dispatch failed refundId={}: {}",
                        refundId, e.getMessage());
            }
        }, 1, TimeUnit.SECONDS);
    }
}
