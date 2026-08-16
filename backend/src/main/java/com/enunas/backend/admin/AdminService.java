package com.enunas.backend.admin;

import com.enunas.backend.admin.dto.AdminProductResponseDto;
import com.enunas.backend.admin.dto.RejectionDto;
import com.enunas.backend.admin.dto.SetShippingProfileDto;
import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.BrandPartnerRepository;
import com.enunas.backend.brandpartner.BrandStatus;
import com.enunas.backend.brandpartner.brandpayoutprofile.BrandPayoutProfile;
import com.enunas.backend.brandpartner.brandpayoutprofile.BrandPayoutProfileRepository;
import com.enunas.backend.brandpartner.brandshippingprofile.BrandShippingProfile;
import com.enunas.backend.brandpartner.brandshippingprofile.BrandShippingProfileRepository;
import com.enunas.backend.ledger.ReconciliationService;
import com.enunas.backend.media.storage.MediaUrlResolver;
import com.enunas.backend.payout.PayoutService;
import com.enunas.backend.payout.PayoutStatus;
import com.enunas.backend.payout.dto.MarkAsPaidDto;
import com.enunas.backend.payout.dto.PayoutDashboardDto;
import com.enunas.backend.payout.dto.PayoutResponseDto;

import java.util.List;
import com.enunas.backend.brandpartner.dto.BrandPartnerResponseDto;
import com.enunas.backend.exception.BrandNotFoundException;
import com.enunas.backend.exception.ProductNotFoundException;
import com.enunas.backend.product.Product;
import com.enunas.backend.product.ProductRepository;
import com.enunas.backend.product.ProductService;
import com.enunas.backend.product.ProductStatus;
import com.enunas.backend.product.dto.UpdateProductDto;
import com.enunas.backend.user.EmailService;
import com.enunas.backend.user.Role;
import com.enunas.backend.user.User;
import com.enunas.backend.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Centralized admin business logic. Admin is identified purely by ROLE_ADMIN — there is no
 * Admin entity or repository. All access is gated by class-level @PreAuthorize and the
 * security filter on /admin/**.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminService {

    private final BrandPartnerRepository brandPartnerRepository;
    private final BrandPayoutProfileRepository brandPayoutProfileRepository;
    private final BrandShippingProfileRepository brandShippingProfileRepository;
    private final ReconciliationService reconciliationService;
    private final PayoutService payoutService;
    private final ProductRepository productRepository;
    private final ProductService productService;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final MediaUrlResolver mediaUrlResolver;

    // ===== Brand management =====

    /**
     * Approve a brand application — flips BrandPartner.status=ACTIVE, BrandPartner.approved=true,
     * and User.adminApproved=true (defensive: ensures role is BRAND_PARTNER) in one transaction.
     */
    @Transactional
    public BrandPartnerResponseDto approveBrand(Long brandId) {
        BrandPartner brand = findBrand(brandId);
        User user = brand.getUser();

        brand.setStatus(BrandStatus.ACTIVE);

        user.setAdminApproved(true);
        // Operator approval is the sole gate: ensure the account is enabled regardless of whether
        // email verification ever happened (onboarding no longer depends on it).
        user.setEnabled(true);
        if (user.getRole() != Role.BRAND_PARTNER) {
            user.setRole(Role.BRAND_PARTNER);
        }

        userRepository.save(user);
        BrandPartner saved = brandPartnerRepository.save(brand);

        emailService.sendAccountApprovedEmail(user.getEmail());
        log.info("Brand approved by admin: {} ({})", brand.getBrandName(), user.getEmail());

        return BrandPartnerResponseDto.from(saved, mediaUrlResolver);
    }

    /** Reject a brand application — sets status=REJECTED. User stays disabled (login already blocked). */
    @Transactional
    public BrandPartnerResponseDto rejectBrand(Long brandId) {
        BrandPartner brand = findBrand(brandId);
        brand.setStatus(BrandStatus.REJECTED);
        BrandPartner saved = brandPartnerRepository.save(brand);

        log.info("Brand rejected by admin: {} ({})", brand.getBrandName(), brand.getUser().getEmail());
        return BrandPartnerResponseDto.from(saved, mediaUrlResolver);
    }

    @Transactional
    public BrandPartnerResponseDto suspendBrand(Long brandId) {
        BrandPartner brand = findBrand(brandId);
        brand.setStatus(BrandStatus.SUSPENDED);
        BrandPartner saved = brandPartnerRepository.save(brand);

        log.info("Brand suspended by admin: {} ({})", brand.getBrandName(), brand.getUser().getEmail());
        return BrandPartnerResponseDto.from(saved, mediaUrlResolver);
    }

    @Transactional(readOnly = true)
    public Page<BrandPartnerResponseDto> getAllBrands(Pageable pageable) {
        return brandPartnerRepository.findAll(pageable).map(b -> BrandPartnerResponseDto.from(b, mediaUrlResolver));
    }

    // ===== Product moderation =====

    @Transactional(readOnly = true)
    public Page<AdminProductResponseDto> getAllProducts(Pageable pageable) {
        return productRepository.findAll(pageable).map(p -> AdminProductResponseDto.from(p, mediaUrlResolver));
    }

    /** Admin can edit any product — no ownership check (ownership stays with BrandPartner). */
    @Transactional
    public AdminProductResponseDto updateProduct(Long productId, UpdateProductDto dto) {
        Product product = findProduct(productId);
        productService.applyProductUpdates(product, dto);
        return AdminProductResponseDto.from(productRepository.save(product), mediaUrlResolver);
    }

    @Transactional
    public void deleteProduct(Long productId) {
        Product product = findProduct(productId);
        productRepository.delete(product);
        log.info("Product deleted by admin: id={}", productId);
    }

    /** Reinstate a product — sets status=ACTIVE, clears any prior rejection reason. */
    @Transactional
    public AdminProductResponseDto approveProduct(Long productId, User admin) {
        Product product = findProduct(productId);
        product.setStatus(ProductStatus.ACTIVE);
        product.setRejectionReason(null);
        product.setModeratedBy(admin);
        product.setModeratedAt(LocalDateTime.now());

        log.info("Product approved by admin {}: id={}", admin.getEmail(), productId);
        return AdminProductResponseDto.from(productRepository.save(product), mediaUrlResolver);
    }

    /** Reject a product — status=REJECTED, stores the (optional) reason and moderation metadata. */
    @Transactional
    public AdminProductResponseDto rejectProduct(Long productId, RejectionDto dto, User admin) {
        Product product = findProduct(productId);
        product.setStatus(ProductStatus.REJECTED);
        product.setRejectionReason(dto != null ? dto.getReason() : null);
        product.setModeratedBy(admin);
        product.setModeratedAt(LocalDateTime.now());

        log.info("Product rejected by admin {}: id={}, reason={}",
                admin.getEmail(), productId, product.getRejectionReason());
        return AdminProductResponseDto.from(productRepository.save(product), mediaUrlResolver);
    }

    /** Hide a product — status=SUSPENDED, invisible to customers but not permanently rejected. */
    @Transactional
    public AdminProductResponseDto hideProduct(Long productId, User admin) {
        Product product = findProduct(productId);
        product.setStatus(ProductStatus.SUSPENDED);
        product.setModeratedBy(admin);
        product.setModeratedAt(LocalDateTime.now());

        log.info("Product hidden by admin {}: id={}", admin.getEmail(), productId);
        return AdminProductResponseDto.from(productRepository.save(product), mediaUrlResolver);
    }

    // ===== Payout Profile =====

    @Transactional
    public BrandPartnerResponseDto setBrandPayoutProfile(Long brandId, String iban, String bankAccountHolder) {
        BrandPartner brand = findBrand(brandId);
        brandPayoutProfileRepository.findByBrandPartner_Id(brandId).ifPresentOrElse(
                profile -> {
                    profile.setIban(iban);
                    profile.setBankAccountHolder(bankAccountHolder);
                    brandPayoutProfileRepository.save(profile);
                },
                () -> brandPayoutProfileRepository.save(
                        BrandPayoutProfile.builder()
                                .brandPartner(brand)
                                .iban(iban)
                                .bankAccountHolder(bankAccountHolder)
                                .build())
        );
        log.info("Admin set payout profile for brand {}: iban={}", brand.getBrandName(), iban);
        return BrandPartnerResponseDto.from(brand, mediaUrlResolver);
    }

    /**
     * Sets (or replaces) a brand's shipping profile.
     *
     * <p><b>FULL-REPLACE SEMANTICS — read this before calling.</b> Despite being exposed over HTTP
     * {@code PATCH}, this is <b>not</b> a partial update. All three mutable fields
     * ({@code shippingCost}, {@code originCountry}, {@code avgShippingDays}) are written
     * unconditionally from the DTO on every call. <b>A field omitted from the request JSON
     * deserializes to {@code null} and therefore CLEARS the stored value.</b> Sending
     * {@code {"avgShippingDays": 3}} against a brand configured at €9.99 wipes {@code shippingCost}
     * back to "not configured", and {@code FlatRateShippingCostService} then falls back to the
     * platform {@code GLOBAL_DEFAULT} rate on that brand's every subsequent order.
     *
     * <p><b>Contract for clients: always submit all three fields together</b>, echoing back the
     * values you do not intend to change. This is a form-style "save the whole shipping config"
     * endpoint, not a field-level patch.
     *
     * <p>This is deliberate rather than accidental: it is the only behaviour that keeps
     * {@code shippingCost: null} ("clear back to not configured") reachable at all, given
     * {@link SetShippingProfileDto} has no way to distinguish "field absent from JSON" from "field
     * present and explicitly null" without an {@code Optional}/{@code JsonNullable} wrapper type.
     * The three shippingCost outcomes it must preserve — {@code null} = unset, {@code 0.00} =
     * explicit free shipping, {@code > 0.00} = flat rate — are documented on
     * {@link com.enunas.backend.brandpartner.brandshippingprofile.BrandShippingProfile#getShippingCost()}.
     * Introducing real presence-tracking is the correct long-term fix and would make this a true
     * PATCH; until then the contract above is authoritative and is pinned by
     * {@code AdminShippingProfileIntegrationTest.patchOmittingShippingCost_clearsIt_fullReplaceContract}.
     */
    @Transactional
    public BrandPartnerResponseDto setBrandShippingProfile(Long brandId, SetShippingProfileDto dto) {
        BrandPartner brand = findBrand(brandId);
        brandShippingProfileRepository.findByBrandPartner_Id(brandId).ifPresentOrElse(
                profile -> {
                    // Unconditional by design — see this method's javadoc. Do NOT "fix" these into
                    // null-guarded setters without also giving the DTO presence-tracking, or
                    // clearing shippingCost back to "not configured" becomes impossible.
                    profile.setShippingCost(dto.getShippingCost());
                    profile.setOriginCountry(dto.getOriginCountry());
                    profile.setAvgShippingDays(dto.getAvgShippingDays());
                    brandShippingProfileRepository.save(profile);
                },
                () -> brandShippingProfileRepository.save(
                        BrandShippingProfile.builder()
                                .brandPartner(brand)
                                .shippingCost(dto.getShippingCost())
                                .originCountry(dto.getOriginCountry())
                                .avgShippingDays(dto.getAvgShippingDays())
                                .currency("EUR")
                                .build())
        );
        log.info("Admin set shipping profile for brand {}: shippingCost={}", brand.getBrandName(), dto.getShippingCost());
        return BrandPartnerResponseDto.from(brand, mediaUrlResolver);
    }

    // ===== Payouts =====

    public List<PayoutResponseDto> generatePayouts() {
        return payoutService.generatePayouts();
    }

    public org.springframework.data.domain.Page<PayoutResponseDto> listPayouts(
            PayoutStatus status, org.springframework.data.domain.Pageable pageable) {
        return status != null
                ? payoutService.listPayoutsByStatus(status, pageable)
                : payoutService.listPayouts(pageable);
    }

    public PayoutResponseDto getPayoutById(Long payoutId) {
        return payoutService.getById(payoutId);
    }

    public PayoutResponseDto approvePayout(Long payoutId, User admin) {
        return payoutService.approvePayout(payoutId, admin.getEmail());
    }

    public PayoutResponseDto markPayoutAsPaid(Long payoutId, MarkAsPaidDto dto, User admin) {
        return payoutService.markAsPaid(payoutId, dto, admin.getEmail());
    }

    public PayoutResponseDto cancelPayout(Long payoutId) {
        return payoutService.cancelPayout(payoutId);
    }

    public PayoutDashboardDto getPayoutDashboard() {
        return payoutService.getDashboard();
    }

    // ===== Reconciliation =====

    public List<ReconciliationService.DriftReport> checkReconciliation() {
        return reconciliationService.checkAllBrands();
    }

    public ReconciliationService.DriftReport checkBrandReconciliation(Long brandId) {
        return reconciliationService.checkBrand(brandId);
    }

    public ReconciliationService.DriftReport rebuildBrandEconomics(Long brandId) {
        log.warn("Admin triggered ledger rebuild for brand={}", brandId);
        return reconciliationService.rebuildFromLedger(brandId);
    }

    // ===== Internal helpers =====

    private BrandPartner findBrand(Long brandId) {
        return brandPartnerRepository.findById(brandId)
                .orElseThrow(() -> new BrandNotFoundException("Brand not found with id: " + brandId));
    }

    private Product findProduct(Long productId) {
        return productRepository.findById(productId)
                .orElseThrow(() -> new ProductNotFoundException("Product not found with id: " + productId));
    }
}
