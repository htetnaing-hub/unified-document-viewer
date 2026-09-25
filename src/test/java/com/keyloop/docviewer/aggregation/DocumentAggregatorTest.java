package com.keyloop.docviewer.aggregation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.keyloop.docviewer.domain.AggregatedDocuments;
import com.keyloop.docviewer.domain.AllSourcesUnavailableException;
import com.keyloop.docviewer.domain.Document;
import com.keyloop.docviewer.domain.DocumentSnapshotStore;
import com.keyloop.docviewer.domain.DocumentSource;
import com.keyloop.docviewer.domain.SearchAuditRecorder;
import com.keyloop.docviewer.domain.SourceOutcome;
import com.keyloop.docviewer.domain.SourceResult;
import com.keyloop.docviewer.domain.SourceSystem;
import com.keyloop.docviewer.domain.SourceUnavailableException;
import com.keyloop.docviewer.domain.Vin;
import com.keyloop.docviewer.observability.MdcPropagatingTaskDecorator;
import com.keyloop.docviewer.observability.SourceTelemetry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

class DocumentAggregatorTest {

    private static final Vin VIN = Vin.of("1HGCM82633A004352");
    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");

    private final SimpleAsyncTaskExecutor executor = executor();
    private final InMemorySnapshotStore snapshots = new InMemorySnapshotStore();
    private final RecordingAudit audit = new RecordingAudit();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final SourceTelemetry telemetry = new SourceTelemetry(meters, Clock.fixed(NOW, ZoneOffset.UTC));

    @AfterEach
    void tearDown() {
        executor.close();
        MDC.clear();
    }

    @Test
    void mergesDocumentsFromBothSourcesKeepingTheirSource() {
        Document sale = document("S-1", SourceSystem.SALES, "2023-03-12T00:00:00Z");
        Document repair = document("A-1", SourceSystem.SERVICE, "2025-01-08T10:15:00Z");

        AggregatedDocuments result = aggregator(
                FakeSource.answering(SourceSystem.SALES, sale),
                FakeSource.answering(SourceSystem.SERVICE, repair)).aggregate(VIN);

        assertThat(result.documents()).containsExactly(repair, sale);
        assertThat(result.documents()).extracting(Document::source).containsExactly(SourceSystem.SERVICE, SourceSystem.SALES);
        assertThat(result.partial()).isFalse();
        assertThat(result.sources()).extracting(SourceResult::outcome).containsOnly(SourceOutcome.OK);
    }

    @Test
    void callsSourcesInParallel() {
        // Sequential calls would take at least 1,200 ms.
        FakeSource sales = FakeSource.answering(SourceSystem.SALES).delayedBy(Duration.ofMillis(600));
        FakeSource service = FakeSource.answering(SourceSystem.SERVICE).delayedBy(Duration.ofMillis(600));

        long started = System.nanoTime();
        aggregator(sales, service).aggregate(VIN);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(elapsed).isLessThan(Duration.ofMillis(1_000));
    }

    @Test
    void slowSourceTimesOutWithoutDelayingTheOtherSourcesResult() {
        Document sale = document("S-1", SourceSystem.SALES, null);
        FakeSource slowService = FakeSource.answering(SourceSystem.SERVICE)
                .withTimeout(Duration.ofMillis(200))
                .delayedBy(Duration.ofSeconds(3));

        long started = System.nanoTime();
        AggregatedDocuments result = aggregator(FakeSource.answering(SourceSystem.SALES, sale), slowService).aggregate(VIN);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(elapsed).isLessThan(Duration.ofMillis(1_500));
        assertThat(result.partial()).isTrue();
        assertThat(result.documents()).containsExactly(sale);
        assertThat(result.source(SourceSystem.SERVICE)).hasValueSatisfying(service ->
                assertThat(service.outcome()).isEqualTo(SourceOutcome.TIMEOUT));
    }

    @Test
    void failingSourceIsReportedWithItsOutcome() {
        FakeSource brokenService = FakeSource.failingWith(SourceSystem.SERVICE, SourceOutcome.ERROR);

        AggregatedDocuments result = aggregator(FakeSource.answering(SourceSystem.SALES), brokenService).aggregate(VIN);

        assertThat(result.partial()).isTrue();
        assertThat(result.source(SourceSystem.SERVICE)).hasValueSatisfying(service -> {
            assertThat(service.outcome()).isEqualTo(SourceOutcome.ERROR);
            assertThat(service.stale()).isFalse();
        });
    }

    @Test
    void unexpectedExceptionFromAdapterIsTreatedAsError() {
        FakeSource buggy = FakeSource.answering(SourceSystem.SERVICE).throwing(new IllegalStateException("bug"));

        AggregatedDocuments result = aggregator(FakeSource.answering(SourceSystem.SALES), buggy).aggregate(VIN);

        assertThat(result.source(SourceSystem.SERVICE)).hasValueSatisfying(service ->
                assertThat(service.outcome()).isEqualTo(SourceOutcome.ERROR));
    }

    @Test
    void successfulCallRefreshesTheSnapshot() {
        Document repair = document("A-1", SourceSystem.SERVICE, null);

        aggregator(FakeSource.answering(SourceSystem.SALES), FakeSource.answering(SourceSystem.SERVICE, repair)).aggregate(VIN);

        assertThat(snapshots.findLastKnown(VIN, SourceSystem.SERVICE)).containsExactly(repair);
        assertThat(snapshots.findLastKnown(VIN, SourceSystem.SALES)).isEmpty();
    }

    @Test
    void failedSourceFallsBackToLastKnownDocumentsFlaggedStale() {
        Document lastKnownRepair = document("A-1", SourceSystem.SERVICE, null);
        snapshots.replace(VIN, SourceSystem.SERVICE, List.of(lastKnownRepair), NOW.minusSeconds(3_600));

        AggregatedDocuments result = aggregator(
                FakeSource.answering(SourceSystem.SALES),
                FakeSource.failingWith(SourceSystem.SERVICE, SourceOutcome.TIMEOUT)).aggregate(VIN);

        assertThat(result.documents()).containsExactly(lastKnownRepair);
        assertThat(result.source(SourceSystem.SERVICE)).hasValueSatisfying(service -> {
            assertThat(service.outcome()).isEqualTo(SourceOutcome.TIMEOUT);
            assertThat(service.stale()).isTrue();
        });
        assertThat(meters.counter("docviewer.source.fallbacks", "source", "service").count()).isEqualTo(1.0);
    }

    @Test
    void failsOnlyWhenNoSourceAnswersAndNothingIsStored() {
        DocumentAggregator aggregator = aggregator(
                FakeSource.failingWith(SourceSystem.SALES, SourceOutcome.ERROR),
                FakeSource.failingWith(SourceSystem.SERVICE, SourceOutcome.TIMEOUT));

        assertThatThrownBy(() -> aggregator.aggregate(VIN))
                .isInstanceOfSatisfying(AllSourcesUnavailableException.class, e ->
                        assertThat(e.results()).extracting(SourceResult::outcome)
                                .containsExactly(SourceOutcome.ERROR, SourceOutcome.TIMEOUT));
        assertThat(audit.searches).hasSize(1);
    }

    @Test
    void allSourcesDownButStoredCopyExistsStillAnswers() {
        Document lastKnownSale = document("S-1", SourceSystem.SALES, null);
        snapshots.replace(VIN, SourceSystem.SALES, List.of(lastKnownSale), NOW.minusSeconds(60));

        AggregatedDocuments result = aggregator(
                FakeSource.failingWith(SourceSystem.SALES, SourceOutcome.ERROR),
                FakeSource.failingWith(SourceSystem.SERVICE, SourceOutcome.ERROR)).aggregate(VIN);

        assertThat(result.documents()).containsExactly(lastKnownSale);
        assertThat(result.partial()).isTrue();
    }

    @Test
    void vehicleWithNoDocumentsIsAnEmptyResultNotAFailure() {
        AggregatedDocuments result = aggregator(
                FakeSource.answering(SourceSystem.SALES),
                FakeSource.answering(SourceSystem.SERVICE)).aggregate(VIN);

        assertThat(result.documents()).isEmpty();
        assertThat(result.partial()).isFalse();
    }

    @Test
    void snapshotStoreFailureDoesNotFailTheSearch() {
        snapshots.failAllOperations = true;
        Document sale = document("S-1", SourceSystem.SALES, null);

        AggregatedDocuments result = aggregator(
                FakeSource.answering(SourceSystem.SALES, sale),
                FakeSource.failingWith(SourceSystem.SERVICE, SourceOutcome.ERROR)).aggregate(VIN);

        assertThat(result.documents()).containsExactly(sale);
    }

    @Test
    void recordsAuditAndPerSourceMetrics() {
        aggregator(
                FakeSource.answering(SourceSystem.SALES),
                FakeSource.failingWith(SourceSystem.SERVICE, SourceOutcome.ERROR)).aggregate(VIN);

        assertThat(audit.searches).singleElement().satisfies(results ->
                assertThat(results).extracting(SourceResult::outcome).containsExactly(SourceOutcome.OK, SourceOutcome.ERROR));
        assertThat(meters.timer("docviewer.source.requests", "source", "sales", "outcome", "ok").count()).isEqualTo(1);
        assertThat(meters.timer("docviewer.source.requests", "source", "service", "outcome", "error").count()).isEqualTo(1);
        assertThat(telemetry.lastStatus().get(SourceSystem.SERVICE).outcome()).isEqualTo(SourceOutcome.ERROR);
    }

    @Test
    void correlationIdReachesTheThreadsCallingSources() {
        MDC.put("correlationId", "test-correlation-id");
        FakeSource sales = FakeSource.answering(SourceSystem.SALES);
        FakeSource service = FakeSource.answering(SourceSystem.SERVICE);

        aggregator(sales, service).aggregate(VIN);

        assertThat(sales.observedMdc).containsEntry("correlationId", "test-correlation-id");
        assertThat(service.observedMdc).containsEntry("correlationId", "test-correlation-id");
    }

    private DocumentAggregator aggregator(DocumentSource... sources) {
        return new DocumentAggregator(List.of(sources), executor, snapshots, audit, telemetry, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SimpleAsyncTaskExecutor executor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("test-source-call-");
        executor.setVirtualThreads(true);
        executor.setTaskDecorator(new MdcPropagatingTaskDecorator());
        return executor;
    }

    private static Document document(String id, SourceSystem source, String issuedAt) {
        return new Document(id, source, "TYPE", "Title " + id, "REF-" + id,
                issuedAt == null ? null : Instant.parse(issuedAt), "https://example.test/" + id);
    }

    /** Configurable stand-in for a source system adapter. */
    private static final class FakeSource implements DocumentSource {

        private final SourceSystem system;
        private final List<Document> documents;
        private Duration timeout = Duration.ofSeconds(2);
        private Duration delay = Duration.ZERO;
        private RuntimeException failure;
        volatile Map<String, String> observedMdc;

        private FakeSource(SourceSystem system, List<Document> documents) {
            this.system = system;
            this.documents = documents;
        }

        static FakeSource answering(SourceSystem system, Document... documents) {
            return new FakeSource(system, List.of(documents));
        }

        static FakeSource failingWith(SourceSystem system, SourceOutcome outcome) {
            return answering(system).throwing(new SourceUnavailableException(system, outcome, "simulated " + outcome));
        }

        FakeSource delayedBy(Duration delay) {
            this.delay = delay;
            return this;
        }

        FakeSource withTimeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        FakeSource throwing(RuntimeException failure) {
            this.failure = failure;
            return this;
        }

        @Override
        public SourceSystem system() {
            return system;
        }

        @Override
        public Duration timeout() {
            return timeout;
        }

        @Override
        public List<Document> fetchDocuments(Vin vin) {
            observedMdc = MDC.getCopyOfContextMap();
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (failure != null) {
                throw failure;
            }
            return documents;
        }
    }

    private static final class InMemorySnapshotStore implements DocumentSnapshotStore {

        private final Map<String, List<Document>> store = new ConcurrentHashMap<>();
        boolean failAllOperations;

        @Override
        public void replace(Vin vin, SourceSystem source, List<Document> documents, Instant fetchedAt) {
            if (failAllOperations) {
                throw new IllegalStateException("database down");
            }
            store.put(vin.value() + source, List.copyOf(documents));
        }

        @Override
        public List<Document> findLastKnown(Vin vin, SourceSystem source) {
            if (failAllOperations) {
                throw new IllegalStateException("database down");
            }
            return store.getOrDefault(vin.value() + source, List.of());
        }
    }

    private static final class RecordingAudit implements SearchAuditRecorder {

        final List<List<SourceResult>> searches = new ArrayList<>();

        @Override
        public void record(Vin vin, List<SourceResult> results, Instant requestedAt, Duration duration) {
            searches.add(results);
        }
    }
}
