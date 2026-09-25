package com.keyloop.docviewer.domain;

import java.util.List;

/**
 * No source answered and there is no stored copy to fall back to. Distinguishes
 * "we could not check" from "the vehicle has no documents".
 */
public class AllSourcesUnavailableException extends RuntimeException {

    private final List<SourceResult> results;

    public AllSourcesUnavailableException(Vin vin, List<SourceResult> results) {
        super("No document source is available for VIN " + vin.masked());
        this.results = List.copyOf(results);
    }

    public List<SourceResult> results() {
        return results;
    }
}
