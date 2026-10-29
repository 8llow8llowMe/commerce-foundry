package com.commercefoundry.domainlayer.member.application.port.out;

import com.commercefoundry.security.principal.MemberRole;
import java.util.OptionalLong;

/**
 * 관리자 감사 기록 (#6). #192 부터 구현은 {@code AdminAuditLogRepositoryAdapter} 다 — 구조화 로그 한 줄({@code admin.audit} 로거)을 먼저 남기고
 * {@code auth.admin_audit_log} 에 행을 더한다 ({@code security-guide.md} §11-4).
 *
 * <p>이메일 · 이름 · 전화 원문과 비밀번호 · 토큰을 넘기지 않는다. 남기는 것은 행위 · 행위자 memberId · 대상 · 결과뿐이다.
 * 감사 기록 실패가 업무 응답을 바꾸지 않게 구현은 예외를 던지지 않는다.
 */
public interface AdminAuditPort {

    /** 관리자 로그인 성공 — {@code AdminAuthWebFacade#login}. */
    void adminLoginSucceeded(long adminMemberId);

    /**
     * 관리자 로그인 실패 — 행위자를 모른다(이메일 원문도 남기지 않는다). 응답은 이유를 나누지 않지만 감사 로그에는 <b>노린 관리자 계정</b>을 남긴다 —
     * 한 관리자 계정에 몰리는 비밀번호 대입을 찾을 수 있게. 회원 없음 · 고객 계정이면 대상이 없다(고객 memberId 는 남기지 않는다).
     *
     * @param targetAdminMemberId 입력한 이메일이 관리자 계정이면 그 memberId, 아니면 빈 값
     */
    void adminLoginFailed(OptionalLong targetAdminMemberId);

    /**
     * 어드민 회원 목록 조회 — {@code MemberAdminFacade#searchMembers}. 마스킹했어도 개인정보를 읽는 행위라 누가 어느 페이지를 봤는지 남긴다.
     *
     * @param role 역할 조건. 없으면 null(전체)
     */
    void membersSearched(long adminMemberId, MemberRole role, int page, int size);

    /** 관리자 계정을 추가했다 (#193). 만들기 전에 거절되면 {@code createdMemberId} 는 null 이고 실패다. */
    void adminAccountCreated(long actorMemberId, Long createdMemberId, boolean succeeded);

    /** 관리자 계정을 비활성화했다 (#193). */
    void adminAccountDeactivated(long actorMemberId, long targetMemberId, boolean succeeded);

    /** 로그인한 관리자가 자기 비밀번호를 바꿨다 (#193). */
    void adminPasswordChanged(long adminMemberId, boolean succeeded);

    /** 로그인한 관리자가 OTP 등록을 확인했다 (#238). 비밀 · 코드 · 복구 코드 · 비밀번호는 넘기지 않는다. */
    void adminOtpEnrolled(long adminMemberId, boolean succeeded);

    /** 로그인한 관리자가 자기 OTP 를 해제했다 (#238). */
    void adminOtpDisabled(long adminMemberId, boolean succeeded);

    /** 로그인한 관리자가 OTP 복구 코드를 다시 발급했다 (#238). */
    void adminOtpRecoveryCodesRegenerated(long adminMemberId, boolean succeeded);
}
