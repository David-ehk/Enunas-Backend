package com.enunas.backend.brandpartner.brandshippingprofile;

import com.enunas.backend.brandpartner.BrandPartner;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "brand_shipping_profiles")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrandShippingProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id", nullable = false, unique = true)
    private BrandPartner brandPartner;

    private String originCountry;

    @Builder.Default
    private boolean handlesOwnShipping = false;

    private Integer avgShippingDays;
}
