package com.enunas.backend.payout;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.BrandStatus;
import com.enunas.backend.brandpartner.brandeconomics.BrandEconomics;
import com.enunas.backend.brandpartner.brandeconomics.BrandEconomicsRepository;
import com.enunas.backend.brandpartner.brandpayoutprofile.BrandPayoutProfile;
import com.enunas.backend.brandpartner.brandpayoutprofile.BrandPayoutProfileRepository;
import com.enunas.backend.ledger.LedgerService;
import com.enunas.backend.payout.dto.MarkAsPaidDto;
import com.enunas.backend.payout.dto.PayoutDashboardDto;
import com.enunas.backend.payout.dto.PayoutResponseDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PayoutService {

    private final PayoutRepository payoutRepository;
    private final BrandEconomicsRepository brandEconomicsRepository;
    private final BrandPayoutProfileRepository brandPayoutProfileRepository;
    private final LedgerService ledgerService;

    // ===== Generation =====

    /**
     * Generates PENDING payout records for every brand that has an AVAILABLE balance
     * and a configured payout profile. Debt is netted off before the payout amount is set.
     *
     * Brands already holding a PENDING or APPROVED payout are skipped — idempotent.
     * Brands with no payout profile are skipped with a warning.
     * Brands whose available balance is fully consumed by outstanding debt get no payout
     * (they will show in the dashboard as negative-balance warnings instead).
     */
    @Transactional
    public List<PayoutResponseDto> generatePayouts() {
        List<BrandEconomics> allEcos = brandEconomicsRepository.findAll();
        List<Payout> created = new ArrayList<>();

        for (BrandEconomics eco : allEcos) {
            BrandPartner brand = eco.getBrandPartner();
            Long brandId = brand.getId();

            if (brand.getStatus() != BrandStatus.ACTIVE) {
                log.debug("generatePayouts: brand {} status={} — skipping", brandId, brand.getStatus());
                continue;
            }

            if (payoutRepository.existsByBrandPartnerIdAndStatusIn(
                    brandId, List.of(PayoutStatus.PENDING, PayoutStatus.APPROVED))) {
                log.debug("generatePayouts: brand {} already has open payout — skipping", brandId);
                continue;
            }

            BigDecimal available = eco.getPayoutBalance();
            if (available.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            BrandPayoutProfile profile = brandPayoutProfileRepository.findByBrandPartner_Id(brandId).orElse(null);
            if (profile == null) {
                log.warn("generatePayouts: brand {} has payoutBalance={} but no payout profile", brandId, available);
                continue;
            }

            BigDecimal debt         = eco.getOutstandingDebt();
            BigDecimal debtAbsorbed = debt.min(available).setScale(2, RoundingMode.HALF_UP);
            BigDecimal netAmount    = available.subtract(debtAbsorbed).setScale(2, RoundingMode.HALF_UP);

            if (netAmount.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("generatePayouts: brand {} available={} fully consumed by debt={} — no payout generated",
                        brandId, available, debt);
                continue;
            }

            Payout payout = Payout.builder()
                    .brandPartnerId(brandId)
                    .amount(netAmount)
                    .debtAbsorbed(debtAbsorbed)
                    .status(PayoutStatus.PENDING)
                    .iban(profile.getIban())
                    .bankAccountHolder(profile.getBankAccountHolder())
                    .currency("EUR")
                    .build();

            created.add(payoutRepository.save(payout));
            log.info("generatePayouts: created payout brand={} net={} debtAbsorbed={}", brandId, netAmount, debtAbsorbed);
        }

        log.info("generatePayouts: {} payout record(s) created", created.size());
        return created.stream().map(PayoutResponseDto::from).toList();
    }

    // ===== State transitions =====

    @Transactional
    public PayoutResponseDto approvePayout(Long payoutId, String adminEmail) {
        Payout payout = findById(payoutId);
        if (payout.getStatus() != PayoutStatus.PENDING) {
            throw new IllegalStateException("Only PENDING payouts can be approved. Current: " + payout.getStatus());
        }
        payout.setStatus(PayoutStatus.APPROVED);
        payout.setApprovedAt(LocalDateTime.now());
        payout.setApprovedByAdminEmail(adminEmail);
        return PayoutResponseDto.from(payoutRepository.save(payout));
    }

    /**
     * Confirms that the bank transfer has been sent.
     * Moves the payout to PAID and records the transfer in the ledger atomically.
     * The externalReference is the bank transfer reference for reconciliation.
     */
    @Transactional
    public PayoutResponseDto markAsPaid(Long payoutId, MarkAsPaidDto dto, String adminEmail) {
        Payout payout = findById(payoutId);
        if (payout.getStatus() != PayoutStatus.APPROVED) {
            throw new IllegalStateException("Only APPROVED payouts can be marked as paid. Current: " + payout.getStatus());
        }
        if (payoutRepository.findByExternalReference(dto.getExternalReference())
                .filter(p -> !p.getId().equals(payoutId)).isPresent()) {
            throw new IllegalArgumentException("External reference already used: " + dto.getExternalReference());
        }

        payout.setStatus(PayoutStatus.PAID);
        payout.setPaidAt(LocalDateTime.now());
        payout.setPaidByAdminEmail(adminEmail);
        payout.setExternalReference(dto.getExternalReference());
        payoutRepository.save(payout);

        ledgerService.recordPayoutTransfer(
                payout.getBrandPartnerId(),
                payout.getAmount(),
                payout.getDebtAbsorbed(),
                dto.getExternalReference());

        return PayoutResponseDto.from(payout);
    }

    @Transactional
    public PayoutResponseDto cancelPayout(Long payoutId) {
        Payout payout = findById(payoutId);
        if (payout.getStatus() == PayoutStatus.PAID) {
            throw new IllegalStateException("PAID payouts cannot be cancelled");
        }
        payout.setStatus(PayoutStatus.CANCELLED);
        return PayoutResponseDto.from(payoutRepository.save(payout));
    }

    // ===== Queries =====

    @Transactional(readOnly = true)
    public Page<PayoutResponseDto> listPayouts(Pageable pageable) {
        return payoutRepository.findAllByOrderByCreatedAtDesc(pageable).map(PayoutResponseDto::from);
    }

    @Transactional(readOnly = true)
    public Page<PayoutResponseDto> listPayoutsByStatus(PayoutStatus status, Pageable pageable) {
        return payoutRepository.findByStatusOrderByCreatedAtDesc(status, pageable).map(PayoutResponseDto::from);
    }

    @Transactional(readOnly = true)
    public PayoutResponseDto getById(Long payoutId) {
        return PayoutResponseDto.from(findById(payoutId));
    }

    @Transactional(readOnly = true)
    public List<PayoutResponseDto> getPayoutsForBrand(Long brandId) {
        return payoutRepository.findByBrandPartnerIdOrderByCreatedAtDesc(brandId)
                .stream().map(PayoutResponseDto::from).toList();
    }

    // ===== Dashboard =====

    @Transactional(readOnly = true)
    public PayoutDashboardDto getDashboard() {
        long pendingCount   = payoutRepository.countByStatus(PayoutStatus.PENDING);
        BigDecimal pendingTotal = payoutRepository.sumAmountByStatus(PayoutStatus.PENDING);

        long approvedCount  = payoutRepository.countByStatus(PayoutStatus.APPROVED);
        BigDecimal approvedTotal = payoutRepository.sumAmountByStatus(PayoutStatus.APPROVED);

        long paidCount      = payoutRepository.countByStatus(PayoutStatus.PAID);
        BigDecimal paidTotal = payoutRepository.sumAmountByStatus(PayoutStatus.PAID);

        List<PayoutDashboardDto.NegativeBalanceBrand> negativeBrands =
                brandEconomicsRepository.findBrandsWithOutstandingDebt().stream()
                        .map(eco -> new PayoutDashboardDto.NegativeBalanceBrand(
                                eco.getBrandPartner().getId(),
                                eco.getOutstandingDebt(),
                                eco.getPayoutBalance()))
                        .toList();

        return new PayoutDashboardDto(
                pendingCount, pendingTotal,
                approvedCount, approvedTotal,
                paidCount, paidTotal,
                negativeBrands);
    }

    // ===== Helpers =====

    private Payout findById(Long id) {
        return payoutRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Payout not found: " + id));
    }
}
