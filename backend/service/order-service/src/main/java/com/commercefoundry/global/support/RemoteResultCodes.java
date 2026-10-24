package com.commercefoundry.global.support;

import java.util.regex.Pattern;

/**
 * 서비스 간 호출에서 원격이 보낸 {@code resultCode} 를 로그에 넣을 값으로 바꾼다 — catalog(product-service) · order(delivery-service) 의 응답 support 가 함께 쓴다 (#281).
 * 원격의 임의 문자열(CR/LF 로 로그 줄 위조 · 긴 값)을 로그에 넣지 않는다 ({@code coding-conventions.md} §8 · §10).
 */
public final class RemoteResultCodes {

    /** 코드 모양이 아닌 resultCode 를 대신하는 값. */
    public static final String UNRECOGNIZED = "<unrecognized>";

    /**
     * 로그에 그대로 남기는 resultCode 의 모양 — common-core {@code ValidationErrorSupport} 의 코드 판정({@code DOMAIN_NNN})과 같다.
     * 그쪽 판정은 private 이라 가져다 쓰지 않고 같은 식을 둔다.
     */
    private static final Pattern RESULT_CODE_SHAPE = Pattern.compile("[A-Z][A-Z0-9_]*_[0-9]{3}");
    private static final int MAX_LOGGED_LENGTH = 40;

    private RemoteResultCodes() {
    }

    /**
     * 코드 모양({@code [A-Z][A-Z0-9_]*_[0-9]{3}}, 40자 이하)일 때만 그대로이고, 아니면(CR/LF · 긴 값 · 임의 문자열) {@value #UNRECOGNIZED} 다.
     * 코드가 없으면(본문 없음 · JSON 아님) null 그대로다 — 원격 문자열이 아니다.
     */
    public static String loggable(String resultCode) {
        if (resultCode == null) {
            return null;
        }
        return resultCode.length() <= MAX_LOGGED_LENGTH && RESULT_CODE_SHAPE.matcher(resultCode).matches() ? resultCode : UNRECOGNIZED;
    }
}
