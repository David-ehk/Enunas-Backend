package com.enunas.backend.order;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderExpirySchedulerTest {

    private static final Instant START = Instant.parse("2026-11-09T00:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(40);

    private final OrderExpiryService expiryService = mock(OrderExpiryService.class);
    private final Clock clock = mock(Clock.class);
    private OrderExpiryScheduler scheduler;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(START);
        scheduler = new OrderExpiryScheduler(expiryService, clock);
    }

    private void at(Instant now) {
        when(clock.instant()).thenReturn(now);
    }

    @Test
    void afterRestart_checksUntilOrdersFromBeforeTheRestartCanHaveExpired() {
        at(START.plus(WINDOW));

        scheduler.cancelExpiredOrders();

        verify(expiryService).cancelExpiredPendingOrders();
    }

    @Test
    void withNoOrders_doesNotTouchTheDatabaseOnceTheWindowHasPassed() {
        at(START.plus(WINDOW).plusSeconds(1));

        scheduler.cancelExpiredOrders();

        verify(expiryService, never()).cancelExpiredPendingOrders();
    }

    @Test
    void aNewOrder_reopensTheWindowLongEnoughForItToExpire() {
        Instant orderPlaced = START.plus(Duration.ofHours(6));
        at(orderPlaced);
        scheduler.orderCreated();

        at(orderPlaced.plus(WINDOW));
        scheduler.cancelExpiredOrders();
        verify(expiryService, times(1)).cancelExpiredPendingOrders();

        at(orderPlaced.plus(WINDOW).plusSeconds(1));
        scheduler.cancelExpiredOrders();
        verify(expiryService, times(1)).cancelExpiredPendingOrders();
    }
}
