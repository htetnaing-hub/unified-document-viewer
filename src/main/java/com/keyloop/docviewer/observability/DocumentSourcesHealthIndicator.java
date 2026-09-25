package com.keyloop.docviewer.observability;

import com.keyloop.docviewer.domain.SourceOutcome;
import com.keyloop.docviewer.domain.SourceSystem;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

/**
 * Reports how each source behaved on its most recent call. It is passive (no extra traffic
 * to the sources) and reports DEGRADED rather than DOWN: a failing dependency must not make
 * the orchestrator restart this service, because the service keeps working with partial data.
 * It is therefore excluded from the liveness and readiness groups.
 */
public class DocumentSourcesHealthIndicator implements HealthIndicator {

    public static final Status DEGRADED = new Status("DEGRADED", "At least one source failed its last call");

    private final SourceTelemetry telemetry;

    public DocumentSourcesHealthIndicator(SourceTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    @Override
    public Health health() {
        Map<SourceSystem, SourceTelemetry.SourceStatus> statuses = telemetry.lastStatus();
        Map<String, Object> details = new LinkedHashMap<>();
        boolean degraded = false;
        for (SourceSystem source : SourceSystem.values()) {
            SourceTelemetry.SourceStatus status = statuses.get(source);
            if (status == null) {
                details.put(source.name(), Map.of("lastOutcome", "NOT_CALLED_YET"));
                continue;
            }
            degraded |= status.outcome() != SourceOutcome.OK;
            details.put(source.name(), Map.of(
                    "lastOutcome", status.outcome().name(),
                    "lastLatencyMs", status.latency().toMillis(),
                    "observedAt", status.observedAt().toString()));
        }
        return Health.status(degraded ? DEGRADED : Status.UP).withDetails(details).build();
    }
}
