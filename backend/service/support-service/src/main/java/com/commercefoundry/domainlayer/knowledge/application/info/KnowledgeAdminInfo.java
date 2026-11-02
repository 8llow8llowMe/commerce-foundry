package com.commercefoundry.domainlayer.knowledge.application.info;

import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeDocument;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeRevision;

public record KnowledgeAdminInfo(KnowledgeDocument document, KnowledgeRevision revision) {
}
