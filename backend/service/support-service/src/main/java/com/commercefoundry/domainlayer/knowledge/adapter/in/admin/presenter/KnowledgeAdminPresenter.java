package com.commercefoundry.domainlayer.knowledge.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.knowledge.adapter.in.admin.dto.response.KnowledgeAdminResponses;
import com.commercefoundry.domainlayer.knowledge.application.info.KnowledgeAdminInfo;
import com.commercefoundry.domainlayer.knowledge.application.info.KnowledgeSummaryInfo;
import com.commercefoundry.persistence.dto.PageResponse;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeAdminPresenter {

    public KnowledgeAdminResponses.Detail toResponse(KnowledgeAdminInfo info) {
        var document = info.document();
        var revision = info.revision();
        var content = revision.content();
        return new KnowledgeAdminResponses.Detail(
            Long.toString(document.id()), Long.toString(revision.id()), document.type().toMetadata(), document.locale().toMetadata(),
            document.latestRevision(), document.publishedRevision(), revision.revisionNumber(), document.version(), revision.version(),
            content.title(), content.body(), content.category().toMetadata(), revision.status().toMetadata(), content.validFrom(), content.validTo(),
            revision.approvedBy() == null ? null : Long.toString(revision.approvedBy()), revision.approvedAt(), revision.publishedAt());
    }

    public PageResponse<KnowledgeAdminResponses.Summary> toPage(Page<KnowledgeSummaryInfo> infos) {
        return PageResponse.of(infos.map(info -> new KnowledgeAdminResponses.Summary(
            Long.toString(info.documentId()), info.type().toMetadata(), info.locale().toMetadata(), info.latestRevision(), info.publishedRevision(),
            info.documentVersion(), info.revisionVersion(), info.title(), info.category().toMetadata(), info.status().toMetadata(), info.validFrom(), info.validTo(),
            info.approvedBy() == null ? null : Long.toString(info.approvedBy()), info.approvedAt(), info.publishedAt())));
    }
}
