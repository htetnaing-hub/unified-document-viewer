package com.keyloop.docviewer.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SearchAuditRepository extends JpaRepository<SearchAuditEntity, UUID> {

    List<SearchAuditEntity> findByVinOrderByRequestedAtDesc(String vin);
}
