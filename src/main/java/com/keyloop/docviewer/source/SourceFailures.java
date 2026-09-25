package com.keyloop.docviewer.source;

import com.keyloop.docviewer.domain.SourceOutcome;
import com.keyloop.docviewer.domain.SourceSystem;
import com.keyloop.docviewer.domain.SourceUnavailableException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClientException;

/** Translates HTTP client failures into the domain's {@link SourceUnavailableException}. */
public final class SourceFailures {

    private SourceFailures() {
    }

    public static SourceUnavailableException from(SourceSystem source, RestClientException e) {
        SourceOutcome outcome = isTimeout(e) ? SourceOutcome.TIMEOUT : SourceOutcome.ERROR;
        return new SourceUnavailableException(source, outcome, source.displayName() + " call failed: " + e.getMessage(), e);
    }

    public static SourceUnavailableException unexpectedStatus(SourceSystem source, HttpStatusCode status) {
        // Deliberately not including the response body: it may contain internal details of the source system.
        return new SourceUnavailableException(source, SourceOutcome.ERROR,
                source.displayName() + " responded with HTTP " + status.value());
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }
}
