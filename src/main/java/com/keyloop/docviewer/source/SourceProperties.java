package com.keyloop.docviewer.source;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Connection settings for one source system.
 *
 * @param baseUrl        root URL of the source system's API
 * @param connectTimeout maximum time to establish a TCP connection
 * @param timeout        maximum time a search waits for the source's answer
 */
public record SourceProperties(
        @NotNull URI baseUrl,
        @DefaultValue("500ms") Duration connectTimeout,
        @DefaultValue("2s") Duration timeout) {
}
