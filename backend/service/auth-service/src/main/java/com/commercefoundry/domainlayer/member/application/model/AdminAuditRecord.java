package com.commercefoundry.domainlayer.member.application.model;

import com.commercefoundry.security.principal.MemberRole;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * 감사 한 건의 네 칸 — {@code action · actorMemberId · target · result} ({@code security-guide.md} §11-4). 행위마다 칸을 채우는 규칙을 여기 한 곳에 두어
 * 로그 한 줄과 DB 행이 같은 값을 남기게 한다. 개인정보(이메일 · 이름 · 전화 원문)와 비밀번호 · 토큰을 담지 않는다.
 *
 * @param actorMemberId 행위한 관리자. 모르면 null (로그인 실패)
 * @param target 대상 리소스 · 조건. 없으면 null. 공백을 넣지 않는다
 */
public record AdminAuditRecord(AdminAuditAction action, Long actorMemberId, String target, boolean succeeded) {

    public AdminAuditRecord {
        Objects.requireNonNull(action, "action");
    }

    public static AdminAuditRecord adminLoginSucceeded(long adminMemberId) {
        return new AdminAuditRecord(AdminAuditAction.ADMIN_LOGIN, adminMemberId, member(adminMemberId), true);
    }

    /** 노린 관리자 계정만 대상으로 남긴다 — 회원 없음 · 고객 계정이면 대상이 없다. */
    public static AdminAuditRecord adminLoginFailed(OptionalLong targetAdminMemberId) {
        String target = targetAdminMemberId.isPresent() ? member(targetAdminMemberId.getAsLong()) : null;
        return new AdminAuditRecord(AdminAuditAction.ADMIN_LOGIN, null, target, false);
    }

    /** @param role 역할 조건. 없으면 null(전체) */
    public static AdminAuditRecord membersSearched(long adminMemberId, MemberRole role, int page, int size) {
        String target = "members?role=" + (role == null ? "ALL" : role.name()) + "&page=" + page + "&size=" + size;
        return new AdminAuditRecord(AdminAuditAction.MEMBER_SEARCH, adminMemberId, target, true);
    }

    /**
     * DB 에 행으로 남길 기록인가. 행위자도 대상도 없는 기록(없는 이메일 · 고객 계정으로 관리자 로그인 실패)은 누구에 대한 기록도 아니고 인증 전 요청이라
     * 반복으로 행을 끝없이 늘릴 수 있어 로그 한 줄만 남긴다.
     */
    public boolean persistable() {
        return actorMemberId != null || target != null;
    }

    /** {@code SUCCESS} · {@code FAILURE} — 실패 이유는 나누지 않는다. */
    public String result() {
        return succeeded ? "SUCCESS" : "FAILURE";
    }

    private static String member(long memberId) {
        return "member:" + memberId;
    }
}
