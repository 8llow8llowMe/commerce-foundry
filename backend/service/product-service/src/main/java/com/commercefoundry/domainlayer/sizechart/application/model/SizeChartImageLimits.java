package com.commercefoundry.domainlayer.sizechart.application.model;

/**
 * 사이즈표 초안 IMAGE 입력의 상한과 이미지 후보 아이디(sourceId) 모양의 단일 정본.
 * 진입 DTO 의 검증 어노테이션과 Processor 가 같은 값을 참조한다 — DTO 는 Processor 를 참조할 수 없어 application/model 에 둔다.
 *
 * <p>sourceId 는 Presenter 가 바꾸는 숫자 식별자가 아니라 이 컨텍스트가 발급하는 토큰이다 — 상품 이미지 {@code IMG-{imageId}},
 * 본문 이미지 {@code DESC-{주소 SHA-256 앞 32자}}.
 */
public final class SizeChartImageLimits {
    public static final int MAX_IMAGES = 4;
    public static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    public static final String IMAGE_SOURCE_PREFIX = "IMG-";
    public static final String DESCRIPTION_SOURCE_PREFIX = "DESC-";
    public static final String SOURCE_ID_PATTERN = IMAGE_SOURCE_PREFIX + "[1-9][0-9]{0,18}|" + DESCRIPTION_SOURCE_PREFIX + "[0-9a-f]{32}";

    private SizeChartImageLimits() {
    }
}
