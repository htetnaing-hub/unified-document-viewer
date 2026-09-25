package com.keyloop.docviewer.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** The consolidated result of searching every source system for one VIN. */
public record AggregatedDocuments(Vin vin, Instant retrievedAt, List<SourceResult> sources) {

    /** Newest first; undated documents last; ties broken deterministically. */
    static final Comparator<Document> NEWEST_FIRST = Comparator
            .comparing(Document::issuedAt, Comparator.nullsLast(Comparator.<Instant>reverseOrder()))
            .thenComparing(Document::source)
            .thenComparing(Document::externalId);

    public AggregatedDocuments {
        Objects.requireNonNull(vin, "vin");
        Objects.requireNonNull(retrievedAt, "retrievedAt");
        sources = List.copyOf(sources);
    }

    /** One list across all sources, each document still tagged with its source. */
    public List<Document> documents() {
        return sources.stream()
                .flatMap(result -> result.documents().stream())
                .sorted(NEWEST_FIRST)
                .toList();
    }

    /** True when at least one source did not answer, so the list may be incomplete or stale. */
    public boolean partial() {
        return sources.stream().anyMatch(result -> !result.succeeded());
    }

    public Optional<SourceResult> source(SourceSystem system) {
        return sources.stream().filter(result -> result.source() == system).findFirst();
    }
}
