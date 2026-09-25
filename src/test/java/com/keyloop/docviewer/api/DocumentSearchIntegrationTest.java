package com.keyloop.docviewer.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.keyloop.docviewer.TestcontainersConfiguration;
import com.keyloop.docviewer.persistence.SearchAuditRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End to end through the real stack: HTTP API → parallel adapters → WireMock source systems,
 * with PostgreSQL (Testcontainers) for snapshots and audit.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureMetrics // Boot disables metric exporters (and /actuator/prometheus) in tests by default
@Import(TestcontainersConfiguration.class)
class DocumentSearchIntegrationTest {

    private static final String HAPPY_VIN = "1HGCM82633A004352";
    private static final String SLOW_SERVICE_VIN = "5YJSA1E26HF000001";
    private static final String FAILING_SERVICE_VIN = "WBA3A5C51DF000002";
    private static final String NO_DOCUMENTS_VIN = "JH4KA7561PC000003";

    private static final WireMockServer salesApi =
            new WireMockServer(options().dynamicPort().usingFilesUnderDirectory("mocks/sales"));
    private static final WireMockServer serviceApi =
            new WireMockServer(options().dynamicPort().usingFilesUnderDirectory("mocks/service"));

    static {
        salesApi.start();
        serviceApi.start();
        // The mock servers run in this JVM and are slow on their very first request (class
        // loading, template compilation). Warm them up so a cold mock is not mistaken for a
        // slow source by whichever test happens to run first.
        warmUp(salesApi.baseUrl() + "/api/v1/vehicles/" + HAPPY_VIN + "/deals");
        warmUp(serviceApi.baseUrl() + "/api/v1/vehicles/" + HAPPY_VIN + "/repair-orders");
    }

    private static void warmUp(String url) {
        try (HttpClient client = HttpClient.newHttpClient()) {
            client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            throw new IllegalStateException("Mock source system did not start", e);
        }
    }

    @DynamicPropertySource
    static void sourceSystems(DynamicPropertyRegistry registry) {
        registry.add("docviewer.sources.sales.base-url", salesApi::baseUrl);
        registry.add("docviewer.sources.service.base-url", serviceApi::baseUrl);
        registry.add("docviewer.sources.sales.timeout", () -> "1s");
        registry.add("docviewer.sources.service.timeout", () -> "1s");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SearchAuditRepository auditRepository;

    @AfterEach
    void resetStubs() {
        salesApi.resetToDefaultMappings();
        serviceApi.resetToDefaultMappings();
    }

    @AfterAll
    static void stopSourceSystems() {
        salesApi.stop();
        serviceApi.stop();
    }

    @Test
    void returnsOneConsolidatedListTaggedBySourceNewestFirst() throws Exception {
        mockMvc.perform(get("/api/v1/vehicles/{vin}/documents", HAPPY_VIN))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.vin").value(HAPPY_VIN))
                .andExpect(jsonPath("$.partial").value(false))
                .andExpect(jsonPath("$.sources[*].system", contains("SALES", "SERVICE")))
                .andExpect(jsonPath("$.sources[*].status", everyItem(is("OK"))))
                .andExpect(jsonPath("$.documents", hasSize(6)))
                .andExpect(jsonPath("$.documents[0].id").value("SERVICE:A-9107"))
                .andExpect(jsonPath("$.documents[0].source").value("SERVICE"))
                .andExpect(jsonPath("$.documents[0].sourceDisplayName").value("Service System"))
                .andExpect(jsonPath("$.documents[5].source").value("SALES"))
                .andExpect(jsonPath("$.documents[*].stale", everyItem(is(false))));
    }

    @Test
    void acceptsLowerCaseVin() throws Exception {
        mockMvc.perform(get("/api/v1/vehicles/{vin}/documents", HAPPY_VIN.toLowerCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vin").value(HAPPY_VIN));
    }

    @Test
    void slowSourceGivesPartialResultWithinTheTimeout() throws Exception {
        long started = System.nanoTime();

        mockMvc.perform(get("/api/v1/vehicles/{vin}/documents", SLOW_SERVICE_VIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partial").value(true))
                .andExpect(jsonPath("$.sources[0].status").value("OK"))
                .andExpect(jsonPath("$.sources[1].status").value("TIMEOUT"))
                .andExpect(jsonPath("$.documents[*].source", everyItem(is("SALES"))))
                .andExpect(jsonPath("$.documents", hasSize(2)));

        // The Service mock takes 5 s; the search must give up after its 1 s timeout.
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
    }

    @Test
    void failedSourceIsServedFromTheLastKnownSnapshotAndFlaggedStale() throws Exception {
        String vin = "2T1BURHE0JC000004";
        salesApi.stubFor(WireMock.get(urlPathEqualTo("/api/v1/vehicles/" + vin + "/deals"))
                .atPriority(0)
                .willReturn(aResponse().withStatus(404)));

        // First search: Service answers and its documents are stored.
        mockMvc.perform(get("/api/v1/vehicles/{vin}/documents", vin))
                .andExpect(jsonPath("$.documents[0].stale").value(false));

        // Then the Service System goes down.
        serviceApi.stubFor(WireMock.get(urlPathEqualTo("/api/v1/vehicles/" + vin + "/repair-orders"))
                .atPriority(0)
                .willReturn(aResponse().withStatus(500)));

        mockMvc.perform(get("/api/v1/vehicles/{vin}/documents", vin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partial").value(true))
                .andExpect(jsonPath("$.sources[1].status").value("ERROR"))
                .andExpect(jsonPath("$.sources[1].stale").value(true))
                .andExpect(jsonPath("$.documents", hasSize(1)))
                .andExpect(jsonPath("$.documents[0].id").value("SERVICE:A-8001"))
                .andExpect(jsonPath("$.documents[0].stale").value(true));
    }

    @Test
    void upstreamErrorDetailsAreNotLeaked() throws Exception {
        mockMvc.perform(get("/api/v1/vehicles/{vin}/documents", FAILING_SERVICE_VIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources[1].status").value("ERROR"))
                .andExpect(content().string(not(containsString("SQLException"))));
    }

    @Test
    void vehicleWithoutDocumentsReturnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/v1/vehicles/{vin}/documents", NO_DOCUMENTS_VIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.partial").value(false))
                .andExpect(jsonPath("$.documents", hasSize(0)));
    }

    @Test
    void returns503WhenNoSourceAnswersAndNothingIsStored() throws Exception {
        String vin = "3VWFE21C04M000005";
        salesApi.stubFor(WireMock.get(urlPathEqualTo("/api/v1/vehicles/" + vin + "/deals"))
                .atPriority(0).willReturn(aResponse().withStatus(500)));
        serviceApi.stubFor(WireMock.get(urlPathEqualTo("/api/v1/vehicles/" + vin + "/repair-orders"))
                .atPriority(0).willReturn(aResponse().withStatus(502)));

        mockMvc.perform(get("/api/v1/vehicles/{vin}/documents", vin))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Document sources unavailable"))
                .andExpect(jsonPath("$.sources[*].status", everyItem(is("ERROR"))));
    }

    @Test
    void rejectsMalformedVinWithProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/vehicles/{vin}/documents", "NOT-A-VIN"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid VIN"))
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void recordsEverySearchInTheAuditTrailWithItsCorrelationId() throws Exception {
        String vin = "1FTFW1ET5DF000006";

        mockMvc.perform(get("/api/v1/vehicles/{vin}/documents", vin).header("X-Correlation-Id", "audit-test-1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Correlation-Id", "audit-test-1"));

        assertThat(auditRepository.findByVinOrderByRequestedAtDesc(vin)).singleElement().satisfies(audit -> {
            assertThat(audit.getCorrelationId()).isEqualTo("audit-test-1");
            assertThat(audit.getSourceOutcomes()).isEqualTo("SALES=OK,SERVICE=OK");
            assertThat(audit.isPartial()).isFalse();
        });
    }

    @Test
    void generatesCorrelationIdWhenCallerSendsNoneOrAnUnsafeOne() throws Exception {
        mockMvc.perform(get("/api/v1/vehicles/{vin}/documents", HAPPY_VIN).header("X-Correlation-Id", "bad value\r\nx"))
                .andExpect(header().string("X-Correlation-Id", org.hamcrest.Matchers.matchesPattern("[0-9a-f-]{36}")));
    }

    @Test
    void exposesPerSourceMetricsAndHealth() throws Exception {
        mockMvc.perform(get("/api/v1/vehicles/{vin}/documents", FAILING_SERVICE_VIN));

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("docviewer_source_requests_seconds_count{")))
                .andExpect(content().string(containsString("outcome=\"error\"")));

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.documentSources.details.SERVICE.lastOutcome").exists());

        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void publishesOpenApiContract() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/vehicles/{vin}/documents'].get").exists());
    }
}
