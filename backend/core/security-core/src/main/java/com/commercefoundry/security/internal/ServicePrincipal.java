package com.commercefoundry.security.internal;

import org.springframework.security.core.AuthenticatedPrincipal;

/**
 * 검증된 서비스 토큰의 주인 — internal API 를 부른 서비스. internal 컨트롤러는 허용 호출자를
 * {@code @PreAuthorize("hasAuthority('SERVICE_order-service')")} 로 선언하고, 필요하면 {@code @AuthenticationPrincipal ServicePrincipal caller} 로 받는다.
 *
 * <p>1단계(공용 키)에서 호출자는 "선언" 이다 — 키를 가진 서비스는 어느 {@code iss} 로도 서명할 수 있다. 2단계(서비스별 키 · mTLS)에서
 * 같은 주체 · 허용 목록이 "증명" 이 된다 ({@code security-guide.md} §9).
 *
 * @param serviceId 호출 서비스 ID ({@code iss})
 * @param tokenId   토큰 아이디 ({@code jti}). 로그 상관관계에 쓴다
 */
public record ServicePrincipal(
    String serviceId,
    String tokenId
) implements AuthenticatedPrincipal {

    /** {@code SERVICE_<service-id>}. */
    public String authority() {
        return InternalTokenFormat.authority(serviceId);
    }

    /** Spring Security 의 {@code Authentication#getName()} 이 이 값을 쓴다. */
    @Override
    public String getName() {
        return serviceId;
    }
}
