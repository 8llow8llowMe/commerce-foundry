package com.commercefoundry.domainlayer.sizechart.adapter.in.admin.dto.request;

import com.commercefoundry.domainlayer.sizechart.application.model.SizeChartImageLimits;

public final class SizeChartDraftValidationMessage {
    public static final String PRODUCT_ID = "SIZE_CHART_DRAFT_101:상품 아이디는 양수여야 합니다.";
    public static final String TEMPLATE_ID = "SIZE_CHART_DRAFT_102:템플릿 아이디는 양수여야 합니다.";
    public static final String MODE = "SIZE_CHART_DRAFT_103:입력 방식은 TEXT 또는 IMAGE만 사용할 수 있습니다.";
    public static final String SOURCE = "SIZE_CHART_DRAFT_104:치수 텍스트를 1~12000자로 입력해 주세요.";
    public static final String DRAFT_ID = "SIZE_CHART_DRAFT_105:초안 아이디는 양수여야 합니다.";
    public static final String VERSION = "SIZE_CHART_DRAFT_106:검토한 초안 버전을 입력해 주세요.";
    public static final String CHART = "SIZE_CHART_DRAFT_107:수정한 사이즈표를 입력해 주세요.";
    public static final String ACK = "SIZE_CHART_DRAFT_108:확인한 경고 아이디를 올바르게 입력해 주세요.";
    public static final String PAGE = "SIZE_CHART_DRAFT_109:페이지는 0 이상이어야 합니다.";
    public static final String SIZE = "SIZE_CHART_DRAFT_110:페이지 크기는 1~100이어야 합니다.";
    public static final String KEY = "SIZE_CHART_DRAFT_111:Idempotency-Key 헤더에 UUID를 입력해 주세요.";
    public static final String IMAGES = "SIZE_CHART_DRAFT_112:사이즈표 이미지를 겹치지 않게 1~4장 선택해 주세요.";
    public static final String IMAGE_SOURCE_ID = "SIZE_CHART_DRAFT_113:이미지 아이디가 올바르지 않습니다. 이미지 목록에서 다시 선택해 주세요.";
    public static final String IMAGE_HASH = "SIZE_CHART_DRAFT_114:이미지를 확인한 뒤 받은 확인값을 그대로 보내 주세요.";
    public static final String PERSONAL_INFO = "SIZE_CHART_DRAFT_115:이미지에 고객 개인정보가 없는지 확인해 주세요.";
    public static final String SOURCE_NOT_ALLOWED = "SIZE_CHART_DRAFT_116:이미지 입력에는 치수 텍스트를 함께 보내지 않습니다.";
    public static final String IMAGES_NOT_ALLOWED = "SIZE_CHART_DRAFT_117:텍스트 입력에는 이미지를 함께 보내지 않습니다.";

    /** 이미지 후보 아이디 모양. 정본은 {@link SizeChartImageLimits#SOURCE_ID_PATTERN}. */
    public static final String IMAGE_SOURCE_ID_PATTERN = SizeChartImageLimits.SOURCE_ID_PATTERN;

    private SizeChartDraftValidationMessage() {
    }
}
