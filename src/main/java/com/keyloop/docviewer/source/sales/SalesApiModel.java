package com.keyloop.docviewer.source.sales;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDate;
import java.util.List;

/**
 * Wire format of the Sales System API ({@code GET /api/v1/vehicles/{vin}/deals}).
 * Owned by the Sales System; never used outside this package. Unknown fields are ignored
 * so the source can add fields without breaking us (tolerant reader).
 */
final class SalesApiModel {

    private SalesApiModel() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DealsResponse(String vin, List<Deal> deals) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Deal(String dealId, List<SalesDocument> documents) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SalesDocument(String docId, String docType, String name, LocalDate createdOn, String link) {
    }
}
