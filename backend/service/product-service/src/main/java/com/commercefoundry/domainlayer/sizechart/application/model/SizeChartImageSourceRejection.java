package com.commercefoundry.domainlayer.sizechart.application.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 사이즈표 초안에 쓸 수 없는 이미지 후보의 사유. 운영자에게 보이는 업무 문구만 담는다. */
@Getter
@RequiredArgsConstructor
public enum SizeChartImageSourceRejection implements CodeNameDescribable {
    LEGACY_IMAGE("이전 쇼핑몰에서 옮긴 이미지", "관리 정보가 없는 이미지라 사용할 수 없습니다. 상품 이미지로 다시 등록한 뒤 선택해 주세요."),
    EXTERNAL_IMAGE("외부 주소 이미지", "우리 저장소 밖의 이미지는 사용할 수 없습니다. 상품 이미지로 다시 등록한 뒤 선택해 주세요."),
    OTHER_PRODUCT_IMAGE("다른 상품의 이미지", "다른 상품에 등록된 이미지는 사용할 수 없습니다. 이 상품의 이미지로 다시 등록해 주세요.");

    private final String displayName;
    private final String description;
}
