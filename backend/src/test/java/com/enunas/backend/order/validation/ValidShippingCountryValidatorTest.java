package com.enunas.backend.order.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ValidShippingCountryValidatorTest {

    private final ValidShippingCountryValidator validator = new ValidShippingCountryValidator();

    @Test
    void nullValue_isValid() {
        assertThat(validator.isValid(null, null)).isTrue();
    }

    @Test
    void de_isValid() {
        assertThat(validator.isValid("DE", null)).isTrue();
    }

    @Test
    void fr_isInvalid() {
        assertThat(validator.isValid("FR", null)).isFalse();
    }

    @Test
    void lowercase_de_isInvalid() {
        // Case-sensitive on purpose: the rest of this codebase's country fields are always
        // uppercase ISO 3166-1 alpha-2 (see RegisterBrandPartnerDto.addressCountry).
        assertThat(validator.isValid("de", null)).isFalse();
    }

    @Test
    void allowedShippingCountries_isAllowed_matchesValidator() {
        assertThat(AllowedShippingCountries.isAllowed("DE")).isTrue();
        assertThat(AllowedShippingCountries.isAllowed("AT")).isFalse();
        assertThat(AllowedShippingCountries.isAllowed(null)).isFalse();
    }
}
