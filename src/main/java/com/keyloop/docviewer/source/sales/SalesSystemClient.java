package com.keyloop.docviewer.source.sales;

import com.keyloop.docviewer.domain.Document;
import com.keyloop.docviewer.domain.DocumentSource;
import com.keyloop.docviewer.domain.SourceSystem;
import com.keyloop.docviewer.domain.Vin;
import com.keyloop.docviewer.source.SourceFailures;
import com.keyloop.docviewer.source.SourceProperties;
import com.keyloop.docviewer.source.SourceRestClients;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Adapter for the Sales System API. It answers 404 for a VIN it has never sold. */
public class SalesSystemClient implements DocumentSource {

    private final RestClient restClient;
    private final Duration timeout;

    public SalesSystemClient(SourceProperties properties, ObservationRegistry observations) {
        this.restClient = SourceRestClients.create(properties, observations);
        this.timeout = properties.timeout();
    }

    @Override
    public SourceSystem system() {
        return SourceSystem.SALES;
    }

    @Override
    public Duration timeout() {
        return timeout;
    }

    @Override
    public List<Document> fetchDocuments(Vin vin) {
        try {
            return restClient.get()
                    .uri("/api/v1/vehicles/{vin}/deals", vin.value())
                    .exchange((request, response) -> {
                        if (response.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                            return List.of();
                        }
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw SourceFailures.unexpectedStatus(SourceSystem.SALES, response.getStatusCode());
                        }
                        return SalesDocumentMapper.toDocuments(response.bodyTo(SalesApiModel.DealsResponse.class));
                    });
        } catch (RestClientException e) {
            throw SourceFailures.from(SourceSystem.SALES, e);
        }
    }
}
