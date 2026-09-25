package com.keyloop.docviewer.source.sales;

import com.keyloop.docviewer.domain.Document;
import com.keyloop.docviewer.domain.SourceSystem;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** Translates the Sales System's deal-centric model into domain {@link Document}s. */
final class SalesDocumentMapper {

    private SalesDocumentMapper() {
    }

    static List<Document> toDocuments(SalesApiModel.DealsResponse response) {
        if (response == null || response.deals() == null) {
            return List.of();
        }
        return response.deals().stream()
                .filter(Objects::nonNull)
                .flatMap(SalesDocumentMapper::documentsOf)
                .toList();
    }

    private static Stream<Document> documentsOf(SalesApiModel.Deal deal) {
        if (deal.documents() == null) {
            return Stream.empty();
        }
        return deal.documents().stream()
                // A document without an id cannot be referenced or de-duplicated, so it is skipped.
                .filter(document -> document != null && document.docId() != null)
                .map(document -> new Document(
                        document.docId(),
                        SourceSystem.SALES,
                        document.docType(),
                        document.name(),
                        deal.dealId(),
                        // The Sales System only provides a date; assume start of day UTC.
                        document.createdOn() == null ? null : document.createdOn().atStartOfDay(ZoneOffset.UTC).toInstant(),
                        document.link()));
    }
}
