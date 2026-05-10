package com.enunas.backend.admin;

import com.enunas.backend.admin.dto.AdminProductResponseDto;
import com.enunas.backend.admin.dto.RejectionDto;
import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.BrandPartnerRepository;
import com.enunas.backend.brandpartner.BrandStatus;
import com.enunas.backend.brandpartner.brandpayoutprofile.BrandPayoutProfile;
import com.enunas.backend.brandpartner.brandpayoutprofile.BrandPayoutProfileRepository;
import com.enunas.backend.ledger.ReconciliationService;
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
    private final ReconciliationService reconciliationService;
    private final PayoutService payoutService;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;

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
        if (user.getRole() != Role.BRAND_PARTNER) {
            user.setRole(Role.BRAND_PARTNER);
        }

        userRepository.save(user);
        BrandPartner saved = brandPartnerRepository.save(brand);

        emailService.sendAccountApprovedEmail(user.getEmail());
        log.info("Brand approved by admin: {} ({})", brand.getBrandName(), user.getEmail());

        return BrandPartnerResponseDto.from(saved);
    }

    /** Reject a brand application — sets status=REJECTED. User stays disabled (login already blocked). */
    @Transactional
    public BrandPartnerResponseDto rejectBrand(Long brandId) {
        BrandPartner brand = findBrand(brandId);
        brand.setStatus(BrandStatus.REJECTED);
        BrandPartner saved = brandPartnerRepository.save(brand);

        log.info("Brand rejected by admin: {} ({})", brand.getBrandName(), brand.getUser().getEmail());
        return BrandPartnerResponseDto.from(saved);
    }

    @Transactional
    public BrandPartnerResponseDto suspendBrand(Long brandId) {
        BrandPartner brand = findBrand(brandId);
        brand.setStatus(BrandStatus.SUSPENDED);
        BrandPartner saved = brandPartnerRepository.save(brand);

        log.info("Brand suspended by admin: {} ({})", brand.getBrandName(), brand.getUser().getEmail());
        return BrandPartnerResponseDto.from(saved);
    }

    @Transactional(readOnly = true)
    public Page<BrandPartnerResponseDto> getAllBrands(Pageable pageable) {
        return brandPartnerRepository.findAll(pageable).map(BrandPartnerResponseDto::from);
    }

    // ===== Product moderation =====

    @Transactional(readOnly = true)
    public Page<AdminProductResponseDto> getAllProducts(Pageable pageable) {
        return productRepository.findAll(pageable).map(AdminProductResponseDto::from);
    }

    /** Admin can edit any product — no ownership check (ownership stays with BrandPartner). */
    @Transactional
    public AdminProductResponseDto updateProduct(Long productId, UpdateProductDto dto) {
        Product product = findProduct(productId);

        if (dto.getName() != null) product.setName(dto.getName());
        if (dto.getDescription() != null) product.setDescription(dto.getDescription());
        if (dto.getInspirationStory() != null) product.setInspirationStory(dto.getInspirationStory());
        if (dto.getCategory() != null) product.setCategory(dto.getCategory());
        if (dto.getCatalogueCategory() != null) product.setCatalogueCategory(dto.getCatalogueCategory());
        if (dto.getGender() != null) product.setGender(dto.getGender());
        if (dto.getMaterial() != null) product.setMaterial(dto.getMaterial());
        if (dto.getOriginCountry() != null) product.setOriginCountry(dto.getOriginCountry());
        if (dto.getCareInstructions() != null) product.setCareInstructions(dto.getCareInstructions());
        if (dto.getCollectionName() != null) product.setCollectionName(dto.getCollectionName());
        if (dto.getReleaseDate() != null) product.setReleaseDate(dto.getReleaseDate());
        if (dto.getReturnPeriodDays() != null) product.setReturnPeriodDays(dto.getReturnPeriodDays());

        return AdminProductResponseDto.from(productRepository.save(product));
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
        return AdminProductResponseDto.from(productRepository.save(product));
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
        return AdminProductResponseDto.from(productRepository.save(product));
    }

    /** Hide a product — status=SUSPENDED, invisible to customers but not permanently rejected. */
    @Transactional
    public AdminProductResponseDto hideProduct(Long productId, User admin) {
        Product product = findProduct(productId);
        product.setStatus(ProductStatus.SUSPENDED);
        product.setModeratedBy(admin);
        product.setModeratedAt(LocalDateTime.now());

        log.info("Product hidden by admin {}: id={}", admin.getEmail(), productId);
        return AdminProductResponseDto.from(productRepository.save(product));
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
        return BrandPartnerResponseDto.from(brand);
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
