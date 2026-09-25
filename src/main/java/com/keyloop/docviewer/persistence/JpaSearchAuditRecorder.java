package com.keyloop.docviewer.persistence;

import com.keyloop.docviewer.domain.SearchAuditRecorder;
import com.keyloop.docviewer.domain.SourceResult;
import com.keyloop.docviewer.domain.Vin;
import com.keyloop.docviewer.observability.CorrelationId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class JpaSearchAuditRecorder implements SearchAuditRecorder {

    private final SearchAuditRepository repository;

    JpaSearchAuditRecorder(SearchAuditRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void record(Vin vin, List<SourceResult> results, Instant requestedAt, Duration duration) {
        String outcomes = results.stream()
                .map(result -> result.source().name() + "=" + result.outcome().name())
                .collect(Collectors.joining(","));
        int documentCount = results.stream().mapToInt(result -> result.documents().size()).sum();
        boolean partial = results.stream().anyMatch(result -> !result.succeeded());
        repository.save(new SearchAuditEntity(
                vin.value(), MDC.get(CorrelationId.MDC_KEY), requestedAt, duration.toMillis(),
                partial, documentCount, outcomes));
    }
}
