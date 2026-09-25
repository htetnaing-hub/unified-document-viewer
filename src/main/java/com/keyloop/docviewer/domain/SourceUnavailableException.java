package com.keyloop.docviewer.domain;

import java.util.Objects;

/** A source system could not provide documents. Never exposed to API clients directly. */
public class SourceUnavailableException extends RuntimeException {

    private final SourceSystem source;
    private final SourceOutcome outcome;

    public SourceUnavailableException(SourceSystem source, SourceOutcome outcome, String message, Throwable cause) {
        super(message, cause);
        this.source = Objects.requireNonNull(source, "source");
        if (outcome == SourceOutcome.OK) {
            throw new IllegalArgumentException("An unavailable source cannot have outcome OK");
        }
        this.outcome = Objects.requireNonNull(outcome, "outcome");
    }

    public SourceUnavailableException(SourceSystem source, SourceOutcome outcome, String message) {
        this(source, outcome, message, null);
    }

    public SourceSystem source() {
        return source;
    }

    public SourceOutcome outcome() {
        return outcome;
    }
}
