package com.keyloop.docviewer.source.service;

import com.keyloop.docviewer.domain.Document;
import com.keyloop.docviewer.domain.SourceSystem;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** Translates the Service System's repair-order model into domain {@link Document}s. */
final class ServiceDocumentMapper {

    private ServiceDocumentMapper() {
    }

    static List<Document> toDocuments(ServiceApiModel.RepairOrdersResponse response) {
        if (response == null || response.repairOrders() == null) {
            return List.of();
        }
        return response.repairOrders().stream()
                .filter(Objects::nonNull)
                .flatMap(ServiceDocumentMapper::documentsOf)
                .toList();
    }

    private static Stream<Document> documentsOf(ServiceApiModel.RepairOrder repairOrder) {
        if (repairOrder.attachments() == null) {
            return Stream.empty();
        }
        return repairOrder.attachments().stream()
                .filter(attachment -> attachment != null && attachment.attachmentId() != null)
                .map(attachment -> new Document(
                        attachment.attachmentId(),
                        SourceSystem.SERVICE,
                        attachment.category(),
                        attachment.description(),
                        repairOrder.roNumber(),
                        attachment.timestamp(),
                        attachment.downloadUrl()));
    }
}
