package com.enunas.backend.brandpartner;

import com.enunas.backend.brandpartner.brandeconomics.BrandEconomicsRepository;
import com.enunas.backend.media.dto.PresignUploadRequestDto;
import com.enunas.backend.brandpartner.dto.UpdateBrandPartnerDto;
import com.enunas.backend.media.storage.MediaPurpose;
import com.enunas.backend.media.storage.MediaStorageService;
import com.enunas.backend.media.storage.MediaUrlResolver;
import com.enunas.backend.user.EmailService;
import com.enunas.backend.user.Role;
import com.enunas.backend.user.User;
import com.enunas.backend.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BrandPartnerMediaServiceTest {

    @Mock private BrandPartnerRepository brandPartnerRepository;
    @Mock private BrandEconomicsRepository brandEconomicsRepository;
    @Mock private UserRepository userRepository;
    @Mock private BCryptPasswordEncoder passwordEncoder;
    @Mock private EmailService emailService;
    @Mock private MediaStorageService mediaStorageService;
    @Mock private MediaUrlResolver mediaUrlResolver;

    @InjectMocks private BrandPartnerService service;

    private BrandPartner brand(long id) {
        User user = User.builder().id(1L).email("brand@it.local").role(Role.BRAND_PARTNER).build();
        return BrandPartner.builder().id(id).user(user).brandName("Acme").slug("acme").build();
    }

    @Test
    void updateMyProfile_logoStorageKey_confirmsBeforeSetting() {
        BrandPartner brand = brand(7L);
        User user = brand.getUser();
        when(brandPartnerRepository.findByUser(user)).thenReturn(Optional.of(brand));
        when(brandPartnerRepository.save(any(BrandPartner.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateBrandPartnerDto dto = new UpdateBrandPartnerDto();
        dto.setLogoStorageKey("brands/7/logo/abc.png");

        service.updateMyProfile(dto, user);

        verify(mediaStorageService).verifyUploaded("brands/7/logo/abc.png", MediaPurpose.BRAND_LOGO, 7L);
        assertThat(brand.getLogoStorageKey()).isEqualTo("brands/7/logo/abc.png");
    }

    @Test
    void updateMyProfile_heroStorageKey_confirmsBeforeSetting() {
        BrandPartner brand = brand(7L);
        User user = brand.getUser();
        when(brandPartnerRepository.findByUser(user)).thenReturn(Optional.of(brand));
        when(brandPartnerRepository.save(any(BrandPartner.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateBrandPartnerDto dto = new UpdateBrandPartnerDto();
        dto.setHeroStorageKey("brands/7/hero/def.jpg");

        service.updateMyProfile(dto, user);

        verify(mediaStorageService).verifyUploaded("brands/7/hero/def.jpg", MediaPurpose.BRAND_HERO, 7L);
        assertThat(brand.getHeroStorageKey()).isEqualTo("brands/7/hero/def.jpg");
    }

    @Test
    void presignMediaUpload_wrongScopePurpose_throwsIllegalArgument() {
        BrandPartner brand = brand(7L);
        User user = brand.getUser();
        when(brandPartnerRepository.findByUser(user)).thenReturn(Optional.of(brand));

        PresignUploadRequestDto dto = new PresignUploadRequestDto();
        dto.setPurpose(MediaPurpose.PRODUCT_IMAGE);
        dto.setContentType("image/jpeg");
        dto.setContentLength(1024);

        assertThatThrownBy(() -> service.presignMediaUpload(dto, user))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mediaStorageService);
    }

    @Test
    void presignMediaUpload_brandLogo_delegatesWithBrandIdAsResourceId() {
        BrandPartner brand = brand(7L);
        User user = brand.getUser();
        when(brandPartnerRepository.findByUser(user)).thenReturn(Optional.of(brand));
        when(mediaStorageService.presignUpload(MediaPurpose.BRAND_LOGO, 7L, "image/png", 2048L))
                .thenReturn(new MediaStorageService.PresignedUpload(
                        "brands/7/logo/x.png", "https://signed.example/x", java.time.Instant.now(),
                        Map.of("Content-Type", "image/png")));

        PresignUploadRequestDto dto = new PresignUploadRequestDto();
        dto.setPurpose(MediaPurpose.BRAND_LOGO);
        dto.setContentType("image/png");
        dto.setContentLength(2048);

        assertThat(service.presignMediaUpload(dto, user).getKey()).isEqualTo("brands/7/logo/x.png");
    }

    @Test
    void updateMyProfile_logoStorageKey_verifyUploadedFails_neverSetsField() {
        BrandPartner brand = brand(7L);
        User user = brand.getUser();
        when(brandPartnerRepository.findByUser(user)).thenReturn(Optional.of(brand));
        doThrow(new IllegalArgumentException("bad key"))
                .when(mediaStorageService).verifyUploaded(eq("brands/7/logo/abc.png"), eq(MediaPurpose.BRAND_LOGO), eq(7L));

        UpdateBrandPartnerDto dto = new UpdateBrandPartnerDto();
        dto.setLogoStorageKey("brands/7/logo/abc.png");

        assertThatThrownBy(() -> service.updateMyProfile(dto, user))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(brand.getLogoStorageKey()).isNull();
        verify(brandPartnerRepository, never()).save(any());
    }
}
