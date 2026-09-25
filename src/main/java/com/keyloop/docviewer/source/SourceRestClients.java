package com.keyloop.docviewer.source;

import com.keyloop.docviewer.observability.CorrelationId;
import io.micrometer.observation.ObservationRegistry;
import java.net.http.HttpClient;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Builds the HTTP client for a source system with explicit timeouts, tracing, and correlation id forwarding. */
public final class SourceRestClients {

    private SourceRestClients() {
    }

    public static RestClient create(SourceProperties properties, ObservationRegistry observations) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.timeout());

        return RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory)
                .observationRegistry(observations)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .requestInterceptor((request, body, execution) -> {
                    String correlationId = MDC.get(CorrelationId.MDC_KEY);
                    if (correlationId != null) {
                        request.getHeaders().set(CorrelationId.HEADER, correlationId);
                    }
                    return execution.execute(request, body);
                })
                .build();
    }
}
