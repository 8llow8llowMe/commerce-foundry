package com.commercefoundry.domainlayer.knowledge.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum KnowledgeStatus implements CodeNameDescribable {

    DRAFT("초안"),
    APPROVED("승인"),
    PUBLISHED("게시"),
    ARCHIVED("보관");

    private final String displayName;
}
