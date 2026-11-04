package com.commercefoundry.domainlayer.legaldocument.application.port.out;

import com.commercefoundry.domainlayer.legaldocument.domain.model.LegalDocument;
import com.commercefoundry.domainlayer.legaldocument.domain.model.LegalDocumentKey;
import com.commercefoundry.domainlayer.legaldocument.domain.model.LegalDocumentRevision;
import com.commercefoundry.domainlayer.legaldocument.application.port.out.query.LegalDocumentQueryResults;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface LegalDocumentRepositoryPort {
    List<LegalDocument> list();
    Optional<LegalDocumentQueryResults.Detail> detail(LegalDocumentKey key);
    Page<LegalDocumentRevision> revisions(LegalDocumentKey key, Pageable pageable);
    List<LegalDocumentQueryResults.Published> published(String locale);
    Optional<LegalDocumentQueryResults.Published> published(LegalDocumentKey key, Integer revisionNumber);
    LegalDocumentQueryResults.Detail saveDraft(LegalDocumentKey key, Long expectedVersion, String title, String body,
                                               long actorMemberId, long newDocumentId, long revisionId, Instant now);
    LegalDocumentQueryResults.Detail publish(LegalDocumentKey key, Long expectedVersion, int revisionNumber,
                                             boolean reviewConfirmed, long actorMemberId, Instant now);
}
