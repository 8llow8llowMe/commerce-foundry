package com.commercefoundry.domainlayer.storefooter.application.port.out;

import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterContent;
import com.commercefoundry.domainlayer.storefooter.domain.model.StoreFooterRevision;
import com.commercefoundry.domainlayer.storefooter.application.port.out.query.StoreFooterQueryResults;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface StoreFooterRepositoryPort {
    Optional<StoreFooterQueryResults.Detail> detail();
    Optional<StoreFooterRevision> published();
    Page<StoreFooterRevision> revisions(Pageable pageable);
    StoreFooterQueryResults.Detail saveDraft(Long expectedVersion, StoreFooterContent content, long revisionId, List<Long> linkIds, long actorMemberId, Instant now);
    StoreFooterQueryResults.Detail publish(Long expectedVersion, int revisionNumber, long actorMemberId, Instant now);
}
