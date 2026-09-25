package com.keyloop.docviewer.api;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import brave.handler.MutableSpan;
import brave.handler.SpanHandler;
import brave.propagation.TraceContext;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.keyloop.docviewer.TestcontainersConfiguration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Proves the tracing claim in the design: one search is one trace, and the calls to the two
 * source systems are child spans of the inbound request that run at the same time.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTracing // Boot disables tracing in tests by default
@Import({TestcontainersConfiguration.class, DocumentSearchTracingTest.SpanCapture.class})
class DocumentSearchTracingTest {

    private static final String VIN = "1HGCM82633A004352";

    private static final WireMockServer salesApi =
            new WireMockServer(options().dynamicPort().usingFilesUnderDirectory("mocks/sales"));
    private static final WireMockServer serviceApi =
            new WireMockServer(options().dynamicPort().usingFilesUnderDirectory("mocks/service"));

    static {
        salesApi.start();
        serviceApi.start();
    }

    @DynamicPropertySource
    static void sourceSystems(DynamicPropertyRegistry registry) {
        registry.add("docviewer.sources.sales.base-url", salesApi::baseUrl);
        registry.add("docviewer.sources.service.base-url", serviceApi::baseUrl);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SpanCapture spans;

    @BeforeEach
    void clearSpans() {
        spans.finished.clear();
    }

    @AfterAll
    static void stopSourceSystems() {
        salesApi.stop();
        serviceApi.stop();
    }

    @Test
    void sourceCallsAreParallelChildSpansOfTheSearchRequest() throws Exception {
        mockMvc.perform(get("/api/v1/vehicles/{vin}/documents", VIN)).andExpect(status().isOk());

        MutableSpan request = spans.finished.stream()
                .filter(span -> span.kind() == brave.Span.Kind.SERVER)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No server span recorded; spans: " + spans.finished));
        List<MutableSpan> sourceCalls = spans.finished.stream()
                .filter(span -> span.kind() == brave.Span.Kind.CLIENT)
                .toList();

        assertThat(sourceCalls).as("one client span per source system").hasSize(2);
        assertThat(sourceCalls).allSatisfy(call -> {
            assertThat(call.traceId()).as("same trace as the request").isEqualTo(request.traceId());
            assertThat(call.parentId()).as("child of the request span").isEqualTo(request.id());
        });

        // Parallel: each call started before the other one finished (the mocks answer in 150 and 250 ms).
        MutableSpan first = sourceCalls.get(0);
        MutableSpan second = sourceCalls.get(1);
        assertThat(first.startTimestamp()).isLessThan(second.finishTimestamp());
        assertThat(second.startTimestamp()).isLessThan(first.finishTimestamp());
    }

    /** Collects every finished span in memory instead of exporting it. */
    @TestConfiguration(proxyBeanMethods = false)
    static class SpanCapture {

        final List<MutableSpan> finished = new CopyOnWriteArrayList<>();

        @Bean
        SpanHandler capturingSpanHandler() {
            return new SpanHandler() {
                @Override
                public boolean end(TraceContext context, MutableSpan span, Cause cause) {
                    if (cause == Cause.FINISHED) {
                        finished.add(span);
                    }
                    return true;
                }
            };
        }
    }
}
