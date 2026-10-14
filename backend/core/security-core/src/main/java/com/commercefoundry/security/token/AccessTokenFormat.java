package com.commercefoundry.security.token;

import java.util.List;

/**
 * access 토큰의 {@code typ}. 발급기와 검증기가 같은 상수를 본다 ({@code security-guide.md} 토큰 사양).
 * 역할 claim 이름은 {@code MemberRole#CLAIM_NAME} 이다.
 */
public final class AccessTokenFormat {

    /** RFC 9068 의 access 토큰 {@code typ}. 검증기는 이 값만 받는다 — 다른 용도의 JWT 가 access 토큰으로 쓰이지 않게. */
    public static final String TYPE = "at+jwt";

    /** RFC 7515 §4.1.9 — {@code application/} 접두어를 붙인 같은 값도 같은 타입으로 본다. */
    public static final List<String> ACCEPTED_TYPES = List.of(TYPE, "application/" + TYPE);

    private AccessTokenFormat() {
    }
}
