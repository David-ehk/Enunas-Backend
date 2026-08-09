package com.enunas.backend.discount;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.BrandPartnerService;
import com.enunas.backend.common.MoneyMath;
import com.enunas.backend.discount.DiscountApplication.ItemShare;
import com.enunas.backend.discount.dto.CreateDiscountDto;
import com.enunas.backend.discount.dto.DiscountResponseDto;
import com.enunas.backend.discount.dto.UpdateDiscountDto;
import com.enunas.backend.order.OrderItem;
import com.enunas.backend.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns discount-code lifecycle (admin + brand CRUD) and the checkout-time calculation that
 * folds a code into per-item discount shares. The {@link DiscountType} drives every cost split,
 * so the absorption model lives in exactly one place.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiscountService {

    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private final DiscountCodeRepository discountCodeRepository;
    private final BrandPartnerService brandPartnerService;

    // ===== Admin CRUD =====

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public DiscountResponseDto createAdminDiscount(CreateDiscountDto dto, User admin) {
        DiscountCode code = buildNew(dto, DiscountType.ADMIN, null, admin.getId());
        return DiscountResponseDto.from(discountCodeRepository.save(code));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public List<DiscountResponseDto> getAllDiscounts() {
        return discountCodeRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(DiscountResponseDto::from)
                .toList();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public DiscountResponseDto updateAsAdmin(Long id, UpdateDiscountDto dto) {
        DiscountCode code = findById(id);
        applyUpdates(code, dto);
        return DiscountResponseDto.from(discountCodeRepository.save(code));
    }

    // ===== Brand CRUD (own codes only) =====

    @PreAuthorize("hasRole('BRAND_PARTNER')")
    @Transactional
    public DiscountResponseDto createBrandDiscount(CreateDiscountDto dto, User brandPartner) {
        BrandPartner brand = brandPartnerService.findByUser(brandPartner);
        DiscountCode code = buildNew(dto, DiscountType.BRAND, brand, brandPartner.getId());
        return DiscountResponseDto.from(discountCodeRepository.save(code));
    }

    @PreAuthorize("hasRole('BRAND_PARTNER')")
    @Transactional(readOnly = true)
    public List<DiscountResponseDto> getMyDiscounts(User brandPartner) {
        BrandPartner brand = brandPartnerService.findByUser(brandPartner);
        return discountCodeRepository.findByBrand_IdOrderByCreatedAtDesc(brand.getId()).stream()
                .map(DiscountResponseDto::from)
                .toList();
    }

    @PreAuthorize("hasRole('BRAND_PARTNER')")
    @Transactional
    public DiscountResponseDto updateAsBrand(Long id, UpdateDiscountDto dto, User brandPartner) {
        DiscountCode code = findById(id);
        verifyBrandOwnership(code, brandPartner);
        applyUpdates(code, dto);
        return DiscountResponseDto.from(discountCodeRepository.save(code));
    }

    // ===== Checkout: validate + compute shares + reserve usage =====

    /**
     * Validates a code against the cart and returns the per-item discount split aligned by index
     * to {@code items}. Reserves one usage atomically (at placement); a lost race rejects the
     * order. Each item must already carry its commission snapshot (commissionRate set) so the
     * non-negativity guard can verify the platform fee and brand payout never go negative.
     *
     * <p>Thin wrapper over {@link #validateAndCompute} — the ONLY difference is the usage
     * reservation. Use this at real order placement; use {@link #validateAndCompute} for a
     * repeatable, non-committal quote (checkout preview) that must not burn a usage.
     */
    @Transactional
    public DiscountApplication validateAndApply(String rawCode, List<OrderItem> items) {
        DiscountApplication application = validateAndCompute(rawCode, items);

        // Reserve usage atomically — only succeeds while active and below the limit.
        if (discountCodeRepository.reserveUsage(application.code().getId()) == 0) {
            throw new IllegalStateException("Discount code is no longer available");
        }
        return application;
    }

    /**
     * Pure validation + per-item share computation, with NO side effects — identical to
     * {@link #validateAndApply} except that it never reserves a usage. This is what
     * {@code OrderService#previewOrder} calls, so the checkout preview quotes the SAME
     * discount-adjusted total the real charge will use without incrementing {@code usedCount}.
     *
     * <p>The {@code maxUses} check here is advisory-only for a preview: a code sitting at its
     * limit still throws, and the authoritative atomic guard remains the {@code reserveUsage}
     * conditional update in {@link #validateAndApply}.
     */
    @Transactional(readOnly = true)
    public DiscountApplication validateAndCompute(String rawCode, List<OrderItem> items) {
        DiscountCode code = discountCodeRepository.findByCodeIgnoreCase(rawCode.trim())
                .orElseThrow(() -> new IllegalArgumentException("Discount code not found: " + rawCode));

        LocalDateTime now = LocalDateTime.now();
        if (!code.isActive()) {
            throw new IllegalStateException("Discount code is not active");
        }
        if (code.getValidFrom() != null && now.isBefore(code.getValidFrom())) {
            throw new IllegalStateException("Discount code is not yet valid");
        }
        if (code.getValidUntil() != null && now.isAfter(code.getValidUntil())) {
            throw new IllegalStateException("Discount code has expired");
        }
        if (code.getMaxUses() != null && code.getUsedCount() >= code.getMaxUses()) {
            throw new IllegalStateException("Discount code has reached its usage limit");
        }
        if (code.getPercent().compareTo(code.getType().getMaxPercent()) > 0) {
            throw new IllegalStateException("Discount percent exceeds the allowed maximum for its type");
        }

        BigDecimal percent = code.getPercent();
        Long codeBrandId = code.getBrand() != null ? code.getBrand().getId() : null;

        if (code.getType() == DiscountType.BRAND) {
            boolean anyMatch = items.stream().anyMatch(it -> codeBrandId.equals(it.getBrandId()));
            if (!anyMatch) {
                throw new IllegalArgumentException(
                        "This discount code does not apply to any product in your cart");
            }
        }

        List<ItemShare> shares = new ArrayList<>(items.size());
        BigDecimal totalDiscount = BigDecimal.ZERO;
        BigDecimal totalPlatform = BigDecimal.ZERO;
        BigDecimal totalBrand    = BigDecimal.ZERO;

        for (OrderItem item : items) {
            BigDecimal lineNet = item.getLineNet();
            boolean applies = switch (code.getType()) {
                case ADMIN -> true;                                  // marketplace-wide
                case BRAND -> codeBrandId.equals(item.getBrandId()); // own products only
            };

            BigDecimal platformShareNet = BigDecimal.ZERO;
            BigDecimal brandShareNet    = BigDecimal.ZERO;

            if (applies && lineNet != null && lineNet.signum() > 0) {
                BigDecimal itemDiscountNet = MoneyMath.round2(lineNet.multiply(percent));
                if (code.getType() == DiscountType.ADMIN) {
                    platformShareNet = itemDiscountNet;                          // Enunas absorbs all
                } else {
                    // Round ONE share, derive the other — never round(x/2) on both sides (that
                    // double-rounds 0.05 → 0.06). The platform absorbs the odd-cent remainder.
                    brandShareNet    = itemDiscountNet.divide(TWO, 2, RoundingMode.HALF_UP);
                    platformShareNet = itemDiscountNet.subtract(brandShareNet);
                }
                assertNonNegativePayout(item, platformShareNet, brandShareNet);
            }

            shares.add(new ItemShare(platformShareNet, brandShareNet));
            totalPlatform = totalPlatform.add(platformShareNet);
            totalBrand    = totalBrand.add(brandShareNet);
            totalDiscount = totalDiscount.add(platformShareNet).add(brandShareNet);
        }

        log.info("Discount {} computed (type={}, percent={}): discount={} platform={} brand={}",
                code.getCode(), code.getType(), percent, totalDiscount, totalPlatform, totalBrand);

        return new DiscountApplication(code, code.getType(), percent, shares,
                totalDiscount, totalPlatform, totalBrand);
    }

    /**
     * Releases one reserved usage for an order whose discount no longer applies — a full
     * cancellation, or a return that ends up covering the entire order. Mirrors
     * {@code reserveUsage} in {@link #validateAndApply}; callers are responsible for their own
     * idempotency (see {@code Order.discountUsageReleased}) since this repository call is itself
     * a safe no-op on a repeat but a repeat would still under-count a code that legitimately gets
     * reused by a different order in the meantime.
     */
    @Transactional
    public void releaseUsage(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) return;
        discountCodeRepository.findByCodeIgnoreCase(rawCode.trim()).ifPresentOrElse(
                code -> {
                    if (discountCodeRepository.releaseUsage(code.getId()) == 0) {
                        log.warn("DiscountService: releaseUsage no-op for {} (usedCount already 0)", rawCode);
                    } else {
                        log.info("DiscountService: released one usage of {}", rawCode);
                    }
                },
                () -> log.warn("DiscountService: releaseUsage found no code for {}", rawCode));
    }

    // ===== Private helpers =====

    private DiscountCode buildNew(CreateDiscountDto dto, DiscountType type,
                                  BrandPartner brand, Long createdByUserId) {
        String normalized = dto.getCode().trim().toUpperCase();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Discount code must not be blank");
        }
        if (discountCodeRepository.existsByCodeIgnoreCase(normalized)) {
            throw new IllegalArgumentException("Discount code already exists: " + normalized);
        }
        assertPercentWithinCap(dto.getPercent(), type);
        assertValidWindow(dto.getValidFrom(), dto.getValidUntil());

        return DiscountCode.builder()
                .code(normalized)
                .type(type)
                .percent(dto.getPercent())
                .brand(brand)
                .validFrom(dto.getValidFrom())
                .validUntil(dto.getValidUntil())
                .maxUses(dto.getMaxUses())
                .usedCount(0)
                .active(true)
                .createdByUserId(createdByUserId)
                .build();
    }

    private void applyUpdates(DiscountCode code, UpdateDiscountDto dto) {
        if (dto.getPercent() != null) {
            assertPercentWithinCap(dto.getPercent(), code.getType());
            code.setPercent(dto.getPercent());
        }
        if (dto.getValidFrom() != null) code.setValidFrom(dto.getValidFrom());
        if (dto.getValidUntil() != null) code.setValidUntil(dto.getValidUntil());
        if (dto.getMaxUses() != null) code.setMaxUses(dto.getMaxUses());
        if (dto.getActive() != null) code.setActive(dto.getActive());
        assertValidWindow(code.getValidFrom(), code.getValidUntil());
    }

    private void assertPercentWithinCap(BigDecimal percent, DiscountType type) {
        if (percent.signum() <= 0) {
            throw new IllegalArgumentException("Discount percent must be positive");
        }
        if (percent.compareTo(type.getMaxPercent()) > 0) {
            throw new IllegalArgumentException(
                    type + " discount may not exceed " + type.getMaxPercent());
        }
    }

    private void assertValidWindow(LocalDateTime from, LocalDateTime until) {
        if (from != null && until != null && until.isBefore(from)) {
            throw new IllegalArgumentException("validUntil must not be before validFrom");
        }
    }

    /**
     * Guards the low-commission-rate edge on the NET basis: the discount must never push the
     * platform's net commission or the brand's net revenue below zero.
     *   commissionNet   = baseCommissionNet − platformShareNet
     *   brandNetRevenue = lineNet − baseCommissionNet − brandShareNet
     */
    private void assertNonNegativePayout(OrderItem item, BigDecimal platformShareNet, BigDecimal brandShareNet) {
        BigDecimal baseCommissionNet = item.getBaseCommissionNet();
        if (baseCommissionNet == null || item.getBrandId() == null) {
            return; // brandless / pre-snapshot item: platform absorbs, nothing to protect
        }
        BigDecimal commissionNet   = baseCommissionNet.subtract(platformShareNet);
        BigDecimal brandNetRevenue = item.getLineNet().subtract(baseCommissionNet).subtract(brandShareNet);
        if (commissionNet.signum() < 0) {
            throw new IllegalStateException(
                    "Discount exceeds the platform commission on item: " + item.getProductSnapshotName());
        }
        if (brandNetRevenue.signum() < 0) {
            throw new IllegalStateException(
                    "Discount exceeds the brand net revenue on item: " + item.getProductSnapshotName());
        }
    }

    private void verifyBrandOwnership(DiscountCode code, User brandPartner) {
        BrandPartner brand = brandPartnerService.findByUser(brandPartner);
        if (code.getBrand() == null || !code.getBrand().getId().equals(brand.getId())) {
            throw new SecurityException("You do not own this discount code");
        }
    }

    private DiscountCode findById(Long id) {
        return discountCodeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Discount code not found with id: " + id));
    }
}
