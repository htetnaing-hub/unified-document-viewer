package com.keyloop.docviewer.source;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** {@code docviewer.sources.*}: where the Sales and Service systems live and how long to wait for them. */
@Validated
@ConfigurationProperties("docviewer.sources")
public record SourcesProperties(
        @NotNull @Valid SourceProperties sales,
        @NotNull @Valid SourceProperties service) {
}
