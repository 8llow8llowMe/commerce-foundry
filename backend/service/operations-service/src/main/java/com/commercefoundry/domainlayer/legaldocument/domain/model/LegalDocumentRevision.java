package com.commercefoundry.domainlayer.legaldocument.domain.model;

import java.time.Instant;

public record LegalDocumentRevision(
    long id, long documentId, int revisionNumber, String title, String body, long createdBy, Instant createdAt,
    Long publishedBy, Instant publishedAt
) {
    public LegalDocumentRevision publish(boolean reviewConfirmed, long actorMemberId, Instant now) {
        if (publishedAt != null) {
            return this;
        }
        if (!reviewConfirmed) {
            throw new LegalDocumentRuleException(LegalDocumentRuleException.Reason.REVIEW_REQUIRED);
        }
        if (title.contains("{{") || title.contains("}}") || body.contains("{{") || body.contains("}}")) {
            throw new LegalDocumentRuleException(LegalDocumentRuleException.Reason.UNRESOLVED_PLACEHOLDER);
        }
        return new LegalDocumentRevision(id, documentId, revisionNumber, title, body, createdBy, createdAt, actorMemberId, now);
    }
}
