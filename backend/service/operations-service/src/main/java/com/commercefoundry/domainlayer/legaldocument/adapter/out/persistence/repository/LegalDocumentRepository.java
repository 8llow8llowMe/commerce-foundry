package com.commercefoundry.domainlayer.legaldocument.adapter.out.persistence.repository;

import com.commercefoundry.domainlayer.legaldocument.adapter.out.persistence.entity.LegalDocumentEntity;
import com.commercefoundry.domainlayer.legaldocument.adapter.out.persistence.repository.custom.LegalDocumentPublishedCustomRepository;
import com.commercefoundry.domainlayer.legaldocument.domain.model.LegalDocumentKey;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LegalDocumentRepository extends JpaRepository<LegalDocumentEntity, Long>, LegalDocumentPublishedCustomRepository {
    Optional<LegalDocumentEntity> findByTypeAndLocale(LegalDocumentKey.Type type, String locale);
}
