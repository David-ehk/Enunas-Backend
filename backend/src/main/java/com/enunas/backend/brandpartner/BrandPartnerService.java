package com.enunas.backend.brandpartner;

import com.enunas.backend.brandpartner.brandeconomics.BrandEconomics;
import com.enunas.backend.brandpartner.brandeconomics.BrandEconomicsRepository;
import com.enunas.backend.brandpartner.dto.AdminBrandMasterDataDto;
import com.enunas.backend.brandpartner.dto.BrandPartnerResponseDto;
import com.enunas.backend.brandpartner.dto.RegisterBrandPartnerDto;
import com.enunas.backend.brandpartner.dto.UpdateBrandPartnerDto;
import com.enunas.backend.exception.BrandNotFoundException;
import com.enunas.backend.media.dto.PresignUploadRequestDto;
import com.enunas.backend.media.dto.PresignUploadResponseDto;
import com.enunas.backend.media.storage.MediaPurpose;
import com.enunas.backend.media.storage.MediaStorageService;
import com.enunas.backend.media.storage.MediaUrlResolver;
import org.springframework.context.ApplicationEventPublisher;
import com.enunas.backend.user.EmailNormalizer;
import com.enunas.backend.user.Role;
import com.enunas.backend.user.User;
import com.enunas.backend.user.UserRepository;
import com.enunas.backend.user.dto.VerifyUserDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.security.SecureRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class BrandPartnerService {

    private final BrandPartnerRepository brandPartnerRepository;
    private final BrandEconomicsRepository brandEconomicsRepository;
    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final MediaStorageService mediaStorageService;
    private final MediaUrlResolver mediaUrlResolver;

    @Value("${enunas.platform.commission-rate:0.18}")
    private BigDecimal platformCommissionRate;

    @Value("${admin.email}")
    private String adminEmail;

    /**
     * When true, USt-IdNr (vatId) is a hard onboarding requirement (simple @NotBlank-style check —
     * NEVER a format/correctness validation). Default false: completeness is verified manually.
     */
    @Value("${enunas.brand.vat-id-required:false}")
    private boolean vatIdRequired;

    /**
     * Brand-partner application: creates the User account AND BrandPartner record
     * in a single transaction. Server-set role; never read from client. The applicant
     * is gated until email verification + admin approval.
     */
    @Transactional
    public BrandPartnerResponseDto applyForBrand(RegisterBrandPartnerDto dto) {
        // Normalize BEFORE the duplicate check: users.email is case-sensitive in Postgres, and the
        // Google flow stores normalized emails — without this, "John@Example.com" would slip past
        // existsByEmail and create a second User row for the same person.
        String normalizedEmail = EmailNormalizer.normalize(dto.getEmail());
        if (userRepository.existsByEmail(normalizedEmail)) {
            log.warn("Brand application failed: email already registered: {}", normalizedEmail);
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

        if (vatIdRequired && (dto.getVatId() == null || dto.getVatId().isBlank())) {
            throw new IllegalArgumentException("USt-IdNr (vatId) is required");
        }

        // enabled=false until verifyBrandApplicant() flips it: login is gated on BOTH email
        // verification AND operator approval (see AuthenticationService.assertAccountActive's
        // dedicated BRAND_PARTNER branch — "verify your email first" only makes sense if this
        // starts false). A commit in Jun 2026 flipped this to enabled=true without updating
        // verifyBrandApplicant()'s "already verified" guard to match, which made /brandpartner/verify
        // reject every real applicant on their first (and only) attempt. Reverted — email
        // verification is a real gate again, not just a best-effort courtesy email.
        User user = User.builder()
                .email(normalizedEmail)
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
                .firstName(dto.getFirstName())
                .lastName(dto.getLastName())
                .description(dto.getDescription())
                .websiteUrl(dto.getWebsiteUrl())
                .instagramHandle(dto.getInstagramHandle())
                .tiktokHandle(dto.getTiktokHandle())
                .country(dto.getCountry())
                .contactEmail(dto.getContactEmail() != null ? dto.getContactEmail() : normalizedEmail)
                // Returns destination — optional; null here means returns fall back to the §22f
                // address. Set outside applyMasterData on purpose: it must not touch `domestic`.
                .returnRecipient(dto.getReturnRecipient())
                .returnStreet(dto.getReturnStreet())
                .returnPostalCode(dto.getReturnPostalCode())
                .returnCity(dto.getReturnCity())
                .returnCountry(normalizeCountry(dto.getReturnCountry()))
                .returnInstructions(dto.getReturnInstructions())
                .status(BrandStatus.PENDING_REVIEW)
                .approved(false)
                .build();
        // §22f master data via the shared helper (identical to what the admin update writes).
        applyMasterData(brand, dto.getLegalName(), dto.getAddressStreet(), dto.getAddressPostalCode(),
                dto.getAddressCity(), dto.getAddressCountry(), dto.getVatId(), dto.getTaxNumber());
        BrandPartner saved = brandPartnerRepository.save(brand);

        brandEconomicsRepository.save(BrandEconomics.builder()
                .brandPartner(saved)
                .defaultCommissionRate(platformCommissionRate)
                .build());

        // Best-effort verification email, dispatched AFTER_COMMIT — never blocks/rolls back the apply.
        applicationEventPublisher.publishEvent(
                new BrandApplicationSubmittedEvent(user.getEmail(), user.getVerificationCode()));
        log.info("Brand application submitted: {} ({})", dto.getBrandName(), user.getEmail());

        return BrandPartnerResponseDto.from(saved, mediaUrlResolver);
    }

    /** Email verification step for brand applicants. Flips User.enabled and notifies admin. */
    @Transactional
    public void verifyBrandApplicant(VerifyUserDto dto) {
        User user = userRepository.findByEmail(EmailNormalizer.normalize(dto.getEmail()))
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (user.getRole() != Role.BRAND_PARTNER) {
            throw new IllegalStateException("Verification is only required for Brand Partners");
        }
        if (user.isEnabled()) {
            throw new IllegalStateException("Email already verified");
        }
        if (user.getVerificationCode() == null
                || user.getVerificationCodeExpiresAt() == null
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

        // Best-effort applicant + admin notification emails, dispatched AFTER_COMMIT — never
        // blocks/rolls back the already-persisted verification.
        applicationEventPublisher.publishEvent(
                new BrandVerificationCompletedEvent(user.getEmail(), user.getId(), adminEmail));
    }

    /** Re-issue the verification code for a brand applicant whose code expired. */
    @Transactional
    public void resendVerificationCode(String email) {
        User user = userRepository.findByEmail(EmailNormalizer.normalize(email))
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

        // Reuses the apply flow's own best-effort, AFTER_COMMIT verification email — same shape,
        // same listener (BrandApplicationEmailListener) — never blocks/rolls back the new code.
        applicationEventPublisher.publishEvent(
                new BrandApplicationSubmittedEvent(user.getEmail(), user.getVerificationCode()));
        log.info("New verification code requested for brand applicant: {}", user.getEmail());
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('BRAND_PARTNER')")
    public BrandPartnerResponseDto getMyProfile(User user) {
        return BrandPartnerResponseDto.from(findByUser(user), mediaUrlResolver);
    }

    @Transactional
    @PreAuthorize("hasRole('BRAND_PARTNER')")
    public BrandPartnerResponseDto updateMyProfile(UpdateBrandPartnerDto dto, User user) {
        BrandPartner brand = findByUser(user);

        if (dto.getDescription() != null) brand.setDescription(dto.getDescription());
        if (dto.getLogoStorageKey() != null) {
            mediaStorageService.verifyUploaded(dto.getLogoStorageKey(), MediaPurpose.BRAND_LOGO, brand.getId());
            brand.setLogoStorageKey(dto.getLogoStorageKey());
        }
        if (dto.getHeroStorageKey() != null) {
            mediaStorageService.verifyUploaded(dto.getHeroStorageKey(), MediaPurpose.BRAND_HERO, brand.getId());
            brand.setHeroStorageKey(dto.getHeroStorageKey());
        }
        if (dto.getWebsiteUrl() != null) brand.setWebsiteUrl(dto.getWebsiteUrl());
        if (dto.getInstagramHandle() != null) brand.setInstagramHandle(dto.getInstagramHandle());
        if (dto.getTiktokHandle() != null) brand.setTiktokHandle(dto.getTiktokHandle());
        if (dto.getCountry() != null) brand.setCountry(dto.getCountry());
        if (dto.getContactEmail() != null) brand.setContactEmail(dto.getContactEmail());
        if (dto.getVatId() != null) brand.setVatId(dto.getVatId());
        if (dto.getTaxNumber() != null) brand.setTaxNumber(dto.getTaxNumber());
        if (dto.getLegalName() != null) brand.setLegalName(dto.getLegalName());
        if (dto.getAddressStreet() != null) brand.setAddressStreet(dto.getAddressStreet());
        if (dto.getAddressPostalCode() != null) brand.setAddressPostalCode(dto.getAddressPostalCode());
        if (dto.getAddressCity() != null) brand.setAddressCity(dto.getAddressCity());
        if (dto.getAddressCountry() != null) brand.setAddressCountry(dto.getAddressCountry());

        // Returns destination. Logistics only — deliberately NOT routed through applyMasterData,
        // because `domestic` must stay derived from addressCountry: a warehouse in another country
        // changes where parcels go, never how commission is taxed.
        if (dto.getReturnRecipient() != null) brand.setReturnRecipient(dto.getReturnRecipient());
        if (dto.getReturnStreet() != null) brand.setReturnStreet(dto.getReturnStreet());
        if (dto.getReturnPostalCode() != null) brand.setReturnPostalCode(dto.getReturnPostalCode());
        if (dto.getReturnCity() != null) brand.setReturnCity(dto.getReturnCity());
        if (dto.getReturnCountry() != null) brand.setReturnCountry(normalizeCountry(dto.getReturnCountry()));
        if (dto.getReturnInstructions() != null) brand.setReturnInstructions(dto.getReturnInstructions());

        return BrandPartnerResponseDto.from(brandPartnerRepository.save(brand), mediaUrlResolver);
    }

    @PreAuthorize("hasRole('BRAND_PARTNER')")
    public PresignUploadResponseDto presignMediaUpload(PresignUploadRequestDto dto, User user) {
        BrandPartner brand = findByUser(user);
        if (dto.getPurpose().scope() != MediaPurpose.Scope.BRAND) {
            throw new IllegalArgumentException(
                    "purpose " + dto.getPurpose() + " is not valid for brand media upload");
        }
        MediaStorageService.PresignedUpload upload = mediaStorageService.presignUpload(
                dto.getPurpose(), brand.getId(), dto.getContentType(), dto.getContentLength());
        return PresignUploadResponseDto.from(upload);
    }

    /**
     * Admin-only update of a brand's §22f master data — legal name + address (mandatory) and the
     * tax identifiers. Scoped strictly to these fields: no financial/snapshot fields, no payout
     * profile, no {@code domestic} flag. Reuses {@link #applyMasterData} so the admin and vendor/
     * onboarding paths can never drift.
     */
    @Transactional
    public BrandPartnerResponseDto updateBrandMasterData(Long brandId, AdminBrandMasterDataDto dto) {
        BrandPartner brand = brandPartnerRepository.findById(brandId)
                .orElseThrow(() -> new BrandNotFoundException("Brand not found with id: " + brandId));
        applyMasterData(brand, dto.getLegalName(), dto.getAddressStreet(), dto.getAddressPostalCode(),
                dto.getAddressCity(), dto.getAddressCountry(), dto.getVatId(), dto.getTaxNumber());
        return BrandPartnerResponseDto.from(brandPartnerRepository.save(brand), mediaUrlResolver);
    }

    /**
     * Single place that writes the §22f master-data fields onto a brand (DRY across apply + admin
     * update). {@code addressCountry} is the ONLY country source: it is normalized to upper-case and
     * {@code domestic} is DERIVED from it (DE ⇒ domestic). {@code domestic} is never set
     * independently — this keeps the reverse-charge input correct without touching the downstream
     * commission/VAT logic.
     */
    private void applyMasterData(BrandPartner brand, String legalName, String addressStreet,
                                 String addressPostalCode, String addressCity, String addressCountry,
                                 String vatId, String taxNumber) {
        String normalizedCountry = normalizeCountry(addressCountry);
        brand.setLegalName(legalName);
        brand.setAddressStreet(addressStreet);
        brand.setAddressPostalCode(addressPostalCode);
        brand.setAddressCity(addressCity);
        brand.setAddressCountry(normalizedCountry);
        brand.setDomestic("DE".equals(normalizedCountry)); // derived — single source of truth
        brand.setVatId(vatId);
        brand.setTaxNumber(taxNumber);
    }

    /** ISO 3166-1 alpha-2 normalization, shared by the §22f and returns-address paths. */
    private String normalizeCountry(String country) {
        return country != null ? country.trim().toUpperCase() : null;
    }

    @Transactional(readOnly = true)
    public BrandPartnerResponseDto getBrandById(Long id) {
        return BrandPartnerResponseDto.from(
                brandPartnerRepository.findById(id)
                        .orElseThrow(() -> new BrandNotFoundException("Brand not found with id: " + id)),
                mediaUrlResolver
        );
    }

    public BrandPartner findByUser(User user) {
        return brandPartnerRepository.findByUser(user)
                .orElseThrow(() -> new BrandNotFoundException(
                        "No brand profile found for user: " + user.getEmail()));
    }

    private String generateVerificationCode() {
        return String.valueOf(new SecureRandom().nextInt(900000) + 100000);
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
}
