package com.commercefoundry.common.support.archfixture.ok.domainlayer.goods.application.exception;

/** 검증 메시지 상수. 컨트롤러가 쓸 수 있다 — H1 허용. 인라인되지 않게 메서드로도 노출해 실제 의존을 만든다. */
public final class GoodsValidationMessage {

    public static final String KEYWORD_REQUIRED = "GOODS_101:검색어를 입력해 주세요";

    private GoodsValidationMessage() {
    }

    public static String keywordRequired() {
        return KEYWORD_REQUIRED;
    }
}
