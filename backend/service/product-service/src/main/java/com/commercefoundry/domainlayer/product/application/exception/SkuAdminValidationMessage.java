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

    private SkuAdminValidationMessage() {
    }
}
