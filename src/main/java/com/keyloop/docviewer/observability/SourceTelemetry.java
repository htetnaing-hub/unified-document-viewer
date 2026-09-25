package com.keyloop.docviewer.observability;

import com.keyloop.docviewer.domain.SourceOutcome;
import com.keyloop.docviewer.domain.SourceSystem;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Metrics and last-seen status for each source system.
 *
 * <ul>
 *   <li>{@code docviewer.source.requests} timer, tagged {@code source} and {@code outcome}:
 *       latency percentiles and error/timeout rates per source.</li>
 *   <li>{@code docviewer.source.fallbacks} counter: how often stale snapshot data was served.</li>
 * </ul>
 * The last-seen status feeds the {@code documentSources} health indicator. It is per instance
 * and only informational, so it does not affect horizontal scaling.
 */
public class SourceTelemetry {

    private final MeterRegistry registry;
    private final Clock clock;
    private final Map<SourceSystem, SourceStatus> lastStatus = new ConcurrentHashMap<>();

    public SourceTelemetry(MeterRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
    }

    public void recordFetch(SourceSystem source, SourceOutcome outcome, Duration latency) {
        Timer.builder("docviewer.source.requests")
                .description("Calls to external document sources")
                .tag("source", tagValue(source))
                .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                .publishPercentileHistogram()
                .register(registry)
                .record(latency);
        lastStatus.put(source, new SourceStatus(outcome, latency, clock.instant()));
    }

    public void recordFallback(SourceSystem source) {
        Counter.builder("docviewer.source.fallbacks")
                .description("Searches that served last known documents because the source failed")
                .tag("source", tagValue(source))
                .register(registry)
                .increment();
    }

    public Map<SourceSystem, SourceStatus> lastStatus() {
        return lastStatus.isEmpty() ? Map.of() : new EnumMap<>(lastStatus);
    }

    private static String tagValue(SourceSystem source) {
        return source.name().toLowerCase(Locale.ROOT);
    }

    public record SourceStatus(SourceOutcome outcome, Duration latency, Instant observedAt) {
    }
}
