package com.commercefoundry.domainlayer.legaldocument.application.exception;

public final class LegalDocumentValidationMessage {
    public static final String VERSION = "LEGAL_DOCUMENT_101:기존 버전은 0 이상이어야 합니다.";
    public static final String TITLE = "LEGAL_DOCUMENT_102:제목은 제어문자가 없는 1~200자여야 합니다.";
    public static final String BODY = "LEGAL_DOCUMENT_103:본문은 1~50000자이며 줄바꿈·탭 외 제어문자를 포함할 수 없습니다.";
    public static final String REVISION = "LEGAL_DOCUMENT_104:리비전 번호는 1 이상이어야 합니다.";
    public static final String REVIEW = "LEGAL_DOCUMENT_105:검토 확인이 필요합니다.";
    public static final String PAGE = "LEGAL_DOCUMENT_106:페이지는 0 이상, 크기는 1~20이어야 합니다.";

    private LegalDocumentValidationMessage() {
    }
}
