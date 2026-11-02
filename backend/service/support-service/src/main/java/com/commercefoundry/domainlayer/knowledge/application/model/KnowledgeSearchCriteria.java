package com.commercefoundry.domainlayer.knowledge.application.model;

import com.commercefoundry.domainlayer.knowledge.application.command.KnowledgeSearchCommand;
import com.commercefoundry.domainlayer.knowledge.application.exception.KnowledgeErrorCode;
import com.commercefoundry.domainlayer.knowledge.application.exception.KnowledgeException;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeCategory;
import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeLocale;
import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public record KnowledgeSearchCriteria(List<String> tokens, KnowledgeLocale locale, KnowledgeCategory category, int topN) {
    public KnowledgeSearchCriteria {
        if (tokens == null || tokens.isEmpty() || tokens.size() > 16 || locale == null || topN < 1 || topN > 20) {
            throw invalid();
        }
        if (tokens.stream().anyMatch(token -> token == null || token.isEmpty() || token.length() > 64)) {
            throw invalid();
        }
        tokens = List.copyOf(tokens);
    }

    public static KnowledgeSearchCriteria from(KnowledgeSearchCommand command) {
        if (command == null || command.question() == null || command.question().isBlank() || command.question().length() > 500) {
            throw invalid();
        }
        String normalized = Normalizer.normalize(command.question(), Normalizer.Form.NFKC);
        if (normalized.length() > 500) {
            throw invalid();
        }
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String token : normalized.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{Nd}]+")) {
            if (!token.isEmpty()) {
                if (token.length() > 64) {
                    throw invalid();
                }
                tokens.add(token);
                if (tokens.size() == 16) {
                    break;
                }
            }
        }
        return new KnowledgeSearchCriteria(List.copyOf(tokens), command.locale(), command.category(), command.topN());
    }

    private static KnowledgeException invalid() {
        return new KnowledgeException(KnowledgeErrorCode.INVALID_INPUT);
    }

    @Override
    public String toString() {
        return "KnowledgeSearchCriteria[tokens=<redacted>, locale=" + locale + ", category=" + category + ", topN=" + topN + "]";
    }
}
