package com.commercefoundry.domainlayer.product.application.exception;

/** PRODUCT의 1xx 대역이 소진되어 편집 입력 검증은 PRODUCT_EDIT 접두어를 연다(coding-conventions §8-2). */
public final class ProductEditValidationMessage {
    public static final String VERSION_REQUIRED_OR_MIN = "PRODUCT_EDIT_101:version 은 필수이며 0 이상이어야 합니다.";

    private ProductEditValidationMessage() {
    }
}
