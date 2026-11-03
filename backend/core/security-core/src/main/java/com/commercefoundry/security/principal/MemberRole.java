package com.commercefoundry.security.principal;

import java.util.Arrays;

/**
 * 회원 역할. access 토큰의 {@code role} claim 값이다. 기존 {@code ADMIN} 은 #236 이행 동안 유지하며
 * 기능 권한은 {@code OWNER} 와 같다. 새 운영자 역할은 PR-A 에서 해석만 하고 아직 발급하지 않는다.
 */
public enum MemberRole {

    CUSTOMER,
    ADMIN,
    OWNER,
    MANAGER,
    STAFF;

    /** access 토큰에서 역할을 싣는 claim 이름. 값은 이 enum 의 이름 하나다. */
    public static final String CLAIM_NAME = "role";

    private static final String AUTHORITY_PREFIX = "ROLE_";

    public String authority() {
        return AUTHORITY_PREFIX + name();
    }

    /** claim 값 그대로(대소문자 구분) 찾는다. 모르는 값이면 {@link IllegalArgumentException} — 역할을 추측해 올리지 않는다. */
    public static MemberRole fromClaim(String value) {
        return Arrays.stream(values())
            .filter(role -> role.name().equals(value))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("알 수 없는 역할입니다: " + value));
    }
}
