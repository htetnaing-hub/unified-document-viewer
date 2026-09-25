package com.keyloop.docviewer.api;

import com.keyloop.docviewer.domain.AggregatedDocuments;
import com.keyloop.docviewer.domain.Document;
import com.keyloop.docviewer.domain.SourceResult;
import com.keyloop.docviewer.domain.SourceSystem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Schema(description = "All documents found for a VIN across the Sales and Service systems")
public record DocumentSearchResponse(
        @Schema(example = "1HGCM82633A004352") String vin,
        Instant retrievedAt,
        @Schema(description = "True when at least one source did not answer; documents may be incomplete or stale")
        boolean partial,
        @Schema(description = "How each source system behaved for this search") List<SourceStatusResponse> sources,
        @Schema(description = "Consolidated list, newest first") List<DocumentResponse> documents) {

    static DocumentSearchResponse from(AggregatedDocuments result) {
        Map<SourceSystem, SourceResult> bySource = result.sources().stream()
                .collect(Collectors.toMap(SourceResult::source, Function.identity()));
        return new DocumentSearchResponse(
                result.vin().value(),
                result.retrievedAt(),
                result.partial(),
                result.sources().stream().map(SourceStatusResponse::from).toList(),
                result.documents().stream()
                        .map(document -> DocumentResponse.from(document, bySource.get(document.source()).stale()))
                        .toList());
    }

    @Schema(name = "SourceStatus")
    public record SourceStatusResponse(
            @Schema(example = "SERVICE") SourceSystem system,
            @Schema(example = "Service System") String displayName,
            @Schema(example = "TIMEOUT", allowableValues = {"OK", "TIMEOUT", "ERROR"}) String status,
            @Schema(example = "3") int documentCount,
            @Schema(example = "2001") long latencyMs,
            @Schema(description = "True when the documents are the last known copy, not live data") boolean stale) {

        static SourceStatusResponse from(SourceResult result) {
            return new SourceStatusResponse(
                    result.source(),
                    result.source().displayName(),
                    result.outcome().name(),
                    result.documents().size(),
                    result.latency().toMillis(),
                    result.stale());
        }
    }

    @Schema(name = "Document")
    public record DocumentResponse(
            @Schema(example = "SERVICE:A-9001") String id,
            @Schema(description = "Source system that holds the document", example = "SERVICE") SourceSystem source,
            @Schema(example = "Service System") String sourceDisplayName,
            @Schema(example = "REPAIR_ORDER") String type,
            @Schema(example = "30,000 mile service") String title,
            @Schema(description = "Deal or repair order the document belongs to", example = "RO-5521") String reference,
            Instant issuedAt,
            String url,
            boolean stale) {

        static DocumentResponse from(Document document, boolean stale) {
            return new DocumentResponse(
                    document.id(),
                    document.source(),
                    document.source().displayName(),
                    document.type(),
                    document.title(),
                    document.reference(),
                    document.issuedAt(),
                    document.url(),
                    stale);
        }
    }
}
