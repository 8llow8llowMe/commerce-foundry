package com.commercefoundry.domainlayer.product.application.exception;

public final class SkuAdminValidationMessage {
    public static final String VERSION = "SKU_ADMIN_101:version은 0 이상 필수입니다.";
    public static final String SKUS = "SKU_ADMIN_102:기존 SKU 전체를 1~100개 보내 주세요.";
    public static final String ID = "SKU_ADMIN_103:아이디는 양수 필수입니다.";
    public static final String PRICE_REQUIRED = "SKU_ADMIN_104:추가금은 0~100000000의 금액과 통화가 필수입니다.";
    public static final String STATUS = "SKU_ADMIN_105:판매 상태가 필수입니다.";
    public static final String WEIGHT = "SKU_ADMIN_106:배송 무게는 1g 이상입니다.";
    public static final String HS_CODE = "SKU_ADMIN_107:HS 코드는 구분자를 제외한 숫자 6~10자리입니다.";

    public static final String PRICE_AMOUNT = "SKU_ADMIN_108:추가금 금액은 0~100000000의 정수 필수입니다.";
    public static final String PRICE_CURRENCY = "SKU_ADMIN_109:추가금 통화가 필수입니다.";
    public static final String PRODUCT_ID = "SKU_ADMIN_110:상품 아이디는 양수입니다.";

    // 옵션 값 · 새 SKU 추가 (#9 PR-C1)
    public static final String NEW_OPTION_VALUES = "SKU_ADMIN_111:추가할 옵션 값은 0~50개입니다.";
    public static final String NEW_OPTION_VALUE_NAME = "SKU_ADMIN_112:옵션 값 이름은 1~50자이며 제어 · 서식 문자를 쓸 수 없습니다.";
    public static final String NEW_SKUS = "SKU_ADMIN_113:추가할 SKU 를 1~100개 보내 주세요.";
    public static final String OPTION_VALUE_NAMES = "SKU_ADMIN_114:SKU 의 옵션 값 이름을 옵션 순서대로 보내 주세요.";
    public static final String INITIAL_STOCK = "SKU_ADMIN_115:처음 재고는 재고 관리 여부와 0 이상의 수량을 모두 보내 주세요.";

    // 옵션 은퇴 (#9 PR-C2) — 111~115 는 #9 PR-C1 이 쓴다
    public static final String RETIREMENT_TARGETS = "SKU_ADMIN_116:은퇴할 옵션 · 값을 하나 이상, 각각 50개까지 보내 주세요.";

    private SkuAdminValidationMessage() {
    }
}
