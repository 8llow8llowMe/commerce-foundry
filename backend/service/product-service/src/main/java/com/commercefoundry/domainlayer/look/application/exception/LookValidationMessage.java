package com.commercefoundry.domainlayer.look.application.exception;

/**
 * 룩 요청 DTO 의 필드별 검증 메시지 ({@code coding-conventions.md} §8-2). 접두어는 컨텍스트 단위 {@code LOOK_} 이고 101~ 을 쓴다
 * ({@code PRODUCT_1xx} 대역이 다 찼다 — {@link LookErrorCode}). 봉투는 서비스 공용 검증 처리({@code ProductExceptionHandler})가 만든다 —
 * 메시지 앞의 코드를 읽으므로 컨텍스트를 몰라도 된다. 폴백(역직렬화 실패)은 서비스 공용 {@code PRODUCT_100} 이다.
 */
public final class LookValidationMessage {

    // 어드민 룩 생성 · 수정 (POST /api/v1/admin/looks, PUT /api/v1/admin/looks/{lookId})
    public static final String TITLE_REQUIRED = "LOOK_101:title 은 필수입니다.";
    public static final String TITLE_SIZE_MAX = "LOOK_102:title 은 100자 이하여야 합니다.";
    public static final String TITLE_INVALID_CHARACTER = "LOOK_103:title 에는 제어 문자를 쓸 수 없습니다.";
    public static final String DESCRIPTION_SIZE_MAX = "LOOK_104:description 은 1000자 이하여야 합니다.";
    public static final String DESCRIPTION_INVALID_CHARACTER = "LOOK_105:description 에는 개행 · 탭 외의 제어 문자를 쓸 수 없습니다.";
    public static final String IMAGE_REQUIRED = "LOOK_106:image 는 필수입니다.";
    public static final String IMAGE_URL_REQUIRED = "LOOK_107:image.url 은 필수입니다.";
    public static final String IMAGE_URL_SIZE_MAX = "LOOK_108:image.url 은 500자 이하여야 합니다.";
    public static final String IMAGE_WIDTH_POSITIVE = "LOOK_109:image.width 는 양수여야 합니다.";
    public static final String IMAGE_HEIGHT_POSITIVE = "LOOK_110:image.height 는 양수여야 합니다.";
    public static final String IMAGE_ALT_TEXT_SIZE_MAX = "LOOK_111:image.altText 는 200자 이하여야 합니다.";
    public static final String IMAGE_ALT_TEXT_INVALID_CHARACTER = "LOOK_112:image.altText 에는 제어 문자를 쓸 수 없습니다.";
    public static final String VISIBLE_REQUIRED = "LOOK_113:visible 은 필수입니다.";
    public static final String PRODUCT_IDS_REQUIRED = "LOOK_114:productIds 는 필수입니다.";
    public static final String PRODUCT_IDS_SIZE = "LOOK_115:productIds 는 1~20개여야 합니다.";
    public static final String PRODUCT_ID_REQUIRED = "LOOK_116:productIds 의 원소는 비어 있을 수 없습니다.";
    public static final String PRODUCT_ID_POSITIVE = "LOOK_117:productIds 의 원소는 양수여야 합니다.";

    // 경로 변수 (/api/v1/admin/looks/{lookId})
    public static final String LOOK_ID_POSITIVE = "LOOK_118:lookId 는 양수여야 합니다.";

    // 어드민 룩 순서 변경 (PATCH /api/v1/admin/looks/order)
    public static final String LOOK_IDS_REQUIRED = "LOOK_119:lookIds 는 필수입니다.";
    public static final String LOOK_IDS_SIZE_MAX = "LOOK_120:lookIds 는 최대 100개입니다.";
    public static final String LOOK_ID_ELEMENT_REQUIRED = "LOOK_121:lookIds 의 원소는 비어 있을 수 없습니다.";
    public static final String LOOK_ID_ELEMENT_POSITIVE = "LOOK_122:lookIds 의 원소는 양수여야 합니다.";

    private LookValidationMessage() {
    }
}
