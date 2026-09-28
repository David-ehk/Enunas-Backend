package com.enunas.backend.payment;

import com.enunas.backend.exception.PaymentException;
import com.enunas.backend.exception.PaymentRejectedException;
import com.mollie.mollie.Client;
import com.mollie.mollie.models.errors.ClientError;
import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.http.HttpTimeoutException;

import com.mollie.mollie.models.components.RefundRequest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MolliePaymentServiceRefundClassificationTest {

    private final Client client = mock(Client.class, RETURNS_DEEP_STUBS);
    private final MolliePaymentService service = new MolliePaymentService(client);
    private final RefundCommand command = new RefundCommand("tr_1", new BigDecimal("10.00"), "test", "key-1");

    private void mollieThrows(RuntimeException e) throws Exception {
        when(client.refunds().create().paymentId(anyString()).refundRequest(any(RefundRequest.class)).idempotencyKey(anyString()).call())
                .thenThrow(e);
    }

    @Test
    void a4xxAnswer_isADefinitiveRejection() throws Exception {
        ClientError error = mock(ClientError.class);
        when(error.code()).thenReturn(422);
        when(error.getMessage()).thenReturn("amount too high");
        mollieThrows(error);
        assertThatThrownBy(() -> service.refundPayment(command)).isInstanceOf(PaymentRejectedException.class);
    }

    @Test
    void a5xxAnswer_isAmbiguous() throws Exception {
        ClientError error = mock(ClientError.class);
        when(error.code()).thenReturn(503);
        when(error.getMessage()).thenReturn("unavailable");
        mollieThrows(error);
        assertThatThrownBy(() -> service.refundPayment(command))
                .isInstanceOf(PaymentException.class)
                .isNotInstanceOf(PaymentRejectedException.class);
    }

    @Test
    void aTimeout_isAmbiguous() throws Exception {
        mollieThrows(new UncheckedIOException(new HttpTimeoutException("timed out")));
        assertThatThrownBy(() -> service.refundPayment(command))
                .isInstanceOf(PaymentException.class)
                .isNotInstanceOf(PaymentRejectedException.class);
    }
}
