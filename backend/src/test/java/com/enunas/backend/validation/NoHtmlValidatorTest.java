package com.enunas.backend.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NoHtmlValidatorTest {

    private final NoHtmlValidator validator = new NoHtmlValidator();

    @Test
    void nullValue_isValid() {
        assertThat(validator.isValid(null, null)).isTrue();
    }

    @Test
    void emptyValue_isValid() {
        assertThat(validator.isValid("", null)).isTrue();
    }

    @Test
    void plainText_isValid() {
        assertThat(validator.isValid("Black Hoodie, size M", null)).isTrue();
    }

    @Test
    void scriptTag_isInvalid() {
        assertThat(validator.isValid("<script>alert(1)</script>", null)).isFalse();
    }

    @Test
    void loneAngleBracket_isInvalid() {
        assertThat(validator.isValid("5 > 3 and 2 < 4", null)).isFalse();
    }

    @Test
    void imgOnErrorPayload_isInvalid() {
        assertThat(validator.isValid("<img src=x onerror=alert(1)>", null)).isFalse();
    }
}
