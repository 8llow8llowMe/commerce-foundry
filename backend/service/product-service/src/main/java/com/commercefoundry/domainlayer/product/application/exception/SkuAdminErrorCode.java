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
    RETIRED_OPTION_ON_SALE("SKU_ADMIN_006", "은퇴한 옵션의 SKU는 판매중으로 바꿀 수 없습니다.", HttpStatus.BAD_REQUEST),
    /** 옵션 값 추가 (#9 PR-C1) — 없는 · 은퇴한 · 다른 상품의 그룹, 그룹 안에서 이미 쓴 이름(은퇴 값 포함), 그룹당 50개 초과. 메시지에 이유를 싣는다. */
    OPTION_VALUE_INVALID("SKU_ADMIN_007", "추가할 옵션 값을 확인해 주세요: %s", HttpStatus.BAD_REQUEST),
    /** 새 SKU 의 조합 (#9 PR-C1) — 그룹마다 값 하나가 아니다, 없는 · 은퇴한 값, 옵션 없는 상품, 은퇴한 옵션이 있는 상품. */
    COMBINATION_INVALID("SKU_ADMIN_008", "새 SKU 의 옵션 조합을 확인해 주세요: %s", HttpStatus.BAD_REQUEST),
    /** 이미 있는(판매 종료 포함) 또는 요청 안에서 겹친 조합 (#9 PR-C1). */
    COMBINATION_DUPLICATED("SKU_ADMIN_009", "이미 있는 옵션 조합입니다: %s", HttpStatus.BAD_REQUEST),
    /** 상품당 SKU 상한(등록과 같은 100개) (#9 PR-C1). */
    SKU_LIMIT_EXCEEDED("SKU_ADMIN_010", "SKU 는 상품당 100개까지 둘 수 있습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
