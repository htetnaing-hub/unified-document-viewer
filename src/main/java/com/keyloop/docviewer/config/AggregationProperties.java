package com.keyloop.docviewer.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code docviewer.aggregation.*}
 *
 * @param maxConcurrentSourceCalls cap on in-flight calls to source systems per instance. Virtual
 *                                 threads are cheap, but the source systems are not: under a load
 *                                 spike this protects them (and our socket count), making callers
 *                                 wait instead of piling on more requests.
 */
@Validated
@ConfigurationProperties("docviewer.aggregation")
public record AggregationProperties(@DefaultValue("200") @Positive int maxConcurrentSourceCalls) {
}
