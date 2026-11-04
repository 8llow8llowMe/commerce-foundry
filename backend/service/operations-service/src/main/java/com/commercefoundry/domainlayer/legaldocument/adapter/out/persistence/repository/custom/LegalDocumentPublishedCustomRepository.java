package com.commercefoundry.domainlayer.legaldocument.adapter.out.persistence.repository.custom;

import com.commercefoundry.domainlayer.legaldocument.application.port.out.query.LegalDocumentQueryResults;
import com.commercefoundry.domainlayer.legaldocument.domain.model.LegalDocumentKey;
import java.util.List;
import java.util.Optional;

public interface LegalDocumentPublishedCustomRepository {
    List<LegalDocumentQueryResults.Published> published(String locale);
    Optional<LegalDocumentQueryResults.Published> published(LegalDocumentKey key, Integer revisionNumber);
}
