package com.commercefoundry.domainlayer.storebrand.domain.model;

import java.time.Instant;

public record BrandRevision(long version, long draftVersion, Long publishedVersion, Long restoredFromVersion,
                            long actorMemberId, Instant createdAt, BrandAssets assets) {
}
