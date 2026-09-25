package com.keyloop.docviewer.aggregation;

import com.keyloop.docviewer.domain.AggregatedDocuments;
import com.keyloop.docviewer.domain.AllSourcesUnavailableException;
import com.keyloop.docviewer.domain.Document;
import com.keyloop.docviewer.domain.DocumentSnapshotStore;
import com.keyloop.docviewer.domain.DocumentSource;
import com.keyloop.docviewer.domain.SearchAuditRecorder;
import com.keyloop.docviewer.domain.SourceOutcome;
import com.keyloop.docviewer.domain.SourceResult;
import com.keyloop.docviewer.domain.SourceUnavailableException;
import com.keyloop.docviewer.domain.Vin;
import com.keyloop.docviewer.observability.SourceTelemetry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Searches every {@link DocumentSource} for a VIN in parallel and merges the results.
 *
 * <p>Each source runs on its own (virtual) thread with its own deadline, so a search takes
 * as long as the slowest source, capped at that source's timeout. A source that fails or
 * times out never fails the search: its last known documents are served from the snapshot
 * store and flagged as stale. Only when no source answers and nothing is stored does the
 * search fail with {@link AllSourcesUnavailableException}.
 */
public class DocumentAggregator {

    private static final Logger log = LoggerFactory.getLogger(DocumentAggregator.class);

    private final List<DocumentSource> sources;
    private final Executor executor;
    private final DocumentSnapshotStore snapshots;
    private final SearchAuditRecorder audit;
    private final SourceTelemetry telemetry;
    private final Clock clock;

    public DocumentAggregator(
            List<DocumentSource> sources,
            Executor executor,
            DocumentSnapshotStore snapshots,
            SearchAuditRecorder audit,
            SourceTelemetry telemetry,
            Clock clock) {
        this.sources = sources.stream().sorted(Comparator.comparing(DocumentSource::system)).toList();
        this.executor = executor;
        this.snapshots = snapshots;
        this.audit = audit;
        this.telemetry = telemetry;
        this.clock = clock;
    }

    public AggregatedDocuments aggregate(Vin vin) {
        Instant requestedAt = clock.instant();
        long started = System.nanoTime();

        // Start every call before waiting on any of them.
        List<CompletableFuture<Attempt>> attempts = sources.stream()
                .map(source -> attempt(source, vin))
                .toList();

        // join() cannot block indefinitely: every attempt is bounded by orTimeout and never
        // completes exceptionally (failures are turned into Attempt values).
        List<SourceResult> results = attempts.stream()
                .map(CompletableFuture::join)
                .map(attempt -> resolve(vin, attempt, requestedAt))
                .toList();

        Duration duration = Duration.ofNanos(System.nanoTime() - started);
        recordAudit(vin, results, requestedAt, duration);

        if (results.stream().noneMatch(result -> result.succeeded() || !result.documents().isEmpty())) {
            throw new AllSourcesUnavailableException(vin, results);
        }
        return new AggregatedDocuments(vin, requestedAt, results);
    }

    private CompletableFuture<Attempt> attempt(DocumentSource source, Vin vin) {
        long started = System.nanoTime();
        CompletableFuture<Attempt> call;
        try {
            call = CompletableFuture.supplyAsync(
                    () -> Attempt.succeeded(source, source.fetchDocuments(vin), since(started)), executor);
        } catch (RuntimeException rejected) {
            // e.g. the executor refused the task while shutting down
            call = CompletableFuture.failedFuture(rejected);
        }
        // orTimeout stops waiting but cannot cancel the HTTP call; the adapter's read timeout
        // (same value) makes sure the underlying thread is released as well.
        return call
                .orTimeout(source.timeout().toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(failure -> Attempt.failed(source, failure, since(started)));
    }

    private SourceResult resolve(Vin vin, Attempt attempt, Instant requestedAt) {
        DocumentSource source = attempt.source();
        telemetry.recordFetch(source.system(), attempt.outcome(), attempt.latency());

        if (attempt.outcome() == SourceOutcome.OK) {
            refreshSnapshot(vin, attempt, requestedAt);
            return SourceResult.ok(source.system(), attempt.documents(), attempt.latency());
        }

        log.warn("{} {} for VIN {} after {} ms: {}",
                source.system().displayName(), attempt.outcome(), vin.masked(),
                attempt.latency().toMillis(), attempt.failureMessage());
        List<Document> lastKnown = lastKnown(vin, source);
        if (!lastKnown.isEmpty()) {
            telemetry.recordFallback(source.system());
        }
        return SourceResult.failed(source.system(), attempt.outcome(), lastKnown, attempt.latency());
    }

    // Persistence problems degrade the fallback, never the search itself.
    private void refreshSnapshot(Vin vin, Attempt attempt, Instant fetchedAt) {
        try {
            snapshots.replace(vin, attempt.source().system(), attempt.documents(), fetchedAt);
        } catch (RuntimeException e) {
            log.warn("Could not store {} snapshot for VIN {}", attempt.source().system(), vin.masked(), e);
        }
    }

    private List<Document> lastKnown(Vin vin, DocumentSource source) {
        try {
            return snapshots.findLastKnown(vin, source.system());
        } catch (RuntimeException e) {
            log.warn("Could not read {} snapshot for VIN {}", source.system(), vin.masked(), e);
            return List.of();
        }
    }

    private void recordAudit(Vin vin, List<SourceResult> results, Instant requestedAt, Duration duration) {
        try {
            audit.record(vin, results, requestedAt, duration);
        } catch (RuntimeException e) {
            log.warn("Could not record search audit for VIN {}", vin.masked(), e);
        }
    }

    private static Duration since(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos);
    }

    private record Attempt(
            DocumentSource source,
            SourceOutcome outcome,
            List<Document> documents,
            Duration latency,
            String failureMessage) {

        static Attempt succeeded(DocumentSource source, List<Document> documents, Duration latency) {
            return new Attempt(source, SourceOutcome.OK, List.copyOf(documents), latency, null);
        }

        static Attempt failed(DocumentSource source, Throwable failure, Duration latency) {
            Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause()
                    : failure;
            SourceOutcome outcome = switch (cause) {
                case TimeoutException timeout -> SourceOutcome.TIMEOUT;
                case SourceUnavailableException unavailable -> unavailable.outcome();
                default -> SourceOutcome.ERROR;
            };
            String message = cause instanceof TimeoutException
                    ? "no response within " + source.timeout().toMillis() + " ms"
                    : cause.getClass().getSimpleName() + ": " + cause.getMessage();
            return new Attempt(source, outcome, List.of(), latency, message);
        }
    }
}
