package com.commercefoundry.domainlayer.knowledge.application.command;

import com.commercefoundry.domainlayer.knowledge.domain.model.KnowledgeCategory;
import java.time.Instant;

/** 원문은 Processor의 검증 경계 안에서만 도메인 Content로 바꾼다. */
public record KnowledgeContentCommand(String title, String body, KnowledgeCategory category, Instant validFrom, Instant validTo) {

    @Override
    public String toString() {
        return "KnowledgeContentCommand[redacted]";
    }
}
