package com.commercefoundry.domainlayer.display.application.exception;

/**
 * 메인 전시 요청 DTO 의 필드별 검증 메시지 ({@code coding-conventions.md} §8-2). 접두어는 서비스 단위 {@code PRODUCT_} 이고 전시는 140~ 을 쓴다
 * (101~139 는 {@code ProductValidationMessage}, 198 · 199 는 서비스 공용 형식 · 필수 파라미터). 새 번호를 더할 때 두 클래스가 겹치지 않게 한다.
 * 봉투는 서비스 공용 검증 처리({@code ProductExceptionHandler})가 만든다 — 메시지 앞의 코드를 읽으므로 컨텍스트를 몰라도 된다.
 */
public final class DisplayValidationMessage {

    // 어드민 전시 섹션 생성 · 수정 (POST /api/v1/admin/displays/sections, PUT /api/v1/admin/displays/sections/{sectionId})
    public static final String SECTION_TYPE_REQUIRED = "PRODUCT_140:sectionType 은 필수입니다.";
    public static final String TITLE_SIZE_MAX = "PRODUCT_141:title 은 200자 이하여야 합니다.";
    public static final String SUBTITLE_SIZE_MAX = "PRODUCT_142:subtitle 은 200자 이하여야 합니다.";
    public static final String BODY_SIZE_MAX = "PRODUCT_143:body 는 2000자 이하여야 합니다.";
    public static final String LINK_URL_SIZE_MAX = "PRODUCT_144:linkUrl 은 500자 이하여야 합니다.";
    public static final String LINK_LABEL_SIZE_MAX = "PRODUCT_145:linkLabel 은 50자 이하여야 합니다.";
    public static final String VISIBLE_REQUIRED = "PRODUCT_146:visible 은 필수입니다.";
    public static final String ITEMS_REQUIRED = "PRODUCT_147:items 는 필수입니다 (아이템이 없으면 빈 배열).";
    public static final String ITEMS_SIZE_MAX = "PRODUCT_148:items 는 최대 30개입니다.";
    public static final String ITEM_REQUIRED = "PRODUCT_149:items 의 원소는 비어 있을 수 없습니다.";
    public static final String ITEM_TYPE_REQUIRED = "PRODUCT_150:itemType 은 필수입니다.";
    public static final String MEDIA_TYPE_REQUIRED = "PRODUCT_151:mediaType 은 필수입니다.";
    public static final String MEDIA_PC_URL_REQUIRED = "PRODUCT_152:pcUrl 은 필수입니다.";
    public static final String MEDIA_URL_SIZE_MAX = "PRODUCT_153:미디어 주소는 500자 이하여야 합니다.";
    public static final String MEDIA_ALT_TEXT_SIZE_MAX = "PRODUCT_154:altText 는 200자 이하여야 합니다.";
    public static final String ITEM_PRODUCT_ID_POSITIVE = "PRODUCT_155:productId 는 양수여야 합니다.";
    public static final String ITEM_CAPTION_SIZE_MAX = "PRODUCT_156:caption 은 200자 이하여야 합니다.";
    public static final String ITEM_MEDIA_SHAPE_INVALID = "PRODUCT_157:itemType 이 MEDIA 면 media 가 필수이고 productId 는 보낼 수 없습니다.";
    public static final String ITEM_PRODUCT_SHAPE_INVALID = "PRODUCT_158:itemType 이 PRODUCT 면 productId 가 필수이고 media 는 보낼 수 없습니다.";
    public static final String MEDIA_POSTER_SHAPE_INVALID = "PRODUCT_159:VIDEO 는 pcPosterUrl 이 필수이고, IMAGE 는 포스터를 보낼 수 없습니다.";
    public static final String TEXT_INVALID_CHARACTER = "PRODUCT_164:문구에는 제어 문자를 쓸 수 없습니다 (body 만 개행 · 탭 허용).";
    public static final String PERIOD_OUT_OF_RANGE = "PRODUCT_165:startAt · endAt 은 0001-01-01T00:00:00Z 이상 9999-12-31T23:59:59.999999Z 이하여야 합니다.";

    // 경로 변수 (/api/v1/admin/displays/sections/{sectionId})
    public static final String SECTION_ID_POSITIVE = "PRODUCT_160:sectionId 는 양수여야 합니다.";

    // 어드민 전시 섹션 순서 변경 (PATCH /api/v1/admin/displays/sections/order)
    public static final String SECTION_IDS_REQUIRED = "PRODUCT_161:sectionIds 는 필수입니다.";
    public static final String SECTION_IDS_SIZE_MAX = "PRODUCT_162:sectionIds 는 최대 50개입니다.";
    public static final String SECTION_IDS_POSITIVE = "PRODUCT_163:sectionIds 의 원소는 양수여야 합니다.";

    // 저장하지 않는 전시 전체 초안 (POST /api/v1/admin/displays/preview)
    public static final String PREVIEW_SECTIONS_REQUIRED = "PRODUCT_166:sections 는 필수입니다 (섹션이 없으면 빈 배열).";
    public static final String PREVIEW_SECTIONS_SIZE_MAX = "PRODUCT_167:sections 는 최대 50개입니다.";
    public static final String PREVIEW_SECTION_REQUIRED = "PRODUCT_168:sections 의 원소는 비어 있을 수 없습니다.";

    private DisplayValidationMessage() {
    }
}
