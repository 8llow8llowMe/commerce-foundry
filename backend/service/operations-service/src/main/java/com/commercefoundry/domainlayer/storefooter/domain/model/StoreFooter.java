package com.commercefoundry.domainlayer.storefooter.domain.model;

import java.util.Objects;

public record StoreFooter(int latestRevision, Integer publishedRevision, Long version) {
    public StoreFooter append(Long expectedVersion) {
        requireVersion(expectedVersion);
        if (latestRevision == Integer.MAX_VALUE) {
            throw new StoreFooterRuleException(StoreFooterRuleException.Reason.CONFLICT);
        }
        return new StoreFooter(latestRevision + 1, publishedRevision, version);
    }

    public StoreFooter publish(Long expectedVersion, int revisionNumber) {
        if (Objects.equals(publishedRevision, revisionNumber)) {
            return this;
        }
        requireVersion(expectedVersion);
        if (revisionNumber != latestRevision) {
            throw new StoreFooterRuleException(StoreFooterRuleException.Reason.CONFLICT);
        }
        return new StoreFooter(latestRevision, revisionNumber, version);
    }

    private void requireVersion(Long expectedVersion) {
        if (!Objects.equals(version, expectedVersion)) {
            throw new StoreFooterRuleException(StoreFooterRuleException.Reason.CONFLICT);
        }
    }
}
