package com.commercefoundry.domainlayer.look.domain.model;

/**
 * 룩(Shop the Look)의 상한 — 요청 검증 · domain 불변식 · 컬럼 길이가 같은 값을 본다.
 *
 * <p>룩 수 상한은 전체 잠금 · 전체 읽기(어드민 목록 · 순서 변경)의 비용을 작게 묶는다. 고객 화면은 페이지로 읽는다.
 */
public final class LookLimits {

    /** 룩 수 상한. 넘기면 생성이 409 {@code LOOK_002} 다. */
    public static final int MAX_LOOK_COUNT = 100;

    /** 룩 하나의 구성 상품 수 상한 (하한은 1). */
    public static final int MAX_ITEM_COUNT = 20;

    public static final int TITLE_MAX_LENGTH = 100;

    public static final int DESCRIPTION_MAX_LENGTH = 1000;

    public static final int ALT_TEXT_MAX_LENGTH = 200;

    public static final int URL_MAX_LENGTH = 500;

    private LookLimits() {
    }
}
