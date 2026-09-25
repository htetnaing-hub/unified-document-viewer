package com.keyloop.docviewer.domain;

public class InvalidVinException extends RuntimeException {

    public InvalidVinException(String rejected) {
        super(rejected == null
                ? "VIN is required"
                : "VIN must be 17 characters (letters and digits, excluding I, O and Q)");
    }
}
