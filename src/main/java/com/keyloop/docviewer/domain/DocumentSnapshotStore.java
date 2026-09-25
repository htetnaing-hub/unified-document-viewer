package com.keyloop.docviewer.domain;

import java.time.Instant;
import java.util.List;

/**
 * Last known documents per VIN and source, used as a fallback when a source is down.
 */
public interface DocumentSnapshotStore {

    /** Replaces the stored documents of one source for the VIN with a fresh result. */
    void replace(Vin vin, SourceSystem source, List<Document> documents, Instant fetchedAt);

    List<Document> findLastKnown(Vin vin, SourceSystem source);
}
