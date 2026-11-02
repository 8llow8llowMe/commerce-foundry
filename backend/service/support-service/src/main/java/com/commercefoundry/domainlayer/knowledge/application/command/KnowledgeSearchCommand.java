package com.commercefoundry.domainlayer.knowledge.application.command;

import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeCategory;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeLocale;

public record KnowledgeSearchCommand(String question, KnowledgeLocale locale, KnowledgeCategory category, int topN) {
    @Override
    public String toString() {
        return "KnowledgeSearchCommand[question=<redacted>, locale=" + locale + ", category=" + category + ", topN=" + topN + "]";
    }
}
