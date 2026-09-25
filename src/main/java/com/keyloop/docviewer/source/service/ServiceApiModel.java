package com.keyloop.docviewer.source.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;

/**
 * Wire format of the Service System API ({@code GET /api/v1/vehicles/{vin}/repair-orders}).
 * Owned by the Service System; never used outside this package.
 */
final class ServiceApiModel {

    private ServiceApiModel() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RepairOrdersResponse(String vehicleVin, List<RepairOrder> repairOrders) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RepairOrder(String roNumber, List<Attachment> attachments) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Attachment(String attachmentId, String category, String description, Instant timestamp, String downloadUrl) {
    }
}
