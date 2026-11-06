package com.commercefoundry.global.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 운영자 역할 나누기 ({@code auth.operator-roles.*}, #236). 기본값은 {@code application.yml} 에 둔다.
 *
 * <p>어드민 화면(#237)이 아직 ADMIN 으로 세션을 판정하므로, 새 운영자 역할(OWNER · MANAGER · STAFF)을 주는 요청 — 역할을 지정한 운영자 추가와 역할 변경 — 은
 * 이 플래그가 켜져야 받는다. 꺼져 있으면 409 {@code AUTH_049} 다. 역할 없는 운영자 추가(ADMIN) · 상태 변경 · 목록은 플래그와 무관하다.
 * 화면 전환 뒤 켜고, 기존 ADMIN 을 OWNER 로 옮긴 다음 후속에서 플래그를 지운다.
 *
 * @param issuanceEnabled 새 운영자 역할 발급 · 역할 변경을 받는가. 기본 false
 */
@ConfigurationProperties(prefix = "auth.operator-roles")
public record OperatorRoleProperties(boolean issuanceEnabled) {
}
