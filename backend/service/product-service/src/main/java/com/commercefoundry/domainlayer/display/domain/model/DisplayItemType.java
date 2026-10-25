package com.commercefoundry.domainlayer.display.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 전시 아이템 유형. 고객 응답에는 화면이 분기에만 쓰는 제어 코드라 문자열로 내리고({@code coding-conventions.md} §11 예외),
 * 어드민 응답에는 운영자에게 보여 주는 값이라 metadata({@code {code, name, description}})로 내린다. 요청은 코드 문자열로 받는다.
 */
@Getter
@RequiredArgsConstructor
public enum DisplayItemType implements CodeNameDescribable {

    /** 이미지 · 영상 한 개. */
    MEDIA("미디어", "이미지 또는 영상 한 개입니다."),

    /** 상품 하나 — 고객 응답에서는 상품 목록과 같은 카드로 펼친다. */
    PRODUCT("상품", "상품 하나입니다. 고객 메인에는 상품 목록과 같은 카드로 보입니다.");

    private final String displayName;
    private final String description;
}
