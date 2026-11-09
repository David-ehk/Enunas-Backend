package com.enunas.backend.order;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Decides whether the expiry job needs the database at all, so an idle shop lets a scale-to-zero
 * database suspend. Kept apart from the {@code @Transactional} {@link OrderExpiryService} because
 * opening a transaction takes a connection, which would wake the database before any skip ran.
 *
 * <p>ponytail: in-memory, so it assumes one backend instance. With several, an instance that dies
 * could leave its pending orders until the next order or restart elsewhere; move the window to
 * shared state then.
 */
@Service
public class OrderExpiryScheduler {

    /** Long enough for a new order to expire, plus two more 5-minute runs in case one fails. */
    static final Duration CHECK_WINDOW = Duration.ofMinutes(OrderExpiryService.EXPIRY_MINUTES + 10);

    private final OrderExpiryService orderExpiryService;
    private final Clock clock;
    private volatile Instant checkUntil;

    public OrderExpiryScheduler(OrderExpiryService orderExpiryService, Clock clock) {
        this.orderExpiryService = orderExpiryService;
        this.clock = clock;
        // Orders placed before a restart may still be pending, and this instance can't know.
        orderCreated();
    }

    public void orderCreated() {
        checkUntil = clock.instant().plus(CHECK_WINDOW);
    }

    @Scheduled(fixedDelay = 5 * 60 * 1000)
    public void cancelExpiredOrders() {
        if (clock.instant().isAfter(checkUntil)) return;
        orderExpiryService.cancelExpiredPendingOrders();
    }
}
