package com.keyloop.docviewer.config;

import com.keyloop.docviewer.aggregation.DocumentAggregator;
import com.keyloop.docviewer.domain.DocumentSnapshotStore;
import com.keyloop.docviewer.domain.DocumentSource;
import com.keyloop.docviewer.domain.SearchAuditRecorder;
import com.keyloop.docviewer.observability.CorrelationIdFilter;
import com.keyloop.docviewer.observability.DocumentSourcesHealthIndicator;
import com.keyloop.docviewer.observability.MdcPropagatingTaskDecorator;
import com.keyloop.docviewer.observability.SourceTelemetry;
import com.keyloop.docviewer.source.SourcesProperties;
import com.keyloop.docviewer.source.sales.SalesSystemClient;
import com.keyloop.docviewer.source.service.ServiceSystemClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SourcesProperties.class, AggregationProperties.class})
class DocViewerConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    SalesSystemClient salesSystemClient(SourcesProperties sources, ObservationRegistry observations) {
        return new SalesSystemClient(sources.sales(), observations);
    }

    @Bean
    ServiceSystemClient serviceSystemClient(SourcesProperties sources, ObservationRegistry observations) {
        return new ServiceSystemClient(sources.service(), observations);
    }

    /** One virtual thread per source call, bounded, carrying the caller's tracing context and MDC. */
    @Bean(destroyMethod = "close")
    SimpleAsyncTaskExecutor sourceCallExecutor(AggregationProperties aggregation) {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("source-call-");
        executor.setVirtualThreads(true);
        executor.setConcurrencyLimit(aggregation.maxConcurrentSourceCalls());
        executor.setTaskDecorator(new MdcPropagatingTaskDecorator());
        return executor;
    }

    @Bean
    SourceTelemetry sourceTelemetry(MeterRegistry meterRegistry, Clock clock) {
        return new SourceTelemetry(meterRegistry, clock);
    }

    @Bean
    DocumentAggregator documentAggregator(
            List<DocumentSource> sources,
            @Qualifier("sourceCallExecutor") SimpleAsyncTaskExecutor sourceCallExecutor,
            DocumentSnapshotStore snapshots,
            SearchAuditRecorder audit,
            SourceTelemetry telemetry,
            Clock clock) {
        return new DocumentAggregator(sources, sourceCallExecutor, snapshots, audit, telemetry, clock);
    }

    @Bean
    DocumentSourcesHealthIndicator documentSourcesHealthIndicator(SourceTelemetry telemetry) {
        return new DocumentSourcesHealthIndicator(telemetry);
    }

    @Bean
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration = new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
