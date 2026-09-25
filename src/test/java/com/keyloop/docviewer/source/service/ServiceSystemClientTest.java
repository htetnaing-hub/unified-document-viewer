package com.keyloop.docviewer.source.service;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.keyloop.docviewer.domain.Document;
import com.keyloop.docviewer.domain.SourceOutcome;
import com.keyloop.docviewer.domain.SourceSystem;
import com.keyloop.docviewer.domain.SourceUnavailableException;
import com.keyloop.docviewer.domain.Vin;
import com.keyloop.docviewer.source.SourceProperties;
import io.micrometer.observation.ObservationRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Runs against the same mappings as the docker-compose mock (mocks/service). */
class ServiceSystemClientTest {

    private static final WireMockServer serviceApi =
            new WireMockServer(options().dynamicPort().usingFilesUnderDirectory("mocks/service"));

    private final ServiceSystemClient client = new ServiceSystemClient(
            new SourceProperties(URI.create(serviceApi.baseUrl()), Duration.ofMillis(500), Duration.ofMillis(800)),
            ObservationRegistry.NOOP);

    @BeforeAll
    static void start() {
        serviceApi.start();
    }

    @AfterAll
    static void stop() {
        serviceApi.stop();
    }

    @Test
    void mapsRepairOrderAttachmentsToDomainDocuments() {
        List<Document> documents = client.fetchDocuments(Vin.of("1HGCM82633A004352"));

        assertThat(documents).extracting(Document::reference).containsExactly("RO-5521", "RO-5521", "RO-6120");
        assertThat(documents).extracting(Document::source).containsOnly(SourceSystem.SERVICE);
        assertThat(documents.getFirst()).isEqualTo(new Document(
                "A-9001", SourceSystem.SERVICE, "REPAIR_ORDER", "30,000 mile service", "RO-5521",
                Instant.parse("2025-01-08T10:15:00Z"), "https://service.dealer.example/attachments/A-9001"));
    }

    @Test
    void unknownVehicleIsAnEmptyList() {
        assertThat(client.fetchDocuments(Vin.of("JH4KA7561PC000003"))).isEmpty();
    }

    @Test
    void serverErrorIsReportedAsErrorWithoutLeakingTheBody() {
        assertThatThrownBy(() -> client.fetchDocuments(Vin.of("WBA3A5C51DF000002")))
                .isInstanceOfSatisfying(SourceUnavailableException.class, e -> {
                    assertThat(e.outcome()).isEqualTo(SourceOutcome.ERROR);
                    assertThat(e.getMessage()).contains("500").doesNotContain("SQLException");
                });
    }

    @Test
    void slowResponseIsReportedAsTimeoutAfterTheConfiguredTimeout() {
        long started = System.nanoTime();

        assertThatThrownBy(() -> client.fetchDocuments(Vin.of("5YJSA1E26HF000001")))
                .isInstanceOfSatisfying(SourceUnavailableException.class, e ->
                        assertThat(e.outcome()).isEqualTo(SourceOutcome.TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
    }
}
