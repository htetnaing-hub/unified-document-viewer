package com.keyloop.docviewer.observability;

import java.util.UUID;
import java.util.regex.Pattern;

/** Correlation id shared by the inbound request, its logs, and the calls it makes to source systems. */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private CorrelationId() {
    }

    /** Reuses a caller-supplied id only if it is safe to log and forward; otherwise creates one. */
    public static String fromHeaderOrNew(String header) {
        return header != null && SAFE.matcher(header).matches() ? header : UUID.randomUUID().toString();
    }
}
