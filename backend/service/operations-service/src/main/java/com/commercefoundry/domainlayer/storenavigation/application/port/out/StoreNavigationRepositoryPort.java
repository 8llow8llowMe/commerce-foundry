package com.commercefoundry.domainlayer.storenavigation.application.port.out;

import com.commercefoundry.domainlayer.storenavigation.application.port.out.query.StoreNavigationQueryResults;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationContent;
import java.time.Instant;
import java.util.Optional;

public interface StoreNavigationRepositoryPort {
    Optional<StoreNavigationQueryResults.Detail> detail();

    /** root 버전 확인·증가와 새 리비전·항목 INSERT 를 한 독립 트랜잭션으로 반영한다. 항목 행은 (리비전, key) 가 PK 라 별도 ID 가 없다. */
    StoreNavigationQueryResults.Detail saveDraft(Long expectedVersion, StoreNavigationContent content, long revisionId, long actorMemberId, Instant now);
}
