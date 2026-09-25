package com.keyloop.docviewer.domain;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * What one source contributed to a search.
 *
 * @param documents current documents when {@code outcome} is OK; otherwise the last known
 *                  documents from the snapshot store (possibly none)
 * @param stale     true when {@code documents} come from the snapshot because the live call failed
 */
public record SourceResult(
        SourceSystem source,
        SourceOutcome outcome,
        List<Document> documents,
        Duration latency,
        boolean stale) {

    public SourceResult {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(latency, "latency");
        documents = List.copyOf(documents);
        if (stale && outcome == SourceOutcome.OK) {
            throw new IllegalArgumentException("A successful result cannot be stale");
        }
    }

    public static SourceResult ok(SourceSystem source, List<Document> documents, Duration latency) {
        return new SourceResult(source, SourceOutcome.OK, documents, latency, false);
    }

    public static SourceResult failed(SourceSystem source, SourceOutcome outcome, List<Document> lastKnown, Duration latency) {
        return new SourceResult(source, outcome, lastKnown, latency, !lastKnown.isEmpty());
    }

    public boolean succeeded() {
        return outcome == SourceOutcome.OK;
    }
}
