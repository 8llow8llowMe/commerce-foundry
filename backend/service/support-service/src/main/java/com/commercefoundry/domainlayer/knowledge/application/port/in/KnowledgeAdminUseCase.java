package com.commercefoundry.domainlayer.knowledge.application.port.in;

import com.commercefoundry.domainlayer.knowledge.adapter.in.admin.dto.response.KnowledgeAdminResponses;
import com.commercefoundry.domainlayer.knowledge.application.command.KnowledgeContentCommand;
import com.commercefoundry.domainlayer.knowledge.application.command.KnowledgeCreateCommand;
import com.commercefoundry.domainlayer.knowledge.application.model.KnowledgeAdminQuery;
import com.commercefoundry.persistence.dto.PageResponse;

public interface KnowledgeAdminUseCase {

    PageResponse<KnowledgeAdminResponses.Summary> list(KnowledgeAdminQuery query);

    KnowledgeAdminResponses.Detail get(long documentId);

    KnowledgeAdminResponses.Detail create(KnowledgeCreateCommand command, long actor);

    KnowledgeAdminResponses.Detail edit(long documentId, int number, Long version, KnowledgeContentCommand content, long actor);

    KnowledgeAdminResponses.Detail createDraft(long documentId, Long documentVersion, KnowledgeContentCommand content, long actor);

    KnowledgeAdminResponses.Detail approve(long documentId, int number, Long version, long actor);

    KnowledgeAdminResponses.Detail publish(long documentId, int number, Long version, Long documentVersion, long actor);

    void archive(long documentId, int number, Long version, Long documentVersion, long actor);
}
