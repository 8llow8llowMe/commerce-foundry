package com.commercefoundry.domainlayer.storenavigation.application.exception;

public final class StoreNavigationValidationMessage {
    public static final String VERSION = "STORE_NAV_101:버전은 0 이상이어야 합니다.";
    public static final String ITEMS = "STORE_NAV_102:메뉴 항목 목록은 필수이며 null 항목 없이 전체 최대 60개, 한 상위 항목 아래 최대 20개입니다.";
    public static final String KEY = "STORE_NAV_103:항목 key 는 영문 소문자·숫자로 시작하는 소문자·숫자·하이픈 1~40자이며 목록 안에서 유일해야 합니다.";
    public static final String PARENT = "STORE_NAV_104:상위 항목 key 는 목록에 있는 다른 항목이어야 합니다.";
    public static final String TYPE = "STORE_NAV_107:메뉴 유형이 올바르지 않습니다.";
    public static final String LABELS = "STORE_NAV_108:표시명은 두 언어 모두 제어문자 없는 1~40자여야 합니다.";
    public static final String URL = "STORE_NAV_109:INTERNAL 은 / 로 시작하는 내부 경로, EXTERNAL 은 허용된 HTTPS 주소여야 하며 CATEGORY·NONE 은 URL 을 넣지 않습니다.";
    public static final String CATEGORY = "STORE_NAV_110:CATEGORY 유형은 양수 카테고리 ID 가 필수이고 다른 유형은 카테고리 ID 를 넣지 않습니다.";
    public static final String ORDER = "STORE_NAV_111:표시 순서는 같은 상위 항목 아래에서 중복 없는 0~999여야 합니다.";
    public static final String VISIBLE = "STORE_NAV_112:노출 여부는 필수입니다.";

    private StoreNavigationValidationMessage() {
    }
}
