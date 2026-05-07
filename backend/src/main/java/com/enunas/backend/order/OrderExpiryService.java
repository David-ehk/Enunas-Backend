package com.enunas.backend.order;

import com.enunas.backend.user.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderExpiryService {

    static final int EXPIRY_MINUTES = 30;

    private final OrderRepository orderRepository;
    private final EmailService emailService;

    /**
     * Runs every 5 minutes. Cancels PENDING orders older than 30 minutes.
     * No stock restore is needed — stock is only decremented when an order reaches PAID,
     * so PENDING orders never hold any inventory.
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000)
    @Transactional
    public void cancelExpiredPendingOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(EXPIRY_MINUTES);
        List<Order> expired = orderRepository.findByStatusAndCreatedAtBefore(OrderStatus.PENDING, cutoff);

        if (expired.isEmpty()) return;

        for (Order order : expired) {
            order.setStatus(OrderStatus.CANCELLED);
            order.setCancellationNote("Order expired — payment not confirmed within 30 minutes.");
            orderRepository.save(order);

            try {
                emailService.sendPlainTextEmail(
                        order.getBuyer().getEmail(),
                        "Your order " + order.getOrderNumber() + " has expired",
                        "Your order " + order.getOrderNumber() + " was automatically cancelled " +
                        "because payment was not completed within 30 minutes.\n\n" +
                        "Please place a new order if you still want these items.");
            } catch (Exception e) {
                log.error("Failed to send expiry email for order {}: {}", order.getOrderNumber(), e.getMessage());
            }
        }

        log.info("Order expiry job: cancelled {} stale PENDING order(s) older than {} minutes",
                expired.size(), EXPIRY_MINUTES);
    }
}
