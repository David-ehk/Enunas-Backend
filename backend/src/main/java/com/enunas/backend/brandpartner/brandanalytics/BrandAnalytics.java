package com.enunas.backend.brandpartner.brandanalytics;

import com.enunas.backend.brandpartner.BrandPartner;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "brand_analytics")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrandAnalytics {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id", nullable = false, unique = true)
    private BrandPartner brand;

    @Builder.Default
    private Long totalViews = 0L;

    @Builder.Default
    private Long totalSales = 0L;

    @Builder.Default
    private BigDecimal revenue = BigDecimal.ZERO;

    private Double conversionRate;
}
