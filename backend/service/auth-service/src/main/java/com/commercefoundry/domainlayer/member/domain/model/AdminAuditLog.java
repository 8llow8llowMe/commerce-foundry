package com.commercefoundry.domainlayer.member.domain.model;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 저장된 관리자 감사 로그 한 건 (#192). 읽기 전용이다 — 쓰기는 {@code AdminAuditPort} 가 행위별 메서드로 한다.
 *
 * @param action 행위 이름 (ADMIN_LOGIN · MEMBER_SEARCH …)
 * @param actorMemberId 행위한 관리자. 모르면 null
 * @param target 대상. 없으면 null
 * @param result SUCCESS · FAILURE
 */
public record AdminAuditLog(long id, String action, Long actorMemberId, String target, String result, Instant createdAt) {

    private static final String MEMBER_TARGET_PREFIX = "member:";

    /** {@code target} 이 {@code member:<양수>} 일 때만 그 회원 아이디. 목록 조건 · 빈 값 · 0 · 앞자리 0 · 숫자가 아니면 null. */
    public Long memberTargetId() {
        return memberIdOfTarget(target);
    }

    /**
     * 한 페이지에서 이름을 붙일 회원 아이디. 행위자와 {@code member:<id>} 대상을 첫 등장 순서로 모으고 중복은 뺀다.
     * 양수가 아닌 행위자 · 회원 대상이 아닌 target 은 빠진다.
     */
    public static List<Long> referencedMemberIds(List<AdminAuditLog> logs) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (AdminAuditLog log : logs) {
            Long actorId = log.actorMemberId();
            if (actorId != null && actorId > 0) {
                ids.add(actorId);
            }
            Long targetId = log.memberTargetId();
            if (targetId != null) {
                ids.add(targetId);
            }
        }
        return List.copyOf(ids);
    }

    static Long memberIdOfTarget(String target) {
        if (target == null || !target.startsWith(MEMBER_TARGET_PREFIX)) {
            return null;
        }
        String raw = target.substring(MEMBER_TARGET_PREFIX.length());
        if (raw.isEmpty() || raw.charAt(0) == '0') {
            return null;
        }
        long value = 0;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch < '0' || ch > '9') {
                return null;
            }
            int digit = ch - '0';
            if (value > (Long.MAX_VALUE - digit) / 10) {
                return null;
            }
            value = value * 10 + digit;
        }
        return value;
    }
}
