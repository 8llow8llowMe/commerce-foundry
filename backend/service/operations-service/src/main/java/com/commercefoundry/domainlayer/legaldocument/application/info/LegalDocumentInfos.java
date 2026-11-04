package com.commercefoundry.domainlayer.legaldocument.application.info;

import com.commercefoundry.domainlayer.legaldocument.domain.model.LegalDocument;
import com.commercefoundry.domainlayer.legaldocument.domain.model.LegalDocumentRevision;
import java.time.Instant;
import java.util.List;

public final class LegalDocumentInfos {
    private LegalDocumentInfos() {
    }

    public record Detail(LegalDocument document, LegalDocumentRevision revision) {
    }

    public record Published(String type, String locale, int revisionNumber, String title, String body, Instant publishedAt) {
    }

    public record Template(String templateId, int version, String type, String locale, String title, String body,
                           List<String> checklist, String guidance) {
    }
}
