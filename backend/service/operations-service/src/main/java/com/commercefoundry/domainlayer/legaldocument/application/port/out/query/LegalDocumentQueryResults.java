package com.commercefoundry.domainlayer.legaldocument.application.port.out.query;

import com.commercefoundry.domainlayer.legaldocument.domain.model.LegalDocument;
import com.commercefoundry.domainlayer.legaldocument.domain.model.LegalDocumentRevision;
import java.time.Instant;

public final class LegalDocumentQueryResults {
    private LegalDocumentQueryResults() {
    }

    public record Detail(LegalDocument document, LegalDocumentRevision revision) {
    }

    public record Published(String type, String locale, int revisionNumber, String title, String body, Instant publishedAt) {
    }
}
