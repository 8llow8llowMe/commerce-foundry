package com.commercefoundry.domainlayer.knowledge.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum KnowledgeLocale implements CodeNameDescribable {

    KO("한국어"),
    EN("영어");

    private final String displayName;
}
