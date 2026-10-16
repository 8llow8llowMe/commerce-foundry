package com.commercefoundry.domainlayer.product.application.exception;

/**
 * 상품 요청 검증 메시지 — {@code "PRODUCT_1xx:사용자 메시지"} ({@code coding-conventions.md} §8-2). 컨트롤러 · DTO 는 이 상수만 참조한다.
 * 대역 끝 198 · 199 는 {@link ProductErrorCode} 의 타입 불일치 · 필수 파라미터 누락이다.
 */
public final class ProductValidationMessage {

    public static final String PAGE_MIN = "PRODUCT_101:page 는 0 이상이어야 합니다.";
    public static final String SIZE_RANGE = "PRODUCT_102:size 는 1 이상 100 이하여야 합니다.";
    public static final String IDS_REQUIRED = "PRODUCT_103:ids 는 필수입니다.";
    public static final String IDS_SIZE_MAX = "PRODUCT_104:ids 는 최대 100개입니다.";
    public static final String ID_POSITIVE = "PRODUCT_105:id 는 양수여야 합니다.";

    private ProductValidationMessage() {
    }
}
