package com.commercefoundry.domainlayer.storebrand.application.service.processor;

import com.commercefoundry.domainlayer.storebrand.application.exception.BrandAssetException;
import com.commercefoundry.domainlayer.storebrand.application.exception.BrandAssetException.Code;
import com.commercefoundry.domainlayer.storebrand.application.info.BrandAssetInfo;
import com.commercefoundry.domainlayer.storebrand.application.port.out.BrandRevisionPort;
import com.commercefoundry.domainlayer.storebrand.domain.model.BrandAssets;
import com.commercefoundry.domainlayer.storebrand.domain.model.BrandRevision;
import com.commercefoundry.persistence.dto.PageResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BrandRevisionProcessor {
    private final BrandRevisionPort revisions;
    private final Clock clock;

    public BrandAssetInfo detail() {
        return revisions.latest().map(this::detailOf).orElseGet(() -> detailOf(null));
    }

    public BrandAssetInfo current(long expectedVersion) {
        var current = detail();
        if (current.version() != expectedVersion) {
            throw new BrandAssetException(Code.CONFLICT);
        }
        return current;
    }

    public BrandAssetInfo save(long actor, BrandAssetInfo current, BrandAssets assets) {
        long next = current.version() + 1;
        var revision = new BrandRevision(next, next, publicationVersion(current), null, actor, now(), assets);
        revisions.append(revision);
        return detailOf(revision);
    }

    public BrandAssetInfo publish(long actor, long expectedVersion, Long restore, BrandAssets assets) {
        var latest = revisions.latest().orElseThrow(() -> new BrandAssetException(Code.CONFLICT));
        if (latest.version() != expectedVersion) {
            throw new BrandAssetException(Code.CONFLICT);
        }
        long next = expectedVersion + 1;
        var revision = new BrandRevision(next, latest.draftVersion(), next, restore, actor, now(), assets);
        revisions.append(revision);
        return detailOf(revision);
    }

    public BrandRevision publication(long version) {
        return revisions.find(version).filter(revision -> Long.valueOf(version).equals(revision.publishedVersion()))
            .orElseThrow(() -> new BrandAssetException(Code.NOT_FOUND));
    }

    public Optional<BrandRevision> publication() {
        return revisions.latest().map(BrandRevision::publishedVersion).map(this::publication);
    }

    public PageResponse<BrandRevision> history(int page, int size) {
        if (page < 0 || size < 1 || size > 20 || (long) page * size > Integer.MAX_VALUE) {
            throw new BrandAssetException(Code.INVALID_REQUEST);
        }
        return revisions.publications(page, size);
    }

    private BrandAssetInfo detailOf(BrandRevision revision) {
        if (revision == null) {
            return new BrandAssetInfo(0, BrandAssets.EMPTY, null);
        }
        BrandAssets draft = revision.draftVersion() == revision.version()
            ? revision.assets() : revisions.find(revision.draftVersion()).orElseThrow().assets();
        Long publishedVersion = revision.publishedVersion();
        BrandRevision published = publishedVersion == null ? null
            : publishedVersion == revision.version() ? revision : publication(publishedVersion);
        return new BrandAssetInfo(revision.version(), draft, published);
    }

    private static Long publicationVersion(BrandAssetInfo current) {
        return current.publication() == null ? null : current.publication().version();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
