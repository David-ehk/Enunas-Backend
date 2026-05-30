package com.enunas.backend.discount;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.BrandPartnerService;
import com.enunas.backend.discount.dto.CreateDiscountDto;
import com.enunas.backend.order.OrderItem;
import com.enunas.backend.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiscountServiceTest {

    @Mock private DiscountCodeRepository discountCodeRepository;
    @Mock private BrandPartnerService brandPartnerService;

    @InjectMocks private DiscountService discountService;

    private DiscountCode adminCode;
    private DiscountCode brandCode;
    private BrandPartner brandFive;

    @BeforeEach
    void setUp() {
        brandFive = BrandPartner.builder().build();
        brandFive.setId(5L);

        adminCode = DiscountCode.builder()
                .id(1L).code("WELCOME10").type(DiscountType.ADMIN)
                .percent(new BigDecimal("0.1000")).active(true).usedCount(0)
                .build();

        brandCode = DiscountCode.builder()
                .id(2L).code("SUMMER15").type(DiscountType.BRAND).brand(brandFive)
                .percent(new BigDecimal("0.1500")).active(true).usedCount(0)
                .build();
    }

    private OrderItem mockItem(long brandId, String lineTotal, String rate) {
        OrderItem item = org.mockito.Mockito.mock(OrderItem.class);
        lenient().when(item.getBrandId()).thenReturn(brandId);
        lenient().when(item.getLineTotal()).thenReturn(new BigDecimal(lineTotal));
        lenient().when(item.getCommissionRate()).thenReturn(new BigDecimal(rate));
        lenient().when(item.getProductSnapshotName()).thenReturn("Test Product");
        return item;
    }

    @Test
    void adminDiscount_absorbedEntirelyByPlatform_acrossAllItems() {
        when(discountCodeRepository.findByCodeIgnoreCase("WELCOME10")).thenReturn(Optional.of(adminCode));
        when(discountCodeRepository.reserveUsage(1L)).thenReturn(1);

        OrderItem item = mockItem(5L, "100.00", "0.18");

        DiscountApplication app = discountService.validateAndApply("WELCOME10", List.of(item));

        assertThat(app.discountAmount()).isEqualByComparingTo("10.00");
        assertThat(app.platformDiscountAmount()).isEqualByComparingTo("10.00");
        assertThat(app.brandDiscountAmount()).isEqualByComparingTo("0.00");
        assertThat(app.itemShares().get(0).platformShare()).isEqualByComparingTo("10.00");
        assertThat(app.itemShares().get(0).brandShare()).isEqualByComparingTo("0.00");
    }

    @Test
    void brandDiscount_splitFiftyFifty_onlyOnIssuingBrandItems() {
        when(discountCodeRepository.findByCodeIgnoreCase("SUMMER15")).thenReturn(Optional.of(brandCode));
        when(discountCodeRepository.reserveUsage(2L)).thenReturn(1);

        OrderItem own   = mockItem(5L, "100.00", "0.18"); // brand 5 — discounted
        OrderItem other = mockItem(9L, "50.00", "0.18");  // brand 9 — untouched

        DiscountApplication app = discountService.validateAndApply("SUMMER15", List.of(own, other));

        assertThat(app.discountAmount()).isEqualByComparingTo("15.00");
        assertThat(app.platformDiscountAmount()).isEqualByComparingTo("7.50");
        assertThat(app.brandDiscountAmount()).isEqualByComparingTo("7.50");
        // Own item split 7.50 / 7.50; other brand's item untouched.
        assertThat(app.itemShares().get(0).platformShare()).isEqualByComparingTo("7.50");
        assertThat(app.itemShares().get(0).brandShare()).isEqualByComparingTo("7.50");
        assertThat(app.itemShares().get(1).total()).isEqualByComparingTo("0.00");
    }

    @Test
    void brandDiscount_rejectedWhenCartHasNoneOfTheBrandsProducts() {
        when(discountCodeRepository.findByCodeIgnoreCase("SUMMER15")).thenReturn(Optional.of(brandCode));

        OrderItem other = mockItem(9L, "50.00", "0.18");

        assertThatThrownBy(() -> discountService.validateAndApply("SUMMER15", List.of(other)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not apply");
    }

    @Test
    void expiredCode_isRejected() {
        adminCode.setValidUntil(LocalDateTime.now().minusDays(1));
        when(discountCodeRepository.findByCodeIgnoreCase("WELCOME10")).thenReturn(Optional.of(adminCode));

        assertThatThrownBy(() -> discountService.validateAndApply("WELCOME10",
                List.of(mockItem(5L, "100.00", "0.18"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void exhaustedSingleUseCode_isRejected() {
        adminCode.setMaxUses(1);
        adminCode.setUsedCount(1);
        when(discountCodeRepository.findByCodeIgnoreCase("WELCOME10")).thenReturn(Optional.of(adminCode));

        assertThatThrownBy(() -> discountService.validateAndApply("WELCOME10",
                List.of(mockItem(5L, "100.00", "0.18"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("usage limit");
    }

    @Test
    void inactiveCode_isRejected() {
        adminCode.setActive(false);
        when(discountCodeRepository.findByCodeIgnoreCase("WELCOME10")).thenReturn(Optional.of(adminCode));

        assertThatThrownBy(() -> discountService.validateAndApply("WELCOME10",
                List.of(mockItem(5L, "100.00", "0.18"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not active");
    }

    @Test
    void brandCreate_rejectsPercentAboveFifteenCap() {
        User brandUser = new User();
        CreateDiscountDto dto = new CreateDiscountDto();
        dto.setCode("TOOBIG");
        dto.setPercent(new BigDecimal("0.20")); // 20% > 15% brand cap

        when(brandPartnerService.findByUser(brandUser)).thenReturn(brandFive);
        when(discountCodeRepository.existsByCodeIgnoreCase("TOOBIG")).thenReturn(false);

        assertThatThrownBy(() -> discountService.createBrandDiscount(dto, brandUser))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("may not exceed");
    }
}
