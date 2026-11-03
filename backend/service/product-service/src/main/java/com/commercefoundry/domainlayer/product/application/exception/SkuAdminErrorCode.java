package com.commercefoundry.domainlayer.product.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum SkuAdminErrorCode {
    PRODUCT_NOT_FOUND("SKU_ADMIN_001", "존재하지 않는 상품입니다.", HttpStatus.NOT_FOUND),
    VERSION_CONFLICT("SKU_ADMIN_002", "상품이 변경되었습니다. 다시 조회한 뒤 시도해 주세요.", HttpStatus.CONFLICT),
    SKU_SET_INVALID("SKU_ADMIN_003", "기존 SKU 아이디 전체를 중복 없이 보내 주세요.", HttpStatus.BAD_REQUEST),
    PRICE_INVALID("SKU_ADMIN_004", "SKU 가격 설정이 올바르지 않습니다: %s", HttpStatus.BAD_REQUEST),
    LOCK_CONFLICT("SKU_ADMIN_005", "상품을 변경 중입니다. 다시 조회한 뒤 시도해 주세요.", HttpStatus.CONFLICT),
    RETIRED_OPTION_ON_SALE("SKU_ADMIN_006", "은퇴한 옵션의 SKU는 판매중으로 바꿀 수 없습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
