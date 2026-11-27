package com.commercefoundry.domainlayer.legaldraftgeneration.application.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 검토용 후보의 문서 유형. 코드는 legaldocument 의 문서 유형 이름과 같다. */
@Getter
@RequiredArgsConstructor
public enum LegalDraftDocumentType implements CodeNameDescribable {
    TERMS("이용약관", "스토어 이용약관 검토용 후보입니다."),
    PRIVACY("개인정보처리방침", "개인정보처리방침 검토용 후보입니다.");

    private final String displayName;
    private final String description;
}
