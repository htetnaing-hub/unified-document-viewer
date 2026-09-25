package com.keyloop.docviewer.persistence;

import com.keyloop.docviewer.domain.SourceSystem;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface DocumentSnapshotRepository extends JpaRepository<DocumentSnapshotEntity, UUID> {

    List<DocumentSnapshotEntity> findByVinAndSource(String vin, SourceSystem source);

    // Bulk delete: one statement instead of loading and deleting each entity.
    @Modifying
    @Query("delete from DocumentSnapshotEntity d where d.vin = :vin and d.source = :source")
    int deleteByVinAndSource(@Param("vin") String vin, @Param("source") SourceSystem source);
}
