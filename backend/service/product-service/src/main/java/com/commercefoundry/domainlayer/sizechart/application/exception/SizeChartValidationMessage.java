package com.commercefoundry.domainlayer.sizechart.application.exception;

/**
 * 사이즈표 요청 검증 메시지 — {@code "PRODUCT_1xx:사용자 메시지"} ({@code coding-conventions.md} §8-2). 컨트롤러 · DTO 는 이 상수만 참조한다.
 *
 * <p>목록 page · size 는 서비스 공용 {@code PRODUCT_101} · {@code PRODUCT_102} 다 ({@code api-design-guide.md} §4).
 * 사이즈표 필드 검증은 170~197 이다 (상품 139 까지, 전시 140~165 — 그 사이 166~169 는 비워 둔다).
 * 대역 끝 198 · 199 는 {@code ProductErrorCode} 의 타입 불일치 · 필수 파라미터 누락이라 여기서 쓰지 않는다.
 * 이름 규칙은 카테고리 이름과 같다 — {@code strip()} 뒤 길이, 제어 문자({@code \p{Cc}}) · 서식 문자({@code \p{Cf}}) 금지. 판정기는 이 컨텍스트 안에 둔다.
 */
public final class SizeChartValidationMessage {

    public static final String PAGE_MIN = "PRODUCT_101:page 는 0 이상이어야 합니다.";
    public static final String SIZE_RANGE = "PRODUCT_102:size 는 1 이상 100 이하여야 합니다.";

    public static final String TEMPLATE_NAME_REQUIRED = "PRODUCT_170:name 은 필수입니다.";
    public static final String TEMPLATE_NAME_SIZE_MAX = "PRODUCT_171:name 은 앞뒤 공백을 뺀 뒤 50자 이하여야 합니다.";
    public static final String TEMPLATE_NAME_INVALID_CHARACTER = "PRODUCT_172:name 에는 제어 문자 · 서식 문자(개행 · 탭 · 제로폭 공백 등)를 쓸 수 없습니다.";
    public static final String BASE_UNIT_REQUIRED = "PRODUCT_173:baseUnit 은 필수입니다.";
    public static final String COLUMNS_SIZE = "PRODUCT_174:columns 는 1개 이상 20개 이하여야 합니다.";
    public static final String COLUMN_REQUIRED = "PRODUCT_175:columns 의 원소는 비어 있을 수 없습니다.";
    public static final String COLUMN_NAME_REQUIRED = "PRODUCT_176:columns.name 은 필수입니다.";
    public static final String COLUMN_NAME_SIZE_MAX = "PRODUCT_177:columns.name 은 앞뒤 공백을 뺀 뒤 30자 이하여야 합니다.";
    public static final String COLUMN_NAME_INVALID_CHARACTER = "PRODUCT_178:columns.name 에는 제어 문자 · 서식 문자를 쓸 수 없습니다.";
    public static final String COLUMN_DESCRIPTION_SIZE_MAX = "PRODUCT_179:description 은 앞뒤 공백을 뺀 뒤 200자 이하여야 합니다.";
    public static final String COLUMN_DESCRIPTION_INVALID_CHARACTER = "PRODUCT_180:description 에는 제어 문자 · 서식 문자를 쓸 수 없습니다.";
    public static final String COLUMN_DISPLAY_ORDER_REQUIRED = "PRODUCT_181:columns.displayOrder 는 필수입니다.";
    public static final String COLUMN_DISPLAY_ORDER_MIN = "PRODUCT_182:columns.displayOrder 는 0 이상이어야 합니다.";
    public static final String COLUMN_ID_POSITIVE = "PRODUCT_183:columnId 는 양수여야 합니다.";
    public static final String TEMPLATE_ID_POSITIVE = "PRODUCT_184:templateId 는 양수여야 합니다.";
    public static final String PRODUCT_ID_POSITIVE = "PRODUCT_185:productId 는 양수여야 합니다.";
    public static final String NOTE_SIZE_MAX = "PRODUCT_186:note 는 앞뒤 공백을 뺀 뒤 500자 이하여야 합니다.";
    public static final String NOTE_INVALID_CHARACTER = "PRODUCT_187:note 에는 제어 문자 · 서식 문자를 쓸 수 없습니다.";
    public static final String ROWS_SIZE = "PRODUCT_188:rows 는 1개 이상 30개 이하여야 합니다.";
    public static final String ROW_REQUIRED = "PRODUCT_189:rows 의 원소는 비어 있을 수 없습니다.";
    public static final String SIZE_LABEL_REQUIRED = "PRODUCT_190:sizeLabel 은 필수입니다.";
    public static final String SIZE_LABEL_SIZE_MAX = "PRODUCT_191:sizeLabel 은 앞뒤 공백을 뺀 뒤 20자 이하여야 합니다.";
    public static final String SIZE_LABEL_INVALID_CHARACTER = "PRODUCT_192:sizeLabel 에는 제어 문자 · 서식 문자를 쓸 수 없습니다.";
    public static final String ROW_DISPLAY_ORDER_REQUIRED = "PRODUCT_193:rows.displayOrder 는 필수입니다.";
    public static final String ROW_DISPLAY_ORDER_MIN = "PRODUCT_194:rows.displayOrder 는 0 이상이어야 합니다.";
    public static final String CELLS_REQUIRED = "PRODUCT_195:cells 는 필수입니다.";
    public static final String CELL_REQUIRED = "PRODUCT_196:cells 의 원소는 비어 있을 수 없습니다.";
    public static final String CELL_VALUE_RANGE = "PRODUCT_197:value 는 0 초과 9999.99 이하의 소수 둘째 자리까지여야 합니다.";

    private SizeChartValidationMessage() {
    }
}
