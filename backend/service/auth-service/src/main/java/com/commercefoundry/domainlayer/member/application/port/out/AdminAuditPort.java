package com.commercefoundry.domainlayer.member.application.port.out;

import com.commercefoundry.security.principal.MemberRole;
import java.util.OptionalLong;

/**
 * 관리자 감사 기록 (#6, 1단계). 지금은 구조화 로그 한 줄({@code admin.audit} 로거)이고, 영속 감사(테이블 · 보관 기간 · 조회 화면)는 후속이다 —
 * 그때 이 포트의 구현만 바꾼다 ({@code security-guide.md} §11-4).
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
}
