package com.commercefoundry.domainlayer.knowledge.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum KnowledgeType implements CodeNameDescribable {

    FAQ("자주 묻는 질문"),
    POLICY("정책"),
    TEMPLATE("응답 템플릿");

    private final String displayName;
}
