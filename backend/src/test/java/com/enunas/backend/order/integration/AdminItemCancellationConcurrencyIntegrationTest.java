package com.enunas.backend.order.integration;

import com.enunas.backend.payment.PaymentProvider;
import com.enunas.backend.payment.RefundCommand;
import com.enunas.backend.user.EmailService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AdminItemCancellationConcurrencyIntegrationTest extends AbstractItemCancellationIntegrationTest {

    @MockitoBean EmailService emailService;
    @MockitoSpyBean PaymentProvider paymentProvider;

    private List<Integer> concurrently(Callable<Integer> a, Callable<Integer> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> fa = pool.submit(() -> { start.await(); return a.call(); });
        Future<Integer> fb = pool.submit(() -> { start.await(); return b.call(); });
        start.countDown();
        List<Integer> codes = List.of(fa.get(30, TimeUnit.SECONDS), fb.get(30, TimeUnit.SECONDS));
        pool.shutdown();
        return codes;
    }

    private List<RefundCommand> refundCalls() {
        ArgumentCaptor<RefundCommand> c = ArgumentCaptor.forClass(RefundCommand.class);
        verify(paymentProvider, atLeast(0)).refundPayment(c.capture());
        return c.getAllValues();
    }

    @Test
    void overlappingCalls_refundTheSharedItemExactlyOnce() throws Exception {
        Fixture f = paidTwoBrandOrder(null);

        List<Integer> codes = concurrently(
                () -> cancelItems(f.adminToken(), f.orderId(), List.of(f.a1(), f.a2())).getStatusCode().value(),
                () -> cancelItems(f.adminToken(), f.orderId(), List.of(f.a2(), f.a3())).getStatusCode().value());

        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        verify(paymentProvider, times(1)).refundPayment(any());
        assertThat(reversals(f.orderId())).hasSize(1);
        assertThat(itemRow(f.a2()).get("refund_transaction_id")).isNotNull();
    }

    @Test
    void twoCallsThatTogetherEmptyABrand_refundItsShippingExactlyOnce() throws Exception {
        Fixture f = paidTwoBrandOrder(null);

        List<Integer> codes = concurrently(
                () -> cancelItems(f.adminToken(), f.orderId(), List.of(f.a1(), f.a2())).getStatusCode().value(),
                () -> cancelItems(f.adminToken(), f.orderId(), List.of(f.a3())).getStatusCode().value());

        assertThat(codes).containsExactly(200, 200);
        long shippingRows = reversals(f.orderId()).stream()
                .filter(r -> ((String) r.get("external_reference_id")).endsWith(":SHIPPING")).count();
        assertThat(shippingRows).isEqualTo(1);
        BigDecimal refunded = refundCalls().stream().map(RefundCommand::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(refunded).isEqualByComparingTo(itemMoney(f.a1(), "customer_gross_after_discount")
                .add(itemMoney(f.a2(), "customer_gross_after_discount"))
                .add(itemMoney(f.a3(), "customer_gross_after_discount"))
                .add(shippingOf(f.orderId(), f.brandAId())));
    }

    @Test
    void brandShippingDuringTheRefund_neverShipsTheClaimedItem() {
        Fixture f = paidTwoBrandOrder(null);
        doAnswer(inv -> {
            assertThat(ship(f.brandAToken(), f.orderId()).getStatusCode().is2xxSuccessful()).isTrue(); // a2, a3 active
            return inv.callRealMethod();
        }).when(paymentProvider).refundPayment(any());

        assertThat(cancelItems(f.adminToken(), f.orderId(), List.of(f.a1())).getStatusCode().value()).isEqualTo(200);

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(emailService, atLeastOnce()).sendHtmlEmail(eq("customer@it.local"), anyString(), html.capture());
        List<String> mails = html.getAllValues();
        assertThat(mails.get(mails.size() - 1)).contains(productName(f.a2())).doesNotContain(productName(f.a1()));
    }
}
