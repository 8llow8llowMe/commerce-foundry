package com.commercefoundry.domainlayer.sizechartgeneration.application.exception;

public final class SizeChartGenerationValidationMessage {
    public static final String DRAFT_ID = "SIZE_CHART_GENERATION_101:초안 식별자는 양수여야 합니다.";
    public static final String INPUT_HASH = "SIZE_CHART_GENERATION_111:입력 해시는 소문자 SHA-256 형식이어야 합니다.";
    public static final String ACTOR = "SIZE_CHART_GENERATION_121:요청자 식별자는 양수여야 합니다.";
    public static final String EXPIRY = "SIZE_CHART_GENERATION_131:초안 만료 시각이 필요합니다.";
    public static final String JOB_ID = "SIZE_CHART_GENERATION_141:작업 식별자는 양수여야 합니다.";

    private SizeChartGenerationValidationMessage() {
    }
}
