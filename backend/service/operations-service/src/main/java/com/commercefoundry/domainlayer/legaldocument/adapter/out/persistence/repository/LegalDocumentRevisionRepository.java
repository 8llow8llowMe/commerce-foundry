package com.commercefoundry.domainlayer.legaldocument.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.legaldocument.adapter.out.persistence.entity.LegalDocumentRevisionEntity;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LegalDocumentRevisionRepository extends JpaRepository<LegalDocumentRevisionEntity, Long> {
    Optional<LegalDocumentRevisionEntity> findByDocumentIdAndRevisionNumber(long documentId, int revisionNumber);
    Page<LegalDocumentRevisionEntity> findAllByDocumentId(long documentId, Pageable pageable);
}
