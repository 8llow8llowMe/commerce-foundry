package com.commercefoundry.security.internal;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 서비스 토큰(서비스 간 internal API 인증, 1단계)의 형식 상수. 발급기와 검증기가 같은 값을 본다 ({@code security-guide.md} §9).
 *
 * <p>헤더 {@code alg=HS256 · typ=svc+jwt}, claim {@code iss}(호출 서비스 ID) · {@code aud}(대상 서비스 ID) · {@code iat} · {@code exp} · {@code jti}.
 * 서비스 ID 는 Eureka 서비스 ID({@code spring.application.name})다.
 */
public final class InternalTokenFormat {

    /** JOSE {@code typ}. access 토큰({@code at+jwt})과 달라 한쪽 토큰이 다른 쪽 검증기를 통과하지 않는다. */
    public static final String TYPE = "svc+jwt";

    /** RFC 7515 §4.1.9 — {@code application/} 접두어를 붙인 같은 값도 같은 타입으로 본다. */
    public static final List<String> ACCEPTED_TYPES = List.of(TYPE, "application/" + TYPE);

    /**
     * 권한 이름 접두어. 호출 서비스 {@code order-service} 의 권한은 {@code SERVICE_order-service} 다.
     * 회원 역할({@code ROLE_})과 겹치지 않아 사용자 토큰으로는 이 권한을 얻을 수 없다.
     */
    public static final String AUTHORITY_PREFIX = "SERVICE_";

    /** 서비스 ID 모양 — 소문자 kebab, 63자 이하. 설정({@code security.internal.service-id}) · {@code iss} · {@code aud} 모두 이 모양만 받는다. */
    public static final Pattern SERVICE_ID = Pattern.compile("[a-z][a-z0-9-]{0,62}");

    /** 수신 쪽 시각 판정({@code exp} · {@code iat})의 시계 오차 허용. 서비스끼리는 같은 망의 시계를 쓰므로 access 토큰(60초)보다 작게 둔다. */
    public static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    /** 토큰 수명 상한. 발급 설정({@code security.internal.token-ttl})과 수신 쪽 검증({@code exp − iat})이 같이 쓴다. */
    public static final Duration MAX_TOKEN_TTL = Duration.ofMinutes(5);

    private InternalTokenFormat() {
    }

    public static boolean isServiceId(String value) {
        return value != null && SERVICE_ID.matcher(value).matches();
    }

    /** {@code @PreAuthorize("hasAuthority('SERVICE_order-service')")} 가 보는 권한 이름. */
    public static String authority(String serviceId) {
        return AUTHORITY_PREFIX + serviceId;
    }
}
