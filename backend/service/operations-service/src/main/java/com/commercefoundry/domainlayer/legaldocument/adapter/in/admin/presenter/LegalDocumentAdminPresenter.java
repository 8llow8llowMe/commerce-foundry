package com.commercefoundry.domainlayer.legaldocument.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.legaldocument.adapter.in.admin.dto.response.LegalDocumentAdminResponses;
import com.commercefoundry.domainlayer.legaldocument.application.info.LegalDocumentInfos;
import com.commercefoundry.domainlayer.legaldocument.domain.model.LegalDocument;
import com.commercefoundry.domainlayer.legaldocument.domain.model.LegalDocumentRevision;
import org.springframework.stereotype.Component;

@Component
public class LegalDocumentAdminPresenter {
    public LegalDocumentAdminResponses.Summary summary(LegalDocument document) {
        return new LegalDocumentAdminResponses.Summary(document.key().type().name(), document.key().locale(),
            document.latestRevision(), document.publishedRevision(), document.version());
    }

    public LegalDocumentAdminResponses.Revision revision(LegalDocumentRevision revision) {
        return new LegalDocumentAdminResponses.Revision(revision.revisionNumber(), revision.title(), revision.body(),
            Long.toString(revision.createdBy()), revision.createdAt(), revision.publishedBy() == null ? null : revision.publishedBy().toString(),
            revision.publishedAt());
    }

    public LegalDocumentAdminResponses.Detail detail(LegalDocumentInfos.Detail info) {
        return new LegalDocumentAdminResponses.Detail(summary(info.document()), revision(info.revision()));
    }

    public LegalDocumentAdminResponses.Template template(LegalDocumentInfos.Template info) {
        return new LegalDocumentAdminResponses.Template(info.templateId(), info.version(), info.type(), info.locale(),
            info.title(), info.body(), info.checklist(), info.guidance());
    }
}
