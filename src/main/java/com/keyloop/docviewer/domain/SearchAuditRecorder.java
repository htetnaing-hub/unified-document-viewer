package com.keyloop.docviewer.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Records every search for support and audit purposes: who looked up which VIN, and how each source behaved. */
public interface SearchAuditRecorder {

    void record(Vin vin, List<SourceResult> results, Instant requestedAt, Duration duration);
}
