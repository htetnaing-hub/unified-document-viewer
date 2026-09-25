package com.keyloop.docviewer.persistence;

import com.keyloop.docviewer.domain.Document;
import com.keyloop.docviewer.domain.DocumentSnapshotStore;
import com.keyloop.docviewer.domain.SourceSystem;
import com.keyloop.docviewer.domain.Vin;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class JpaDocumentSnapshotStore implements DocumentSnapshotStore {

    private final DocumentSnapshotRepository repository;

    JpaDocumentSnapshotStore(DocumentSnapshotRepository repository) {
        this.repository = repository;
    }

    /**
     * Delete-then-insert in one transaction, so documents the source no longer returns are
     * removed and readers never see a half-written snapshot. Two concurrent refreshes of the
     * same VIN can collide on the unique key; the loser's transaction rolls back, which is
     * harmless because the winner stored equivalent data.
     */
    @Override
    @Transactional
    public void replace(Vin vin, SourceSystem source, List<Document> documents, Instant fetchedAt) {
        repository.deleteByVinAndSource(vin.value(), source);
        repository.saveAll(documents.stream()
                .map(document -> DocumentSnapshotEntity.of(vin.value(), document, fetchedAt))
                .toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Document> findLastKnown(Vin vin, SourceSystem source) {
        return repository.findByVinAndSource(vin.value(), source).stream()
                .map(DocumentSnapshotEntity::toDocument)
                .toList();
    }
}
