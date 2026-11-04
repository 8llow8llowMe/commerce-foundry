package com.commercefoundry.domainlayer.product.application.exception;

/** PRODUCT의 1xx 대역이 소진되어 편집 입력 검증은 PRODUCT_EDIT 접두어를 연다(coding-conventions §8-2). */
public final class ProductEditValidationMessage {
    public static final String VERSION_REQUIRED_OR_MIN = "PRODUCT_EDIT_101:version 은 필수이며 0 이상이어야 합니다.";

    public static final String OPTION_GROUP_ID_REQUIRED_OR_POSITIVE = "PRODUCT_EDIT_102:옵션 그룹 아이디는 필수이며 양수여야 합니다.";
    public static final String OPTION_VALUE_ID_REQUIRED_OR_POSITIVE = "PRODUCT_EDIT_103:옵션 값 아이디는 필수이며 양수여야 합니다.";

    // 맞춤 제작 설정 (#9 PR-B) — 등록 본문의 customOrder 와 PUT /custom-order 가 같은 문구를 쓴다
    public static final String CUSTOM_ORDER_ENABLED_REQUIRED = "PRODUCT_EDIT_104:맞춤 제작 사용 여부(enabled)는 필수입니다.";
    public static final String CUSTOM_ORDER_NOTICE_INVALID = "PRODUCT_EDIT_105:맞춤 제작 안내 문구는 500자 이하이며 제어 문자를 쓸 수 없습니다.";
    public static final String CUSTOM_ORDER_LEAD_TIME_RANGE = "PRODUCT_EDIT_106:맞춤 제작 소요일은 1~365일이어야 합니다.";
    public static final String CUSTOM_ORDER_FIELDS_SIZE = "PRODUCT_EDIT_107:맞춤 입력 항목은 0~20개여야 합니다.";
    public static final String CUSTOM_ORDER_FIELD_KEY_INVALID =
        "PRODUCT_EDIT_108:입력 항목 키는 영문 소문자로 시작하고 영문 소문자 · 숫자 · 밑줄로 30자 이내여야 합니다.";
    public static final String CUSTOM_ORDER_FIELD_LABEL_INVALID = "PRODUCT_EDIT_109:입력 항목 이름은 1~50자이며 제어 · 서식 문자를 쓸 수 없습니다.";
    public static final String CUSTOM_ORDER_FIELD_INPUT_TYPE_REQUIRED = "PRODUCT_EDIT_110:입력 유형(inputType)은 필수입니다.";
    public static final String CUSTOM_ORDER_FIELD_TEXT_INVALID = "PRODUCT_EDIT_111:단위는 10자, 도움말은 200자 이하이며 제어 · 서식 문자를 쓸 수 없습니다.";
    public static final String CUSTOM_ORDER_FIELD_REQUIRED_REQUIRED = "PRODUCT_EDIT_112:필수 입력 여부(required)는 필수입니다.";

    public static final String OPTION_GROUP_NAME_EN_INVALID = "PRODUCT_EDIT_113:옵션 그룹 영문명은 30자 이하이며 제어 · 서식 문자를 쓸 수 없습니다.";
    public static final String OPTION_VALUE_NAME_EN_INVALID = "PRODUCT_EDIT_114:옵션 값 영문명은 50자 이하이며 제어 · 서식 문자를 쓸 수 없습니다.";

    private ProductEditValidationMessage() {
    }
}
