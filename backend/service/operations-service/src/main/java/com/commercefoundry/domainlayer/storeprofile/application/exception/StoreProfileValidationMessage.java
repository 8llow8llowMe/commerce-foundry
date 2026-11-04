package com.commercefoundry.domainlayer.storeprofile.application.exception;

public final class StoreProfileValidationMessage {

    public static final String VERSION = "STORE_PROFILE_101:기존 버전은 0 이상이어야 합니다.";

    public static final String STORE_NAME = "STORE_PROFILE_102:쇼핑몰 이름의 필수 여부와 길이(최대 100자)를 확인해 주세요.";

    public static final String COMPANY_NAME = "STORE_PROFILE_103:상호의 필수 여부와 길이(최대 200자)를 확인해 주세요.";

    public static final String REPRESENTATIVE_NAME = "STORE_PROFILE_104:대표자의 필수 여부와 길이(최대 100자)를 확인해 주세요.";

    public static final String COUNTRY_CODE = "STORE_PROFILE_105:ISO 3166-1 alpha-2 국가 코드의 필수 여부와 길이(최대 2자)를 확인해 주세요.";

    public static final String BUSINESS_REGISTRATION_NUMBER = "STORE_PROFILE_106:사업자 식별 번호의 필수 여부와 길이(최대 100자)를 확인해 주세요.";

    public static final String MAIL_ORDER_REGISTRATION_NUMBER = "STORE_PROFILE_107:통신판매 신고 번호의 필수 여부와 길이(최대 100자)를 확인해 주세요.";

    public static final String ADDRESS = "STORE_PROFILE_108:공개 사업장 주소의 필수 여부와 길이(최대 500자)를 확인해 주세요.";

    public static final String CUSTOMER_SERVICE_EMAIL = "STORE_PROFILE_109:고객센터 이메일의 필수 여부와 길이(최대 254자)를 확인해 주세요.";

    public static final String CUSTOMER_SERVICE_PHONE = "STORE_PROFILE_110:고객센터 전화의 필수 여부와 길이(최대 50자)를 확인해 주세요.";

    public static final String CUSTOMER_SERVICE_HOURS = "STORE_PROFILE_111:고객센터 운영 시간의 필수 여부와 길이(최대 500자)를 확인해 주세요.";
    private StoreProfileValidationMessage() {
    }
}
