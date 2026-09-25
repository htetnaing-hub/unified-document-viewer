package com.keyloop.docviewer.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * A document about a vehicle, normalized from whichever source system holds it.
 *
 * @param externalId identifier of the document inside its source system
 * @param source     system the document came from
 * @param type       source-specific document type, e.g. SALES_INVOICE or REPAIR_ORDER
 * @param title      human-readable title
 * @param reference  business record the document belongs to, e.g. a deal or repair order number
 * @param issuedAt   when the document was issued; may be null when the source omits it
 * @param url        where the document can be downloaded from the source system
 */
public record Document(
        String externalId,
        SourceSystem source,
        String type,
        String title,
        String reference,
        Instant issuedAt,
        String url) {

    public Document {
        Objects.requireNonNull(externalId, "externalId");
        Objects.requireNonNull(source, "source");
    }

    /** Globally unique id: external ids are only unique within one source system. */
    public String id() {
        return source.name() + ":" + externalId;
    }
}
