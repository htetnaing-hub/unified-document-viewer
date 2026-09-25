package com.keyloop.docviewer.api;

import com.keyloop.docviewer.domain.AllSourcesUnavailableException;
import com.keyloop.docviewer.domain.InvalidVinException;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain failures to RFC 9457 (formerly 7807) problem responses. */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(InvalidVinException.class)
    ProblemDetail invalidVin(InvalidVinException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setTitle("Invalid VIN");
        problem.setType(URI.create("urn:docviewer:problem:invalid-vin"));
        return problem;
    }

    @ExceptionHandler(AllSourcesUnavailableException.class)
    ProblemDetail allSourcesUnavailable(AllSourcesUnavailableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "None of the document sources answered and no stored copy is available. Try again shortly.");
        problem.setTitle("Document sources unavailable");
        problem.setType(URI.create("urn:docviewer:problem:sources-unavailable"));
        problem.setProperty("sources", e.results().stream()
                .map(DocumentSearchResponse.SourceStatusResponse::from)
                .toList());
        return problem;
    }
}
