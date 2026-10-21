package com.enunas.backend.order.integration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PartialReturnConcurrencyIntegrationTest extends AbstractReturnRefundIntegrationTest {

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

    @Test
    void twoConcurrentRequestsForAllUnits_neverExceedTheLineQuantity() throws Exception {
        ReturnFixture f = deliveredOrder(null);

        List<Integer> codes = concurrently(
                () -> requestReturn(f.customerToken(), f.orderId(), f.a1(), 2).getStatusCode().value(),
                () -> requestReturn(f.customerToken(), f.orderId(), f.a1(), 2).getStatusCode().value());

        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        assertThat(quantityOnReturn(returnNumberOf(f.orderId(), f.brandAId()), f.a1())).isEqualTo(2);
    }

    @Test
    void twoConcurrentSingleUnitRequestsPlusOneMore_neverExceedTheLineQuantity() throws Exception {
        ReturnFixture f = deliveredOrder(null);
        assertThat(requestReturn(f.customerToken(), f.orderId(), f.a1(), 1).getStatusCode().value()).isEqualTo(200);

        List<Integer> codes = concurrently(
                () -> requestReturn(f.customerToken(), f.orderId(), f.a1(), 1).getStatusCode().value(),
                () -> requestReturn(f.customerToken(), f.orderId(), f.a1(), 1).getStatusCode().value());

        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        assertThat(quantityOnReturn(returnNumberOf(f.orderId(), f.brandAId()), f.a1())).isEqualTo(2);
    }
}
