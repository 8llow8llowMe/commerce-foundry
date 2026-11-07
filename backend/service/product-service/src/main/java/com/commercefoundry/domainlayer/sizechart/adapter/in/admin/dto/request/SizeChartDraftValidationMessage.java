package com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.request;

public final class SizeChartDraftValidationMessage {
    public static final String PRODUCT_ID = "SIZE_CHART_DRAFT_101:상품 아이디는 양수여야 합니다.";
    public static final String TEMPLATE_ID = "SIZE_CHART_DRAFT_102:템플릿 아이디는 양수여야 합니다.";
    public static final String MODE = "SIZE_CHART_DRAFT_103:텍스트 입력만 사용할 수 있습니다.";
    public static final String SOURCE = "SIZE_CHART_DRAFT_104:치수 텍스트를 1~12000자로 입력해 주세요.";
    public static final String DRAFT_ID = "SIZE_CHART_DRAFT_105:초안 아이디는 양수여야 합니다.";
    public static final String VERSION = "SIZE_CHART_DRAFT_106:검토한 초안 버전을 입력해 주세요.";
    public static final String CHART = "SIZE_CHART_DRAFT_107:수정한 사이즈표를 입력해 주세요.";
    public static final String ACK = "SIZE_CHART_DRAFT_108:확인한 경고 아이디를 올바르게 입력해 주세요.";
    public static final String PAGE = "SIZE_CHART_DRAFT_109:페이지는 0 이상이어야 합니다.";
    public static final String SIZE = "SIZE_CHART_DRAFT_110:페이지 크기는 1~100이어야 합니다.";
    public static final String KEY = "SIZE_CHART_DRAFT_111:Idempotency-Key 헤더에 UUID를 입력해 주세요.";

    private SizeChartDraftValidationMessage() {
    }
}
