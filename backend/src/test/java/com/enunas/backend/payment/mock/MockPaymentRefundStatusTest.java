package com.enunas.backend.payment.mock;

import com.enunas.backend.exception.PaymentRejectedException;
import com.enunas.backend.payment.RefundCommand;
import com.enunas.backend.payment.RefundStatusResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class MockPaymentRefundStatusTest {
    private final MockPaymentStore store = new MockPaymentStore();
    private final MockPaymentService service = new MockPaymentService(store, mock(MockWebhookDispatcher.class));

    @BeforeEach void seed() { store.save(new MockPayment("pay_1", new BigDecimal("50.00"))); }

    @Test void createdRefundsReportRefundedWithAmountAndPayment() {
        String id = service.refundPayment(new RefundCommand("pay_1", new BigDecimal("10.00"), "r", "k1")).refundId();
        RefundStatusResult s = service.getRefundStatus("pay_1", id);
        assertThat(s.status()).isEqualTo(RefundStatusResult.Status.REFUNDED);
        assertThat(s.amount()).isEqualByComparingTo("10.00");
        assertThat(s.paymentId()).isEqualTo("pay_1");
    }

    @Test void forcedStatusAppliesToTheNextRefundOnly() {
        store.forceNextRefundStatus(RefundStatusResult.Status.FAILED);
        String a = service.refundPayment(new RefundCommand("pay_1", new BigDecimal("1.00"), "r", "k1")).refundId();
        String b = service.refundPayment(new RefundCommand("pay_1", new BigDecimal("1.00"), "r", "k2")).refundId();
        assertThat(service.getRefundStatus("pay_1", a).status()).isEqualTo(RefundStatusResult.Status.FAILED);
        assertThat(service.getRefundStatus("pay_1", b).status()).isEqualTo(RefundStatusResult.Status.REFUNDED);
    }

    @Test void unknownRefundIsRejected() {
        assertThatThrownBy(() -> service.getRefundStatus("pay_1", "ref_nope")).isInstanceOf(PaymentRejectedException.class);
    }
}
