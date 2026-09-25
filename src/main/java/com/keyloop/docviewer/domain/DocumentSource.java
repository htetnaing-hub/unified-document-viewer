package com.keyloop.docviewer.domain;

import java.time.Duration;
import java.util.List;

/**
 * Port to an external system that holds vehicle documents. Each implementation is an
 * adapter that translates the system's own API into {@link Document}s, so adding a third
 * system means adding one adapter and nothing else.
 */
public interface DocumentSource {

    SourceSystem system();

    /** Maximum time a search waits for this source before reporting it as timed out. */
    Duration timeout();

    /**
     * Returns every document the source holds for the VIN; an unknown VIN yields an empty list.
     *
     * @throws SourceUnavailableException when the source cannot be reached or answers with an error
     */
    List<Document> fetchDocuments(Vin vin);
}
