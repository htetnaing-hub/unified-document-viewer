package com.keyloop.docviewer.source.service;

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
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Adapter for the Service System API. It answers 200 with an empty list for an unknown VIN. */
public class ServiceSystemClient implements DocumentSource {

    private final RestClient restClient;
    private final Duration timeout;

    public ServiceSystemClient(SourceProperties properties, ObservationRegistry observations) {
        this.restClient = SourceRestClients.create(properties, observations);
        this.timeout = properties.timeout();
    }

    @Override
    public SourceSystem system() {
        return SourceSystem.SERVICE;
    }

    @Override
    public Duration timeout() {
        return timeout;
    }

    @Override
    public List<Document> fetchDocuments(Vin vin) {
        try {
            return restClient.get()
                    .uri("/api/v1/vehicles/{vin}/repair-orders", vin.value())
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw SourceFailures.unexpectedStatus(SourceSystem.SERVICE, response.getStatusCode());
                        }
                        return ServiceDocumentMapper.toDocuments(response.bodyTo(ServiceApiModel.RepairOrdersResponse.class));
                    });
        } catch (RestClientException e) {
            throw SourceFailures.from(SourceSystem.SERVICE, e);
        }
    }
}
