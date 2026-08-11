package com.enunas.backend.shipping;

import com.enunas.backend.brandpartner.BrandPartner;
import com.enunas.backend.brandpartner.brandshippingprofile.BrandShippingProfile;
import com.enunas.backend.brandpartner.brandshippingprofile.BrandShippingProfileRepository;
import com.enunas.backend.order.OrderItem;
import com.enunas.backend.order.ShippingAddress;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * V1 shipping calculation: one flat rate per brand, resolved from {@link BrandShippingProfile}
 * with a platform-wide fallback. {@code destination}/{@code brandItems} are accepted but ignored
 * — see {@link ShippingCostService}'s javadoc for why the signature carries them anyway.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FlatRateShippingCostService implements ShippingCostService {

    static final String RULE_VERSION = "flat-v1";
    private static final String CURRENCY = "EUR";

    private final BrandShippingProfileRepository brandShippingProfileRepository;

    @Value("${enunas.shipping.default-rate}")
    private BigDecimal defaultRate;

    /**
     * Fail-fast environment guard: a misconfigured (negative) platform default rate must stop the
     * app from starting, not silently flow into every brand's {@code GLOBAL_DEFAULT} shipping
     * charge. Runs once at bean initialization.
     */
    @PostConstruct
    void validateDefaultRate() {
        if (defaultRate == null || defaultRate.signum() < 0) {
            throw new IllegalStateException(
                    "enunas.shipping.default-rate must be >= 0, was: " + defaultRate);
        }
    }

    @Override
    public ShippingCostResult calculate(BrandPartner brand, ShippingAddress destination, List<OrderItem> brandItems) {
        Optional<BrandShippingProfile> profile = brand != null
                ? brandShippingProfileRepository.findByBrandPartner_Id(brand.getId())
                : Optional.empty();

        if (profile.isEmpty() || profile.get().getShippingCost() == null) {
            log.debug("Brand {} shipping resolved: {} ({})",
                    brand != null ? brand.getId() : null, defaultRate, ShippingCalculationMethod.GLOBAL_DEFAULT);
            return new ShippingCostResult(
                    defaultRate, CURRENCY, ShippingCalculationMethod.GLOBAL_DEFAULT, RULE_VERSION,
                    profile.map(BrandShippingProfile::getId).orElse(null));
        }

        BrandShippingProfile p = profile.get();
        BigDecimal rate = p.getShippingCost();
        // Defense in depth: the admin API rejects a negative shippingCost at the DTO validation
        // layer (@DecimalMin on SetShippingProfileDto), but this is the last line of defense against
        // one reaching a real order — e.g. a direct DB write, a future write path that skips
        // validation, or a data migration — since a negative amount here would reduce the customer's
        // total rather than add to it.
        if (rate.signum() < 0) {
            throw new IllegalArgumentException(
                    "Shipping cost cannot be negative for brand " + brand.getId() + ": " + rate);
        }
        ShippingCalculationMethod method = rate.compareTo(BigDecimal.ZERO) == 0
                ? ShippingCalculationMethod.BRAND_FREE_SHIPPING
                : ShippingCalculationMethod.BRAND_FLAT_RATE;
        String currency = p.getCurrency() != null ? p.getCurrency() : CURRENCY;
        log.debug("Brand {} shipping resolved: {} ({})", brand.getId(), rate, method);
        return new ShippingCostResult(rate, currency, method, RULE_VERSION, p.getId());
    }
}
