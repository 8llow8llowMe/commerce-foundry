package com.commercefoundry.domainlayer.knowledge.application.exception;

public final class KnowledgeValidationMessage {

    public static final String TYPE = "SUPPORT_101:문서 유형은 필수입니다.";
    public static final String LOCALE = "SUPPORT_102:언어는 필수입니다.";
    public static final String CONTENT = "SUPPORT_103:내용은 필수입니다.";
    public static final String TITLE = "SUPPORT_104:제목은 공백이 아닌 200자 이내입니다.";
    public static final String BODY = "SUPPORT_105:본문은 공백이 아닌 8000자 이내입니다.";
    public static final String CATEGORY = "SUPPORT_106:카테고리는 필수입니다.";
    public static final String VALID_FROM = "SUPPORT_107:유효 시작 시각은 필수입니다.";
    public static final String PERIOD = "SUPPORT_108:유효 종료는 시작 시각보다 뒤여야 합니다.";
    public static final String REVISION = "SUPPORT_109:리비전 번호는 양수입니다.";
    public static final String VERSION = "SUPPORT_110:리비전 버전은 0 이상 필수입니다.";
    public static final String DOCUMENT_VERSION = "SUPPORT_111:문서 버전은 0 이상 필수입니다.";
    public static final String DOCUMENT_ID = "SUPPORT_112:문서 아이디는 양수입니다.";
    public static final String PAGE = "SUPPORT_113:페이지는 0 이상입니다.";
    public static final String SIZE = "SUPPORT_114:페이지 크기는 1부터 100입니다.";

    private KnowledgeValidationMessage() {
    }
}
