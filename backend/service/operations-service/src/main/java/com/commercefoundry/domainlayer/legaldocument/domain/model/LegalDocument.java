package com.commercefoundry.domainlayer.legaldocument.domain.model;

import java.util.Objects;

public record LegalDocument(long id, LegalDocumentKey key, int latestRevision, Integer publishedRevision, Long version) {
    public LegalDocument append(Long expectedVersion) {
        requireVersion(expectedVersion);
        return new LegalDocument(id, key, Math.addExact(latestRevision, 1), publishedRevision, version);
    }

    public LegalDocument publish(Long expectedVersion, int revisionNumber) {
        if (Objects.equals(publishedRevision, revisionNumber)) {
            return this;
        }
        requireVersion(expectedVersion);
        if (revisionNumber != latestRevision) {
            throw new LegalDocumentRuleException(LegalDocumentRuleException.Reason.CONFLICT);
        }
        return new LegalDocument(id, key, latestRevision, revisionNumber, version);
    }

    private void requireVersion(Long expectedVersion) {
        if (!Objects.equals(version, expectedVersion)) {
            throw new LegalDocumentRuleException(LegalDocumentRuleException.Reason.CONFLICT);
        }
    }
}
