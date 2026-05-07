package com.enunas.backend.payment;

import com.enunas.backend.ledger.LedgerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PayoutReleaseService {

    private final LedgerService ledgerService;

    @Scheduled(cron = "${enunas.payout.release-cron:0 0 2 * * ?}")
    public void runPayoutRelease() {
        log.info("PayoutReleaseService: starting scheduled payout release");
        try {
            ledgerService.releasePendingBalances();
        } catch (Exception e) {
            log.error("PayoutReleaseService: release job failed: {}", e.getMessage(), e);
        }
    }
}
