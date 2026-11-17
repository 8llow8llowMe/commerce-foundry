package com.commercefoundry.domainlayer.productdraftgeneration.application.exception;

public final class ProductDraftGenerationValidationMessage {
    public static final String DRAFT_ID = "PRODUCT_DRAFT_GENERATION_101:초안 식별자는 양수여야 합니다.";
    public static final String GENERATION_NO = "PRODUCT_DRAFT_GENERATION_111:생성 회차는 1 이상 3 이하여야 합니다.";
    public static final String INPUT_HASH = "PRODUCT_DRAFT_GENERATION_121:입력 해시는 소문자 SHA-256 형식이어야 합니다.";
    public static final String ACTOR = "PRODUCT_DRAFT_GENERATION_131:요청자 식별자는 양수여야 합니다.";
    public static final String EXPIRY = "PRODUCT_DRAFT_GENERATION_141:초안 만료 시각이 필요합니다.";
    public static final String JOB_ID = "PRODUCT_DRAFT_GENERATION_151:작업 식별자는 양수여야 합니다.";

    private ProductDraftGenerationValidationMessage() {
    }
}
