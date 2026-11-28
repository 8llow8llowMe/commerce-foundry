package com.commercefoundry.domainlayer.settlement.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 불일치 처리 상태. 전이는 {@link SettlementMismatch} 의 도메인 메서드로만 일어난다. */
@Getter
@RequiredArgsConstructor
public enum SettlementMismatchStatus implements CodeNameDescribable {

    OPEN("미해결", "아직 어긋나 있습니다."),
    RESOLVED("해결", "더는 어긋나지 않거나 처리를 마쳤습니다.");

    private final String displayName;
    private final String description;
}
