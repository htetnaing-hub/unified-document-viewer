package com.keyloop.docviewer.source.sales;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** Runs against the same mappings as the docker-compose mock (mocks/sales). */
class SalesSystemClientTest {

    private static final WireMockServer salesApi =
            new WireMockServer(options().dynamicPort().usingFilesUnderDirectory("mocks/sales"));

    private final SalesSystemClient client = new SalesSystemClient(
            new SourceProperties(URI.create(salesApi.baseUrl()), Duration.ofMillis(500), Duration.ofMillis(800)),
            ObservationRegistry.NOOP);

    @BeforeAll
    static void start() {
        salesApi.start();
    }

    @AfterEach
    void resetStubs() {
        salesApi.resetToDefaultMappings();
        MDC.clear();
    }

    @AfterAll
    static void stop() {
        salesApi.stop();
    }

    @Test
    void mapsDealDocumentsToDomainDocuments() {
        List<Document> documents = client.fetchDocuments(Vin.of("1HGCM82633A004352"));

        assertThat(documents).hasSize(3).allSatisfy(document -> {
            assertThat(document.source()).isEqualTo(SourceSystem.SALES);
            assertThat(document.reference()).isEqualTo("D-1001");
        });
        assertThat(documents.getFirst()).isEqualTo(new Document(
                "S-1001-INV", SourceSystem.SALES, "SALES_INVOICE", "Sales invoice #1001", "D-1001",
                Instant.parse("2023-03-12T00:00:00Z"), "https://sales.dealer.example/documents/S-1001-INV"));
    }

    @Test
    void unknownVehicleIsAnEmptyListNotAnError() {
        assertThat(client.fetchDocuments(Vin.of("JH4KA7561PC000003"))).isEmpty();
    }

    @Test
    void connectionResetIsReportedAsError() {
        assertThatThrownBy(() -> client.fetchDocuments(Vin.of("2T1BURHE0JC000004")))
                .isInstanceOfSatisfying(SourceUnavailableException.class, e ->
                        assertThat(e.outcome()).isEqualTo(SourceOutcome.ERROR));
    }

    @Test
    void serverErrorIsReportedAsErrorWithoutLeakingTheBody() {
        salesApi.stubFor(get(urlPathEqualTo("/api/v1/vehicles/1HGCM82633A004352/deals"))
                .atPriority(0)
                .willReturn(aResponse().withStatus(503).withBody("internal-secret-detail")));

        assertThatThrownBy(() -> client.fetchDocuments(Vin.of("1HGCM82633A004352")))
                .isInstanceOfSatisfying(SourceUnavailableException.class, e -> {
                    assertThat(e.outcome()).isEqualTo(SourceOutcome.ERROR);
                    assertThat(e.getMessage()).contains("503").doesNotContain("internal-secret-detail");
                });
    }

    @Test
    void slowResponseIsReportedAsTimeout() {
        salesApi.stubFor(get(urlPathEqualTo("/api/v1/vehicles/1HGCM82633A004352/deals"))
                .atPriority(0)
                .willReturn(aResponse().withStatus(200).withFixedDelay(3_000)));

        assertThatThrownBy(() -> client.fetchDocuments(Vin.of("1HGCM82633A004352")))
                .isInstanceOfSatisfying(SourceUnavailableException.class, e ->
                        assertThat(e.outcome()).isEqualTo(SourceOutcome.TIMEOUT));
    }

    @Test
    void toleratesUnknownFieldsAndMissingOptionalOnes() {
        salesApi.stubFor(get(urlPathEqualTo("/api/v1/vehicles/1HGCM82633A004352/deals"))
                .atPriority(0)
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"vin":"1HGCM82633A004352","newField":true,
                         "deals":[{"dealId":"D-1","documents":[
                            {"docId":"S-1","futureField":"x"},
                            {"name":"no id, skipped"},
                            null]},
                          {"dealId":"D-2"}]}
                        """)));

        assertThat(client.fetchDocuments(Vin.of("1HGCM82633A004352")))
                .singleElement()
                .satisfies(document -> {
                    assertThat(document.externalId()).isEqualTo("S-1");
                    assertThat(document.issuedAt()).isNull();
                });
    }

    @Test
    void forwardsCorrelationId() {
        MDC.put("correlationId", "abc-123");

        client.fetchDocuments(Vin.of("1HGCM82633A004352"));

        salesApi.verify(getRequestedFor(urlPathEqualTo("/api/v1/vehicles/1HGCM82633A004352/deals"))
                .withHeader("X-Correlation-Id", equalTo("abc-123")));
    }
}
