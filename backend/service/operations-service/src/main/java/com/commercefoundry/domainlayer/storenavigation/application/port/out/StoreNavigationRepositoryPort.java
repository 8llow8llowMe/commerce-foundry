package com.commercefoundry.domainlayer.storenavigation.application.port.out;

import com.commercefoundry.domainlayer.storenavigation.application.port.out.query.StoreNavigationQueryResults;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationContent;
import com.commercefoundry.domainlayer.storenavigation.domain.model.StoreNavigationRevision;
import java.time.Instant;
import java.util.Optional;

public interface StoreNavigationRepositoryPort {
    Optional<StoreNavigationQueryResults.Detail> detail();

    /** 현재 게시 리비전. 미등록·미게시는 비어 있다. */
    Optional<StoreNavigationRevision> published();

    /** root 버전 확인·증가와 새 리비전·항목 INSERT 를 한 독립 트랜잭션으로 반영한다. 항목 행은 (리비전, key) 가 PK 라 별도 ID 가 없다. */
    StoreNavigationQueryResults.Detail saveDraft(Long expectedVersion, StoreNavigationContent content, long revisionId, long actorMemberId, Instant now);

    /**
     * 게시 포인터와 최초 게시 기록을 한 독립 트랜잭션으로 반영한다. 현재 게시 리비전의 반복 게시는 변경 없이 현재 상태를 돌려준다.
     * 새 게시는 현재 배포 설정의 URL 규칙으로 저장본을 다시 검증한다.
     */
    StoreNavigationQueryResults.Detail publish(Long expectedVersion, int revisionNumber, long actorMemberId, Instant now);
}
