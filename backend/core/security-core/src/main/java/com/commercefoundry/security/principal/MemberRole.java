package com.commercefoundry.security.principal;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

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

    /**
     * 운영자 역할 — 어드민 콘솔에 들어올 수 있다. 어드민 URL 층(게이트웨이 · 서비스 체인)은 이 역할 전체를 통과시키고,
     * 기능 단위 판정은 메서드의 {@code AdminAuthority} 식이 맡는다 ({@code security-guide.md} §3-1).
     */
    public static final Set<MemberRole> OPERATOR_ROLES = Collections.unmodifiableSet(EnumSet.of(ADMIN, OWNER, MANAGER, STAFF));

    /** 소유자 역할 — 운영자 계정 관리의 마지막 보루(마지막 관리자 가드)와 시스템 알림 수신자다. 기존 ADMIN 은 OWNER 와 같다. */
    public static final Set<MemberRole> OWNER_ROLES = Collections.unmodifiableSet(EnumSet.of(ADMIN, OWNER));

    private static final String AUTHORITY_PREFIX = "ROLE_";

    public String authority() {
        return AUTHORITY_PREFIX + name();
    }

    /** 운영자 역할인가 ({@link #OPERATOR_ROLES}). */
    public boolean isOperator() {
        return OPERATOR_ROLES.contains(this);
    }

    /** 소유자 역할인가 ({@link #OWNER_ROLES}). */
    public boolean isOwner() {
        return OWNER_ROLES.contains(this);
    }

    /** {@code hasAnyRole(...)} 에 넘길 운영자 역할 이름 — {@code ROLE_} 접두사 없이, enum 선언 순서로. */
    public static String[] operatorRoleNames() {
        return OPERATOR_ROLES.stream().map(Enum::name).toArray(String[]::new);
    }

    /** claim 값 그대로(대소문자 구분) 찾는다. 모르는 값이면 {@link IllegalArgumentException} — 역할을 추측해 올리지 않는다. */
    public static MemberRole fromClaim(String value) {
        return Arrays.stream(values())
            .filter(role -> role.name().equals(value))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("알 수 없는 역할입니다: " + value));
    }
}
