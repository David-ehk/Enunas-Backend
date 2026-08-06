package com.enunas.backend.brandpartner;

import com.enunas.backend.brandpartner.brandeconomics.BrandEconomicsRepository;
import com.enunas.backend.user.EmailService;
import com.enunas.backend.user.Role;
import com.enunas.backend.user.User;
import com.enunas.backend.user.UserRepository;
import com.enunas.backend.user.dto.VerifyUserDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * N1: verifyBrandApplicant/resendVerificationCode must normalize email on READ, mirroring
 * applyForBrand's normalize-on-WRITE (I4) -- otherwise a brand applicant who applied as
 * "John@Example.com" (persisted as "john@example.com") can never verify or resend a code
 * using the exact string they applied with.
 */
@ExtendWith(MockitoExtension.class)
class BrandPartnerEmailNormalizationTest {

    @Mock private BrandPartnerRepository brandPartnerRepository;
    @Mock private BrandEconomicsRepository brandEconomicsRepository;
    @Mock private UserRepository userRepository;
    @Mock private BCryptPasswordEncoder passwordEncoder;
    @Mock private EmailService emailService;
    @Mock private ApplicationEventPublisher applicationEventPublisher;

    @InjectMocks private BrandPartnerService service;

    @Test
    void verifyBrandApplicant_mixedCaseEmail_looksUpByNormalizedEmail() {
        User applicant = User.builder().id(1L).email("applicant@example.com").role(Role.BRAND_PARTNER)
                .enabled(false).adminApproved(false)
                .verificationCode("123456")
                .verificationCodeExpiresAt(LocalDateTime.now().plusMinutes(10))
                .build();
        when(userRepository.findByEmail("applicant@example.com")).thenReturn(Optional.of(applicant));

        VerifyUserDto dto = new VerifyUserDto();
        dto.setEmail("  Applicant@Example.com  ");
        dto.setVerificationCode("123456");

        service.verifyBrandApplicant(dto);

        verify(userRepository).findByEmail("applicant@example.com");
        verify(userRepository, never()).findByEmail("  Applicant@Example.com  ");
        verify(userRepository).save(applicant);
    }

    @Test
    void resendVerificationCode_mixedCaseEmail_looksUpByNormalizedEmail() {
        User applicant = User.builder().id(2L).email("resend@example.com").role(Role.BRAND_PARTNER)
                .enabled(false).adminApproved(false)
                .build();
        when(userRepository.findByEmail("resend@example.com")).thenReturn(Optional.of(applicant));

        service.resendVerificationCode("  Resend@Example.com  ");

        verify(userRepository).findByEmail("resend@example.com");
        verify(userRepository, never()).findByEmail("  Resend@Example.com  ");
        verify(userRepository).save(applicant);
    }
}
