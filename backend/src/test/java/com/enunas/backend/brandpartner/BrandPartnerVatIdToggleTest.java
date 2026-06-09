package com.enunas.backend.brandpartner;

import com.enunas.backend.brandpartner.brandeconomics.BrandEconomicsRepository;
import com.enunas.backend.brandpartner.dto.RegisterBrandPartnerDto;
import com.enunas.backend.user.EmailService;
import com.enunas.backend.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;

/**
 * The VAT_ID_REQUIRED toggle is a plain @NotBlank-style onboarding gate — never a format/correctness
 * validation. When ON, an application without a vatId is rejected (the guard fires before any save,
 * so no further mocking is needed).
 */
@ExtendWith(MockitoExtension.class)
class BrandPartnerVatIdToggleTest {

    @Mock private BrandPartnerRepository brandPartnerRepository;
    @Mock private BrandEconomicsRepository brandEconomicsRepository;
    @Mock private UserRepository userRepository;
    @Mock private BCryptPasswordEncoder passwordEncoder;
    @Mock private EmailService emailService;

    @InjectMocks private BrandPartnerService service;

    private RegisterBrandPartnerDto dtoWithoutVatId() {
        RegisterBrandPartnerDto dto = new RegisterBrandPartnerDto();
        dto.setEmail("brand@apply.local");
        dto.setPassword("Brand123!");
        dto.setBrandName("Acme");
        dto.setLegalName("Acme GmbH");
        dto.setAddressStreet("Hauptstr. 1");
        dto.setAddressPostalCode("10115");
        dto.setAddressCity("Berlin");
        dto.setAddressCountry("DE");
        return dto;
    }

    @Test
    void vatIdRequiredTrue_rejectsApplicationWithoutVatId() {
        ReflectionTestUtils.setField(service, "vatIdRequired", true);
        ReflectionTestUtils.setField(service, "platformCommissionRate", new BigDecimal("0.18"));
        ReflectionTestUtils.setField(service, "adminEmail", "admin@enunas.com");
        // Pass the uniqueness checks so the flow reaches the vatId guard.
        lenient().when(userRepository.existsByEmail("brand@apply.local")).thenReturn(false);
        lenient().when(brandPartnerRepository.existsByBrandName("Acme")).thenReturn(false);
        lenient().when(brandPartnerRepository.existsBySlug("acme")).thenReturn(false);

        assertThatThrownBy(() -> service.applyForBrand(dtoWithoutVatId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("USt-IdNr");
    }
}
