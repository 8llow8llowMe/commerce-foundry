package com.commercefoundry.domainlayer.knowledge.application.command;

import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeLocale;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeType;

public record KnowledgeCreateCommand(KnowledgeType type, KnowledgeLocale locale, KnowledgeContentCommand content) {
}
