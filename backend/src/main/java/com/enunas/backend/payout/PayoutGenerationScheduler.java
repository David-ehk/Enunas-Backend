package com.enunas.backend.payout;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PayoutGenerationScheduler {

    private final PayoutService payoutService;

    // Every Monday at 06:00 — override via enunas.payout.generation-cron
    @Scheduled(cron = "${enunas.payout.generation-cron:0 0 6 * * MON}")
    public void runWeeklyPayoutGeneration() {
        log.info("PayoutGenerationScheduler: starting weekly payout generation");
        try {
            var payouts = payoutService.generatePayouts();
            log.info("PayoutGenerationScheduler: {} payout record(s) generated", payouts.size());
        } catch (Exception e) {
            log.error("PayoutGenerationScheduler: generation failed: {}", e.getMessage(), e);
        }
    }
}
