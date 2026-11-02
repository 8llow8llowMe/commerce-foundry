package com.commercefoundry.domainlayer.knowledge.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum KnowledgeCategory implements CodeNameDescribable {

    SHIPPING("배송"),
    SHIPPING_FEE("배송비"),
    INTERNATIONAL_SHIPPING("해외배송"),
    EXCHANGE_REFUND("교환환불"),
    SIZE("사이즈"),
    MATERIAL("소재"),
    PRODUCTION_TIME("제작기간"),
    CUSTOMIZATION("맞춤제작"),
    OTHER("기타");

    private final String displayName;
}
