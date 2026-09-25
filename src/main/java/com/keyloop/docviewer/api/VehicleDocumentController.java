package com.keyloop.docviewer.api;

import com.keyloop.docviewer.aggregation.DocumentAggregator;
import com.keyloop.docviewer.domain.Vin;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/vehicles")
@Tag(name = "Vehicle documents")
class VehicleDocumentController {

    private final DocumentAggregator aggregator;

    VehicleDocumentController(DocumentAggregator aggregator) {
        this.aggregator = aggregator;
    }

    @GetMapping("/{vin}/documents")
    @Operation(
            summary = "Find all documents for a vehicle",
            description = "Queries the Sales and Service systems in parallel and returns one consolidated list, "
                    + "newest first, with each document's source system. If a system is slow or down, the other "
                    + "system's documents are still returned with partial=true; the failed system's last known "
                    + "documents are included and flagged stale when available.")
    @ApiResponse(responseCode = "200", description = "Documents found (the list may be empty)")
    @ApiResponse(responseCode = "400", description = "Malformed VIN",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "No source system answered and no stored copy exists",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    DocumentSearchResponse findDocuments(
            @Parameter(description = "17-character VIN; case-insensitive", example = "1HGCM82633A004352")
            @PathVariable String vin) {
        return DocumentSearchResponse.from(aggregator.aggregate(Vin.of(vin)));
    }
}
