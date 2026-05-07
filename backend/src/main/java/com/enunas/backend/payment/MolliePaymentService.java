package com.enunas.backend.payment;

import com.enunas.backend.exception.PaymentException;
import com.mollie.mollie.Client;
import com.mollie.mollie.models.components.Amount;
import com.mollie.mollie.models.components.EntityPaymentRoute;
import com.mollie.mollie.models.components.EntityPaymentRouteDestination;
import com.mollie.mollie.models.components.PaymentRequest;
import com.mollie.mollie.models.components.RouteDestinationType;
import com.mollie.mollie.models.components.PaymentResponseStatus;
import com.mollie.mollie.models.components.RefundRequest;
import com.mollie.mollie.models.operations.CreatePaymentResponse;
import com.mollie.mollie.models.operations.CreateRefundResponse;
import com.mollie.mollie.models.operations.GetPaymentRequest;
import com.mollie.mollie.models.operations.GetPaymentResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MolliePaymentService {

    private final Client mollieClient;

    @Value("${mollie.webhook.url}")
    private String webhookUrl;

    public record MolliePaymentResult(String checkoutUrl, String molliePaymentId) {}

    public record MolliePaymentDetails(boolean isPaid, BigDecimal amount) {}

    /** Per-brand routing instruction: route this amount to the given Mollie organization. */
    public record BrandRouting(String mollieOrganizationId, BigDecimal amount) {}

    public MolliePaymentResult createPayment(BigDecimal amount, String currency,
                                             String description, String redirectUrl) throws Exception {
        return createPayment(amount, currency, description, redirectUrl, List.of());
    }

    public MolliePaymentResult createPayment(BigDecimal amount, String currency,
                                             String description, String redirectUrl,
                                             List<BrandRouting> routings) throws Exception {

        PaymentRequest.Builder builder = PaymentRequest.builder()
                .amount(Amount.builder()
                        .currency(currency)
                        .value(amount.toPlainString())
                        .build())
                .description(description)
                .redirectUrl(redirectUrl)
                .webhookUrl(webhookUrl);

        if (!routings.isEmpty()) {
            List<EntityPaymentRoute> routes = routings.stream()
                    .map(r -> EntityPaymentRoute.builder()
                            .amount(Amount.builder()
                                    .currency(currency)
                                    .value(r.amount().toPlainString())
                                    .build())
                            .destination(EntityPaymentRouteDestination.builder()
                                    .type(RouteDestinationType.ORGANIZATION)
                                    .organizationId(r.mollieOrganizationId())
                                    .build())
                            .build())
                    .toList();
            builder.routing(routes);
        }

        CreatePaymentResponse response = mollieClient.payments().create()
                .paymentRequest(builder.build())
                .call();

        var payment = response.paymentResponse()
                .orElseThrow(() -> new PaymentException("Mollie returned no payment response"));

        String checkoutUrl = payment.links().checkout()
                .map(url -> url.href())
                .orElseThrow(() -> new PaymentException("No checkout URL in Mollie response"));
        return new MolliePaymentResult(checkoutUrl, payment.id());
    }

    public MolliePaymentDetails getPayment(String molliePaymentId) throws Exception {
        GetPaymentResponse response = mollieClient.payments().get()
                .request(new GetPaymentRequest(molliePaymentId))
                .call();

        var payment = response.paymentResponse()
                .orElseThrow(() -> new PaymentException("Mollie payment not found: " + molliePaymentId));

        // Only "paid" triggers fulfillment; other terminal states (failed, expired, canceled) do not.
        boolean isPaid = PaymentResponseStatus.PAID.equals(payment.status());
        BigDecimal paidAmount = new BigDecimal(payment.amount().value());
        return new MolliePaymentDetails(isPaid, paidAmount);
    }

    public void refundPayment(String molliePaymentId, BigDecimal amount, String reason) throws Exception {
        RefundRequest refundRequest = RefundRequest.builder()
                .description(reason)
                .amount(Amount.builder()
                        .currency("EUR")
                        .value(amount.setScale(2, RoundingMode.HALF_UP).toPlainString())
                        .build())
                .build();

        CreateRefundResponse response = mollieClient.refunds().create()
                .paymentId(molliePaymentId)
                .refundRequest(refundRequest)
                .call();

        if (response.entityRefundResponse().isEmpty()) {
            throw new PaymentException("Mollie refund failed for payment " + molliePaymentId + " — no response body");
        }
    }
}
