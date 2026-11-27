package com.commercefoundry.domainlayer.storenavigation.domain.model;

import java.util.Objects;

/** 배포당 하나인 상단 메뉴 root. 최신 초안·게시 리비전 포인터와 낙관 버전만 가진다(storefooter 와 같은 규칙). */
public record StoreNavigation(int latestRevision, Integer publishedRevision, Long version) {
    public StoreNavigation append(Long expectedVersion) {
        requireVersion(expectedVersion);
        if (latestRevision == Integer.MAX_VALUE) {
            throw new StoreNavigationRuleException(StoreNavigationRuleException.Reason.CONFLICT);
        }
        return new StoreNavigation(latestRevision + 1, publishedRevision, version);
    }

    /** 현재 게시 리비전의 반복 게시는 오래된 버전이어도 변경 없이 성공한다. 그 밖에는 최신 초안만 게시한다. */
    public StoreNavigation publish(Long expectedVersion, int revisionNumber) {
        if (Objects.equals(publishedRevision, revisionNumber)) {
            return this;
        }
        requireVersion(expectedVersion);
        if (revisionNumber != latestRevision) {
            throw new StoreNavigationRuleException(StoreNavigationRuleException.Reason.CONFLICT);
        }
        return new StoreNavigation(latestRevision, revisionNumber, version);
    }

    private void requireVersion(Long expectedVersion) {
        if (!Objects.equals(version, expectedVersion)) {
            throw new StoreNavigationRuleException(StoreNavigationRuleException.Reason.CONFLICT);
        }
    }
}
