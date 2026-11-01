package com.commercefoundry.domainlayer.productdraft.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 초안에 아직 없는 상품 등록 필수 입력 — 서버가 운영자 입력으로 계산한다(AI 출력이 아니다). 가격 · 옵션 · 재고는 초안이 담지 않고 적용 단계에서
 * 운영자가 넣는다 — 상품 등록 검증(#9)이 그때 본다.
 */
@Getter
@RequiredArgsConstructor
public enum ProductDraftRequiredInput implements CodeNameDescribable {

    PRODUCT_NAME("상품명", "상품명 후보를 입력해 주세요."),
    CATEGORY("카테고리", "상품 카테고리를 골라 주세요."),
    THUMBNAIL_IMAGE("대표 이미지", "목록에 보일 대표 이미지를 한 장 지정해 주세요.");

    private final String displayName;
    private final String description;
}
