package com.keyloop.docviewer.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class VinTest {

    @Test
    void acceptsValidVin() {
        assertThat(Vin.of("1HGCM82633A004352").value()).isEqualTo("1HGCM82633A004352");
    }

    @Test
    void normalizesCaseAndSurroundingWhitespace() {
        assertThat(Vin.of("  1hgcm82633a004352 \t").value()).isEqualTo("1HGCM82633A004352");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "1HGCM82633A00435",     // 16 characters
            "1HGCM82633A0043521",   // 18 characters
            "1HGCM82633A00435I",    // I is not allowed
            "1HGCM82633A00435O",    // O is not allowed
            "1HGCM82633A00435Q",    // Q is not allowed
            "1HGCM82633A00435-",    // punctuation
            "1HGCM 2633A004352"     // inner whitespace
    })
    void rejectsMalformedVin(String input) {
        assertThatThrownBy(() -> Vin.of(input)).isInstanceOf(InvalidVinException.class);
    }

    @Test
    void masksVinForLogging() {
        Vin vin = Vin.of("1HGCM82633A004352");

        assertThat(vin.masked()).isEqualTo("1HG**********4352").hasSize(17);
        assertThat(vin.toString()).isEqualTo(vin.masked());
    }
}
