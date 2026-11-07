package com.commercefoundry.domainlayer.storefooter.application.exception;

public final class StoreFooterValidationMessage {
    public static final String VERSION = "STORE_FOOTER_101:버전은 0 이상이어야 합니다.";
    public static final String COPYRIGHT = "STORE_FOOTER_102:저작권 문구는 언어별 null 또는 제어문자 없는 1~200자여야 합니다.";
    public static final String LINKS = "STORE_FOOTER_103:링크 목록은 필수이며 null 항목 없이 최대 20개입니다.";
    public static final String TYPE = "STORE_FOOTER_104:링크 유형이 올바르지 않습니다.";
    public static final String LABELS = "STORE_FOOTER_105:표시명은 두 언어 모두 제어문자 없는 1~80자여야 합니다.";
    public static final String URL = "STORE_FOOTER_106:허용된 HTTPS 링크를 입력해 주세요.";
    public static final String ORDER = "STORE_FOOTER_107:표시 순서는 중복 없는 0~999여야 합니다.";
    public static final String VISIBLE = "STORE_FOOTER_108:노출 여부는 필수입니다.";
    public static final String REVISION = "STORE_FOOTER_109:리비전 번호는 양수여야 합니다.";
    public static final String PAGE = "STORE_FOOTER_100:페이지는 0 이상, 크기는 1~20이어야 합니다.";

    private StoreFooterValidationMessage() {
    }
}
