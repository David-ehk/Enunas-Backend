package com.enunas.backend.discount;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.BrandPartnerService;
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
     */
    @Transactional
    public DiscountApplication validateAndApply(String rawCode, List<OrderItem> items) {
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
            BigDecimal lineTotal = item.getLineTotal();
            boolean applies = switch (code.getType()) {
                case ADMIN -> true;                                  // marketplace-wide
                case BRAND -> codeBrandId.equals(item.getBrandId()); // own products only
            };

            BigDecimal platformShare = BigDecimal.ZERO;
            BigDecimal brandShare    = BigDecimal.ZERO;

            if (applies && lineTotal != null && lineTotal.signum() > 0) {
                BigDecimal itemDiscount = lineTotal.multiply(percent).setScale(2, RoundingMode.HALF_UP);
                if (code.getType() == DiscountType.ADMIN) {
                    platformShare = itemDiscount;                               // Enunas absorbs all
                } else {
                    platformShare = itemDiscount.divide(TWO, 2, RoundingMode.HALF_UP); // 50/50
                    brandShare    = itemDiscount.subtract(platformShare);
                }
                assertNonNegativePayout(item, platformShare, brandShare);
            }

            shares.add(new ItemShare(platformShare, brandShare));
            totalPlatform = totalPlatform.add(platformShare);
            totalBrand    = totalBrand.add(brandShare);
            totalDiscount = totalDiscount.add(platformShare).add(brandShare);
        }

        // Reserve usage atomically — only succeeds while active and below the limit.
        if (discountCodeRepository.reserveUsage(code.getId()) == 0) {
            throw new IllegalStateException("Discount code is no longer available");
        }

        log.info("Discount {} applied (type={}, percent={}): discount={} platform={} brand={}",
                code.getCode(), code.getType(), percent, totalDiscount, totalPlatform, totalBrand);

        return new DiscountApplication(code, code.getType(), percent, shares,
                totalDiscount, totalPlatform, totalBrand);
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

    /** Guards the low-commission-rate edge: a discount must never push fee or payout negative. */
    private void assertNonNegativePayout(OrderItem item, BigDecimal platformShare, BigDecimal brandShare) {
        BigDecimal rate = item.getCommissionRate();
        if (rate == null) {
            return; // brandless item: platform absorbs, no brand payout to protect
        }
        BigDecimal baseFee     = item.getLineTotal().multiply(rate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal platformFee = baseFee.subtract(platformShare);
        BigDecimal brandPayout = item.getLineTotal().subtract(baseFee).subtract(brandShare);
        if (platformFee.signum() < 0) {
            throw new IllegalStateException(
                    "Discount exceeds the platform margin on item: " + item.getProductSnapshotName());
        }
        if (brandPayout.signum() < 0) {
            throw new IllegalStateException(
                    "Discount exceeds the brand payout on item: " + item.getProductSnapshotName());
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
