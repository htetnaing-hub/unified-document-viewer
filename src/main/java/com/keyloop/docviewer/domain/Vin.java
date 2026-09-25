package com.keyloop.docviewer.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Vehicle Identification Number (ISO 3779): 17 characters, digits and capital letters
 * except I, O and Q. The check digit is deliberately not validated because it is only
 * mandatory for North American VINs.
 */
public record Vin(String value) {

    private static final Pattern FORMAT = Pattern.compile("[A-HJ-NPR-Z0-9]{17}");

    public Vin {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new InvalidVinException(value);
        }
    }

    /** Normalizes user input (surrounding whitespace, lower case) before validating. */
    public static Vin of(String raw) {
        if (raw == null) {
            throw new InvalidVinException(null);
        }
        return new Vin(raw.strip().toUpperCase(Locale.ROOT));
    }

    /**
     * VINs can identify a person's vehicle, so logs only carry the manufacturer prefix
     * and the last four characters.
     */
    public String masked() {
        return value.substring(0, 3) + "**********" + value.substring(13);
    }

    @Override
    public String toString() {
        return masked();
    }
}
