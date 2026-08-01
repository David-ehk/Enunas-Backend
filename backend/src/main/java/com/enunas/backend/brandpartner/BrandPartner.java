package com.enunas.backend.brandpartner;

import com.enunas.backend.product.Product;
import com.enunas.backend.user.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Entity
@Table(name = "brand_partners")
@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class BrandPartner {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(nullable = false, unique = true)
    private String brandName;

    /** URL-safe identifier derived from brandName, used for routing (e.g. /brand/{slug}). */
    @Column(nullable = false, unique = true)
    private String slug;

    /**
     * Contact person behind the brand. Nullable in DB (brands onboarded before V12 have none);
     * required at onboarding via @NotBlank in RegisterBrandPartnerDto.
     */
    private String firstName;

    private String lastName;

    @Column(columnDefinition = "TEXT")
    private String description;

    private String logoUrl;

    private String websiteUrl;

    private String instagramHandle;

    private String tiktokHandle;

    /** Onboarding metadata — typically an ISO 3166-1 alpha-2 country code. */
    private String country;

    /**
     * Whether the brand is a domestic (German) VAT-registered business. Drives VAT treatment of
     * the platform commission: domestic ⇒ commission carries 19% VAT; foreign ⇒ reverse charge
     * (commissionVat = 0). Set explicitly during onboarding — never inferred from {@link #country}.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean domestic = true;

    /** USt-IdNr (VAT identification number). Nullable; feeds the future commission Gutschrift. */
    private String vatId;

    /** Steuernummer (German tax number). Nullable; optional alongside {@link #vatId}. */
    private String taxNumber;

    // ===== §22f UStG: supplier legal name + postal address (Pflichtangabe 1; also serves as the
    // shipment-origin / Versandursprung, Pflichtangabe 4, assuming the brand ships from its business
    // address). Nullable in DB; required at onboarding via the DTO. =====
    // This is TAX master data. Where customers send RETURNS is the separate return* block below.

    private String legalName;

    private String addressStreet;

    private String addressPostalCode;

    private String addressCity;

    /** ISO 3166-1 alpha-2 country code of the business address. */
    private String addressCountry;

    // ===== Returns destination — where customers physically ship goods back to. =====
    // Deliberately separate from the §22f block above: that address identifies the supplier for
    // tax purposes, this one routes parcels. A brand fulfilling through a 3PL or a dedicated
    // returns warehouse sets these; everything is nullable and a brand that sets nothing falls
    // back to its §22f address (see BrandReturnAddress.of — the single fallback rule).
    //
    // NEVER feed these into `domestic`. That flag is derived from addressCountry alone
    // (BrandPartnerService.applyMasterData) and a warehouse abroad must not flip VAT treatment.

    private String returnRecipient;

    private String returnStreet;

    private String returnPostalCode;

    private String returnCity;

    /** ISO 3166-1 alpha-2 country code of the returns destination. */
    private String returnCountry;

    /** Free-text handling notes shown to the customer alongside the address (e.g. gate code). */
    @Column(columnDefinition = "TEXT")
    private String returnInstructions;

    /** Public business contact email; distinct from the User login email. */
    private String contactEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    @Setter(AccessLevel.NONE)
    private BrandStatus status = BrandStatus.PENDING_REVIEW;

    // Kept in sync with status — use setStatus() rather than setting this directly
    @Builder.Default
    @Setter(AccessLevel.NONE)
    private boolean approved = false;

    public void setStatus(BrandStatus status) {
        this.status = status;
        this.approved = (status == BrandStatus.ACTIVE);
    }

    @OneToMany(mappedBy = "brand", fetch = FetchType.LAZY,
               cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private List<Product> products = new ArrayList<>();

    @Column(updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public List<Product> getProducts() {
        return products == null ? List.of() : Collections.unmodifiableList(products);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BrandPartner other)) return false;
        return id != null && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
