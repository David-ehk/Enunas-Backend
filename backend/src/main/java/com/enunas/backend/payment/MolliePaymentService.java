package com.enunas.backend.payment;

import com.enunas.backend.exception.PaymentException;
import com.enunas.backend.exception.PaymentRejectedException;
import com.enunas.backend.exception.PaymentRetryLaterException;
import com.mollie.mollie.Client;
import com.mollie.mollie.models.components.Amount;
import com.mollie.mollie.models.components.Metadata;
import com.mollie.mollie.models.components.PaymentRequest;
import com.mollie.mollie.models.components.PaymentResponseStatus;
import com.mollie.mollie.models.components.RefundRequest;
import com.mollie.mollie.models.errors.ClientError;
import com.mollie.mollie.models.operations.CreatePaymentResponse;
import com.mollie.mollie.models.operations.CreateRefundResponse;
import com.mollie.mollie.models.operations.GetPaymentRequest;
import com.mollie.mollie.models.operations.GetPaymentResponse;
import com.mollie.mollie.models.operations.GetRefundRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
@Profile("!mock-payments")
@RequiredArgsConstructor
public class MolliePaymentService implements PaymentProvider {

    private final Client mollieClient;

    @Value("${mollie.webhook.url}")
    private String webhookUrl;

    @Override
    public PaymentResult createPayment(CreatePaymentCommand command) {
        try {
            PaymentRequest request = PaymentRequest.builder()
                    .amount(Amount.builder()
                            .currency(command.currency())
                            .value(command.amount().toPlainString())
                            .build())
                    .description(command.description())
                    .redirectUrl(command.redirectUrl())
                    .webhookUrl(webhookUrl)
                    .build();

            CreatePaymentResponse response = mollieClient.payments().create()
                    .paymentRequest(request)
                    .call();

            var payment = response.paymentResponse()
                    .orElseThrow(() -> new PaymentException("Mollie returned no payment response"));

            String checkoutUrl = payment.links().checkout()
                    .map(link -> link.href())
                    .orElseThrow(() -> new PaymentException("No checkout URL in Mollie response"));

            return new PaymentResult(payment.id(), checkoutUrl);
        } catch (PaymentException e) {
            throw e;
        } catch (Exception e) {
            throw new PaymentException("Mollie createPayment failed: " + e.getMessage(), e);
        }
    }

    @Override
    public RefundResult refundPayment(RefundCommand command) {
        try {
            var refundBuilder = RefundRequest.builder()
                    .description(command.reason())
                    .amount(Amount.builder()
                            .currency("EUR")
                            .value(command.amount().setScale(2, RoundingMode.HALF_UP).toPlainString())
                            .build());
            if (!command.metadata().isEmpty()) {
                refundBuilder.metadata(Metadata.of(command.metadata()));
            }
            RefundRequest refundRequest = refundBuilder.build();

            var call = mollieClient.refunds().create()
                    .paymentId(command.paymentId())
                    .refundRequest(refundRequest);
            if (command.idempotencyKey() != null) {
                call = call.idempotencyKey(command.idempotencyKey());
            }
            CreateRefundResponse response = call.call();

            String refundId = response.entityRefundResponse()
                    .map(refund -> refund.id())
                    .orElseThrow(() -> new PaymentException(
                            "Mollie refund failed for payment " + command.paymentId() + " — no response body"));

            return new RefundResult(refundId);
        } catch (PaymentException e) {
            throw e;
        } catch (ClientError e) {
            throw mapClientError("refundPayment", e);
        } catch (Exception e) {
            throw new PaymentException("Mollie refundPayment failed: " + e.getMessage(), e);
        }
    }

    @Override
    public RefundStatusResult getRefundStatus(String paymentId, String refundId) {
        try {
            var refund = mollieClient.refunds().get()
                    .request(GetRefundRequest.builder().paymentId(paymentId).refundId(refundId).build())
                    .call()
                    .entityRefundResponse()
                    .orElseThrow(() -> new PaymentException("Mollie refund not found: " + refundId));
            return new RefundStatusResult(
                    RefundStatusResult.Status.valueOf(refund.status().value().toUpperCase()),
                    new BigDecimal(refund.amount().value()),
                    refund.paymentId().orElse(paymentId));
        } catch (PaymentException e) {
            throw e;
        } catch (ClientError e) {
            throw mapClientError("getRefundStatus", e);
        } catch (Exception e) {
            throw new PaymentException("Mollie getRefundStatus failed: " + e.getMessage(), e);
        }
    }

    /** 429 = throttled (retry later), other 4xx = refused, anything else = ambiguous. */
    private static PaymentException mapClientError(String op, ClientError e) {
        String msg = "Mollie rejected " + op + " (HTTP " + e.code() + "): " + e.getMessage();
        if (e.code() == 429) return new PaymentRetryLaterException(msg, e);
        if (e.code() >= 400 && e.code() < 500) return new PaymentRejectedException(msg, e);
        return new PaymentException("Mollie " + op + " failed: " + e.getMessage(), e);
    }

    @Override
    public PaymentDetails getPaymentDetails(String paymentId) {
        try {
            GetPaymentResponse response = mollieClient.payments().get()
                    .request(new GetPaymentRequest(paymentId))
                    .call();

            var payment = response.paymentResponse()
                    .orElseThrow(() -> new PaymentException("Mollie payment not found: " + paymentId));

            boolean isPaid = PaymentResponseStatus.PAID.equals(payment.status());
            BigDecimal amount = new BigDecimal(payment.amount().value());
            return new PaymentDetails(isPaid, amount);
        } catch (PaymentException e) {
            throw e;
        } catch (Exception e) {
            throw new PaymentException("Mollie getPayment failed: " + e.getMessage(), e);
        }
    }
}
