package com.keyloop.docviewer.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AggregatedDocumentsTest {

    private static final Vin VIN = Vin.of("1HGCM82633A004352");

    @Test
    void mergesAllSourcesNewestFirstWithUndatedDocumentsLast() {
        Document oldSale = document("S-1", SourceSystem.SALES, "2023-03-12T00:00:00Z");
        Document undatedSale = document("S-2", SourceSystem.SALES, null);
        Document newService = document("A-1", SourceSystem.SERVICE, "2025-01-08T10:15:00Z");
        Document midService = document("A-2", SourceSystem.SERVICE, "2024-06-01T00:00:00Z");

        AggregatedDocuments result = new AggregatedDocuments(VIN, Instant.now(), List.of(
                SourceResult.ok(SourceSystem.SALES, List.of(oldSale, undatedSale), Duration.ZERO),
                SourceResult.ok(SourceSystem.SERVICE, List.of(midService, newService), Duration.ZERO)));

        assertThat(result.documents()).containsExactly(newService, midService, oldSale, undatedSale);
    }

    @Test
    void breaksTiesDeterministicallyBySourceThenId() {
        String sameTime = "2024-01-01T00:00:00Z";
        Document serviceB = document("B", SourceSystem.SERVICE, sameTime);
        Document salesB = document("B", SourceSystem.SALES, sameTime);
        Document salesA = document("A", SourceSystem.SALES, sameTime);

        AggregatedDocuments result = new AggregatedDocuments(VIN, Instant.now(), List.of(
                SourceResult.ok(SourceSystem.SERVICE, List.of(serviceB), Duration.ZERO),
                SourceResult.ok(SourceSystem.SALES, List.of(salesB, salesA), Duration.ZERO)));

        assertThat(result.documents()).containsExactly(salesA, salesB, serviceB);
    }

    @Test
    void isPartialWhenAnySourceFailed() {
        AggregatedDocuments allOk = new AggregatedDocuments(VIN, Instant.now(), List.of(
                SourceResult.ok(SourceSystem.SALES, List.of(), Duration.ZERO),
                SourceResult.ok(SourceSystem.SERVICE, List.of(), Duration.ZERO)));
        AggregatedDocuments oneTimedOut = new AggregatedDocuments(VIN, Instant.now(), List.of(
                SourceResult.ok(SourceSystem.SALES, List.of(), Duration.ZERO),
                SourceResult.failed(SourceSystem.SERVICE, SourceOutcome.TIMEOUT, List.of(), Duration.ofSeconds(2))));

        assertThat(allOk.partial()).isFalse();
        assertThat(oneTimedOut.partial()).isTrue();
    }

    @Test
    void failedSourceIsStaleOnlyWhenItHasLastKnownDocuments() {
        Document lastKnown = document("A-1", SourceSystem.SERVICE, null);

        assertThat(SourceResult.failed(SourceSystem.SERVICE, SourceOutcome.ERROR, List.of(lastKnown), Duration.ZERO).stale()).isTrue();
        assertThat(SourceResult.failed(SourceSystem.SERVICE, SourceOutcome.ERROR, List.of(), Duration.ZERO).stale()).isFalse();
    }

    @Test
    void documentIdIsUniqueAcrossSources() {
        assertThat(document("123", SourceSystem.SALES, null).id()).isEqualTo("SALES:123");
        assertThat(document("123", SourceSystem.SERVICE, null).id()).isEqualTo("SERVICE:123");
    }

    private static Document document(String id, SourceSystem source, String issuedAt) {
        return new Document(id, source, "TYPE", "Title " + id, "REF", issuedAt == null ? null : Instant.parse(issuedAt), null);
    }
}
