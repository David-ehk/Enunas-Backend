package com.enunas.backend.brandpartner;

import com.enunas.backend.brandpartner.dto.BrandPartnerResponseDto;
import com.enunas.backend.brandpartner.dto.RegisterBrandPartnerDto;
import com.enunas.backend.brandpartner.dto.UpdateBrandPartnerDto;
import com.enunas.backend.exception.BrandNotFoundException;
import com.enunas.backend.user.EmailService;
import com.enunas.backend.user.Role;
import com.enunas.backend.user.User;
import com.enunas.backend.user.UserRepository;
import com.enunas.backend.user.dto.VerifyUserDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Random;

@Slf4j
@Service
@RequiredArgsConstructor
public class BrandPartnerService {

    private final BrandPartnerRepository brandPartnerRepository;
    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final EmailService emailService;

    @Value("${admin.email}")
    private String adminEmail;

    /**
     * Brand-partner application: creates the User account AND BrandPartner record
     * in a single transaction. Server-set role; never read from client. The applicant
     * is gated until email verification + admin approval.
     */
    @Transactional
    public BrandPartnerResponseDto applyForBrand(RegisterBrandPartnerDto dto) {
        if (userRepository.existsByEmail(dto.getEmail())) {
            log.warn("Brand application failed: email already registered: {}", dto.getEmail());
            throw new IllegalArgumentException("Email already registered");
        }
        if (brandPartnerRepository.existsByBrandName(dto.getBrandName())) {
            log.warn("Brand application failed: brand name already taken: {}", dto.getBrandName());
            throw new IllegalArgumentException("Brand name already taken: " + dto.getBrandName());
        }

        String slug = slugify(dto.getBrandName());
        if (slug.isEmpty() || brandPartnerRepository.existsBySlug(slug)) {
            log.warn("Brand application failed: slug collision or empty for brand: {}", dto.getBrandName());
            throw new IllegalArgumentException("Brand name produces an invalid or already-taken URL slug. Please choose a different brand name.");
        }

        User user = User.builder()
                .email(dto.getEmail())
                .password(passwordEncoder.encode(dto.getPassword()))
                .role(Role.BRAND_PARTNER)
                .enabled(false)
                .adminApproved(false)
                .verificationCode(generateVerificationCode())
                .verificationCodeExpiresAt(LocalDateTime.now().plusMinutes(15))
                .build();
        userRepository.save(user);

        BrandPartner brand = BrandPartner.builder()
                .user(user)
                .brandName(dto.getBrandName())
                .slug(slug)
                .description(dto.getDescription())
                .logoUrl(dto.getLogoUrl())
                .websiteUrl(dto.getWebsiteUrl())
                .instagramHandle(dto.getInstagramHandle())
                .tiktokHandle(dto.getTiktokHandle())
                .country(dto.getCountry())
                .contactEmail(dto.getContactEmail() != null ? dto.getContactEmail() : dto.getEmail())
                .status(BrandStatus.PENDING_REVIEW)
                .approved(false)
                .build();
        BrandPartner saved = brandPartnerRepository.save(brand);

        emailService.sendVerificationEmail(user.getEmail(), user.getVerificationCode());
        log.info("Brand application submitted: {} ({})", dto.getBrandName(), user.getEmail());

        return BrandPartnerResponseDto.from(saved);
    }

    /** Email verification step for brand applicants. Flips User.enabled and notifies admin. */
    @Transactional
    public void verifyBrandApplicant(VerifyUserDto dto) {
        User user = userRepository.findByEmail(dto.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (user.getRole() != Role.BRAND_PARTNER) {
            throw new IllegalStateException("Verification is only required for Brand Partners");
        }
        if (user.isEnabled()) {
            throw new IllegalStateException("Email already verified");
        }
        if (user.getVerificationCodeExpiresAt() == null
                || user.getVerificationCodeExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalStateException("Verification code expired");
        }
        if (!user.getVerificationCode().equals(dto.getVerificationCode())) {
            throw new IllegalArgumentException("Invalid verification code");
        }

        user.setEnabled(true);
        user.setVerificationCode(null);
        user.setVerificationCodeExpiresAt(null);
        userRepository.save(user);

        log.info("Brand applicant email verified: {}", user.getEmail());

        notifyAdminForApproval(user);
        emailService.sendPendingApprovalEmail(user.getEmail());
    }

    /** Re-issue the verification code for a brand applicant whose code expired. */
    @Transactional
    public void resendVerificationCode(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (user.getRole() != Role.BRAND_PARTNER) {
            throw new IllegalStateException("Verification is only required for Brand Partners");
        }
        if (user.isEnabled()) {
            throw new IllegalStateException("Email already verified");
        }

        user.setVerificationCode(generateVerificationCode());
        user.setVerificationCodeExpiresAt(LocalDateTime.now().plusMinutes(15));
        userRepository.save(user);

        emailService.sendVerificationEmail(user.getEmail(), user.getVerificationCode());
        log.info("New verification code sent to brand applicant: {}", email);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('BRAND_PARTNER')")
    public BrandPartnerResponseDto getMyProfile(User user) {
        return BrandPartnerResponseDto.from(findByUser(user));
    }

    @Transactional
    @PreAuthorize("hasRole('BRAND_PARTNER')")
    public BrandPartnerResponseDto updateMyProfile(UpdateBrandPartnerDto dto, User user) {
        BrandPartner brand = findByUser(user);

        if (dto.getDescription() != null) brand.setDescription(dto.getDescription());
        if (dto.getLogoUrl() != null) brand.setLogoUrl(dto.getLogoUrl());
        if (dto.getWebsiteUrl() != null) brand.setWebsiteUrl(dto.getWebsiteUrl());
        if (dto.getInstagramHandle() != null) brand.setInstagramHandle(dto.getInstagramHandle());
        if (dto.getTiktokHandle() != null) brand.setTiktokHandle(dto.getTiktokHandle());
        if (dto.getCountry() != null) brand.setCountry(dto.getCountry());
        if (dto.getContactEmail() != null) brand.setContactEmail(dto.getContactEmail());

        return BrandPartnerResponseDto.from(brandPartnerRepository.save(brand));
    }

    @Transactional(readOnly = true)
    public BrandPartnerResponseDto getBrandById(Long id) {
        return BrandPartnerResponseDto.from(
                brandPartnerRepository.findById(id)
                        .orElseThrow(() -> new BrandNotFoundException("Brand not found with id: " + id))
        );
    }

    public BrandPartner findByUser(User user) {
        return brandPartnerRepository.findByUser(user)
                .orElseThrow(() -> new BrandNotFoundException(
                        "No brand profile found for user: " + user.getEmail()));
    }

    private String generateVerificationCode() {
        return String.valueOf(new Random().nextInt(900000) + 100000);
    }

    /**
     * Convert a brand name to a URL-safe slug: lowercase, non-alphanumeric runs collapsed to "-",
     * leading/trailing dashes stripped. Example: "My Brand & Co!" → "my-brand-co".
     */
    private String slugify(String brandName) {
        return brandName.trim()
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    private void notifyAdminForApproval(User user) {
        String subject = "New Brand Partner pending approval";
        String message = String.format("""
                A new brand partner is awaiting admin approval.

                Email: %s
                User ID: %d

                Approve via: POST /admin/brands/{brandId}/approve
                """, user.getEmail(), user.getId());

        emailService.sendPlainTextEmail(adminEmail, subject, message);
        log.info("Admin notified for approval of: {}", user.getEmail());
    }
}
