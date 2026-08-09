package com.enunas.backend.shipping;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.brandshippingprofile.BrandShippingProfile;
import com.enunas.backend.brandpartner.brandshippingprofile.BrandShippingProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlatRateShippingCostServiceTest {

    @Mock private BrandShippingProfileRepository brandShippingProfileRepository;

    private FlatRateShippingCostService service;

    @BeforeEach
    void setUp() {
        service = new FlatRateShippingCostService(brandShippingProfileRepository);
        ReflectionTestUtils.setField(service, "defaultRate", new BigDecimal("4.99"));
    }

    private BrandPartner brand(long id) {
        return BrandPartner.builder().id(id).build();
    }

    @Test
    void noProfile_fallsBackToGlobalDefault() {
        when(brandShippingProfileRepository.findByBrandPartner_Id(1L)).thenReturn(Optional.empty());

        ShippingCostResult result = service.calculate(brand(1L), null, List.of());

        assertThat(result.amount()).isEqualByComparingTo("4.99");
        assertThat(result.method()).isEqualTo(ShippingCalculationMethod.GLOBAL_DEFAULT);
        assertThat(result.brandShippingProfileId()).isNull();
        assertThat(result.ruleVersion()).isEqualTo("flat-v1");
    }

    @Test
    void profileWithNullCost_fallsBackToGlobalDefault_butKeepsProfileId() {
        BrandShippingProfile profile = BrandShippingProfile.builder().id(55L).shippingCost(null).build();
        when(brandShippingProfileRepository.findByBrandPartner_Id(2L)).thenReturn(Optional.of(profile));

        ShippingCostResult result = service.calculate(brand(2L), null, List.of());

        assertThat(result.amount()).isEqualByComparingTo("4.99");
        assertThat(result.method()).isEqualTo(ShippingCalculationMethod.GLOBAL_DEFAULT);
        assertThat(result.brandShippingProfileId()).isEqualTo(55L);
    }

    @Test
    void profileWithZeroCost_isExplicitFreeShipping() {
        BrandShippingProfile profile = BrandShippingProfile.builder()
                .id(56L).shippingCost(new BigDecimal("0.00")).currency("EUR").build();
        when(brandShippingProfileRepository.findByBrandPartner_Id(3L)).thenReturn(Optional.of(profile));

        ShippingCostResult result = service.calculate(brand(3L), null, List.of());

        assertThat(result.amount()).isEqualByComparingTo("0.00");
        assertThat(result.method()).isEqualTo(ShippingCalculationMethod.BRAND_FREE_SHIPPING);
        assertThat(result.brandShippingProfileId()).isEqualTo(56L);
    }

    @Test
    void profileWithPositiveCost_isBrandFlatRate() {
        BrandShippingProfile profile = BrandShippingProfile.builder()
                .id(57L).shippingCost(new BigDecimal("6.99")).currency("EUR").build();
        when(brandShippingProfileRepository.findByBrandPartner_Id(4L)).thenReturn(Optional.of(profile));

        ShippingCostResult result = service.calculate(brand(4L), null, List.of());

        assertThat(result.amount()).isEqualByComparingTo("6.99");
        assertThat(result.method()).isEqualTo(ShippingCalculationMethod.BRAND_FLAT_RATE);
        assertThat(result.brandShippingProfileId()).isEqualTo(57L);
    }

    @Test
    void profileWithNegativeCost_isRejected_defensively() {
        BrandShippingProfile profile = BrandShippingProfile.builder()
                .id(59L).shippingCost(new BigDecimal("-5.00")).currency("EUR").build();
        when(brandShippingProfileRepository.findByBrandPartner_Id(6L)).thenReturn(Optional.of(profile));

        assertThatThrownBy(() -> service.calculate(brand(6L), null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    void negativeDefaultRate_failsFastAtStartup() {
        ReflectionTestUtils.setField(service, "defaultRate", new BigDecimal("-1.00"));

        assertThatThrownBy(service::validateDefaultRate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default-rate");
    }
}
