package com.commercefoundry.domainlayer.knowledge.application.model;

import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeLocale;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeType;

public record KnowledgeAdminQuery(KnowledgeType type, KnowledgeLocale locale, int page, int size) {
}
