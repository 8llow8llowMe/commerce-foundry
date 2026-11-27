package com.commercefoundry.domainlayer.storenavigation.domain.model;

import java.time.Instant;

/** 불변 메뉴 리비전. 최초 게시자·게시 시각은 한 번만 기록한다. */
public record StoreNavigationRevision(long id, int revisionNumber, StoreNavigationContent content, long createdBy,
                                      Instant createdAt, Long publishedBy, Instant publishedAt) {
    public StoreNavigationRevision publish(long actorMemberId, Instant now) {
        return publishedAt == null
            ? new StoreNavigationRevision(id, revisionNumber, content, createdBy, createdAt, actorMemberId, now) : this;
    }
}
