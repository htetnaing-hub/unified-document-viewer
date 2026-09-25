package com.keyloop.docviewer.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "search_audit")
public class SearchAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 17)
    private String vin;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "duration_ms", nullable = false)
    private long durationMs;

    @Column(nullable = false)
    private boolean partial;

    @Column(name = "document_count", nullable = false)
    private int documentCount;

    /** e.g. {@code SALES=OK,SERVICE=TIMEOUT}; a string so a new source needs no schema change. */
    @Column(name = "source_outcomes", nullable = false)
    private String sourceOutcomes;

    protected SearchAuditEntity() {
    }

    SearchAuditEntity(String vin, String correlationId, Instant requestedAt, long durationMs,
                      boolean partial, int documentCount, String sourceOutcomes) {
        this.vin = vin;
        this.correlationId = correlationId;
        this.requestedAt = requestedAt;
        this.durationMs = durationMs;
        this.partial = partial;
        this.documentCount = documentCount;
        this.sourceOutcomes = sourceOutcomes;
    }

    public String getVin() {
        return vin;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public boolean isPartial() {
        return partial;
    }

    public int getDocumentCount() {
        return documentCount;
    }

    public String getSourceOutcomes() {
        return sourceOutcomes;
    }
}
