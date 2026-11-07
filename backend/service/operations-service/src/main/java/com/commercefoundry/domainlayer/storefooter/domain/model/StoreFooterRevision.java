package com.commercefoundry.domainlayer.storefooter.domain.model;

import java.time.Instant;

public record StoreFooterRevision(long id, int revisionNumber, StoreFooterContent content, long createdBy,
                                  Instant createdAt, Long publishedBy, Instant publishedAt) {
    public StoreFooterRevision publish(long actorMemberId, Instant now) {
        return publishedAt == null
            ? new StoreFooterRevision(id, revisionNumber, content, createdBy, createdAt, actorMemberId, now) : this;
    }
}
