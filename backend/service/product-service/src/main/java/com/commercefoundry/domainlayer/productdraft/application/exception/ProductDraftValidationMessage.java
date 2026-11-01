package com.commercefoundry.domainlayer.productdraft.application.exception;

/**
 * AI 상품 초안 요청 DTO 의 필드별 검증 메시지 ({@code coding-conventions.md} §8-2). 접두어는 컨텍스트 단위 {@code PRODUCT_DRAFT_} 이고 101~ 을 쓴다
 * ({@link ProductDraftErrorCode}). 봉투는 서비스 공용 검증 처리({@code ProductExceptionHandler})가 만든다 — 메시지 앞의 코드를 읽는다.
 *
 * <p>문구 칸은 보이지 않거나 줄을 나누는 문자를 받지 않는다(치수 메모만 개행 · 탭 허용) — 입력은 AI 프롬프트의 데이터 칸으로 들어가므로,
 * 한 줄 칸이 여러 줄이 되거나 화면에 안 보이는 지시가 실리면 데이터 칸 경계를 흉내 낼 수 있다. 막는 것({@link #FORBIDDEN}):
 * 유니코드 제어 문자 {@code Cc}(C0 · DEL · C1 — U+0085 NEL 포함), 짝 없는 서로게이트 {@code Cs}, 줄 · 문단 구분자 U+2028 · U+2029,
 * 방향 제어 문자(U+200E · U+200F · U+202A~U+202E · U+2066~U+2069 — 검토 화면에서 문구를 뒤집어 보이게 한다), 태그 문자(U+E0000~U+E007F — 보이지 않는 글자).
 * 이모지에 쓰는 ZWJ(U+200D) · 변형 선택자는 막지 않는다.
 */
public final class ProductDraftValidationMessage {

    /** 막는 문자 집합 (문자 클래스 안쪽) — 클래스 javadoc. */
    private static final String FORBIDDEN =
        "\\p{Cc}\\p{Cs}\\x{2028}\\x{2029}\\x{200E}\\x{200F}\\x{202A}-\\x{202E}\\x{2066}-\\x{2069}\\x{E0000}-\\x{E007F}";
    /** 한 줄 문구 — {@link #FORBIDDEN} 금지(개행 · 탭도 Cc 라 막힌다). */
    public static final String SINGLE_LINE = "[^" + FORBIDDEN + "]*";
    /** 여러 줄 문구 — 개행({@code \n} · {@code \r}) · 탭만 허용하고 나머지 {@link #FORBIDDEN} 금지. */
    public static final String MULTI_LINE = "(?:[\\t\\n\\r]|[^" + FORBIDDEN + "])*";

    // 어드민 초안 생성 (POST /api/v1/admin/product-drafts)
    public static final String NAME_HINT_SIZE_MAX = "PRODUCT_DRAFT_101:nameHint 는 100자 이하여야 합니다.";
    public static final String NAME_HINT_INVALID_CHARACTER = "PRODUCT_DRAFT_102:nameHint 에는 제어 · 방향 · 보이지 않는 문자를 쓸 수 없습니다.";
    public static final String CATEGORY_ID_POSITIVE = "PRODUCT_DRAFT_103:categoryId 는 양수여야 합니다.";
    public static final String MATERIALS_SIZE_MAX = "PRODUCT_DRAFT_104:materials 는 최대 10개입니다.";
    public static final String MATERIAL_INVALID = "PRODUCT_DRAFT_105:materials 의 원소는 1~100자 평문이어야 합니다.";
    public static final String CARE_NOTES_SIZE_MAX = "PRODUCT_DRAFT_106:careNotes 는 최대 10개입니다.";
    public static final String CARE_NOTE_INVALID = "PRODUCT_DRAFT_107:careNotes 의 원소는 1~100자 평문이어야 합니다.";
    public static final String MEASUREMENTS_NOTE_SIZE_MAX = "PRODUCT_DRAFT_108:measurementsNote 는 1000자 이하여야 합니다.";
    public static final String MEASUREMENTS_NOTE_INVALID_CHARACTER = "PRODUCT_DRAFT_109:measurementsNote 에는 개행 · 탭 외의 제어 · 방향 · 보이지 않는 문자를 쓸 수 없습니다.";
    public static final String ORIGIN_SIZE_MAX = "PRODUCT_DRAFT_110:origin 은 100자 이하여야 합니다.";
    public static final String ORIGIN_INVALID_CHARACTER = "PRODUCT_DRAFT_111:origin 에는 제어 · 방향 · 보이지 않는 문자를 쓸 수 없습니다.";
    public static final String CERTIFICATIONS_SIZE_MAX = "PRODUCT_DRAFT_112:certifications 는 최대 10개입니다.";
    public static final String CERTIFICATION_INVALID = "PRODUCT_DRAFT_113:certifications 의 원소는 1~100자 평문이어야 합니다.";
    public static final String TONE_SIZE_MAX = "PRODUCT_DRAFT_114:tone 은 200자 이하여야 합니다.";
    public static final String TONE_INVALID_CHARACTER = "PRODUCT_DRAFT_115:tone 에는 제어 · 방향 · 보이지 않는 문자를 쓸 수 없습니다.";
    public static final String MUST_INCLUDE_SIZE_MAX = "PRODUCT_DRAFT_116:mustInclude 는 최대 10개입니다.";
    public static final String MUST_INCLUDE_INVALID = "PRODUCT_DRAFT_117:mustInclude 의 원소는 1~100자 평문이어야 합니다.";
    public static final String MUST_AVOID_SIZE_MAX = "PRODUCT_DRAFT_118:mustAvoid 는 최대 10개입니다.";
    public static final String MUST_AVOID_INVALID = "PRODUCT_DRAFT_119:mustAvoid 의 원소는 1~100자 평문이어야 합니다.";
    public static final String IMAGES_SIZE_MAX = "PRODUCT_DRAFT_120:images 는 최대 8장입니다.";
    public static final String IMAGE_REQUIRED = "PRODUCT_DRAFT_121:images 의 원소는 비어 있을 수 없습니다.";
    public static final String IMAGE_URL_REQUIRED = "PRODUCT_DRAFT_122:images.url 은 필수입니다.";
    public static final String IMAGE_URL_SIZE_MAX = "PRODUCT_DRAFT_123:images.url 은 500자 이하여야 합니다.";
    public static final String IMAGE_TYPE_REQUIRED = "PRODUCT_DRAFT_124:images.imageType 은 필수입니다.";
    public static final String IMAGE_WIDTH_POSITIVE = "PRODUCT_DRAFT_125:images.width 는 양수여야 합니다.";
    public static final String IMAGE_HEIGHT_POSITIVE = "PRODUCT_DRAFT_126:images.height 는 양수여야 합니다.";

    // 경로 변수 (/api/v1/admin/product-drafts/{draftId})
    public static final String DRAFT_ID_POSITIVE = "PRODUCT_DRAFT_127:draftId 는 양수여야 합니다.";

    private ProductDraftValidationMessage() {
    }
}
