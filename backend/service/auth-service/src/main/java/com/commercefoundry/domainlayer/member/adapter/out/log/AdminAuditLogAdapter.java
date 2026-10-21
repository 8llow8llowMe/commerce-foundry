package com.commercefoundry.domainlayer.member.adapter.out.log;

import com.commercefoundry.domainlayer.member.application.port.out.AdminAuditPort;
import com.commercefoundry.security.principal.MemberRole;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 관리자 감사 로그 (#6, 1단계) — 전용 로거 {@value #LOGGER_NAME} 에 INFO 한 줄 {@code key=value} 로 남긴다. 로그 수집기가 로거 이름으로 따로 모아
 * 보관 기간을 앱 로그와 다르게 둘 수 있게 이름을 나눈다. 형식의 정본은 {@code security-guide.md} §11-4 다.
 *
 * <pre>
 * action=ADMIN_LOGIN actorMemberId=- target=member:4611686018427387904 result=FAILURE
 * action=MEMBER_SEARCH actorMemberId=4611686018427387904 target=members?role=ALL&amp;page=0&amp;size=20 result=SUCCESS
 * </pre>
 *
 * <p>칸은 언제나 {@code action · actorMemberId · target · result} 넷이고 순서도 같다 — 모르는 칸은 {@code -}. 값에 공백 · 개인정보를 넣지 않는다
 * (이메일 · 이름 · 전화 원문, 비밀번호 · 토큰). 실패를 감사할 때 실패 이유(회원 없음 · 비밀번호 틀림 · 고객 계정)도 나누지 않는다.
 */
@Component
public class AdminAuditLogAdapter implements AdminAuditPort {

    public static final String LOGGER_NAME = "admin.audit";

    private static final Logger AUDIT = LoggerFactory.getLogger(LOGGER_NAME);
    private static final String NONE = "-";
    private static final String SUCCESS = "SUCCESS";
    private static final String FAILURE = "FAILURE";

    @Override
    public void adminLoginSucceeded(long adminMemberId) {
        write(AdminAuditAction.ADMIN_LOGIN, String.valueOf(adminMemberId), member(adminMemberId), SUCCESS);
    }

    @Override
    public void adminLoginFailed(OptionalLong targetAdminMemberId) {
        String target = targetAdminMemberId.isPresent() ? member(targetAdminMemberId.getAsLong()) : NONE;
        write(AdminAuditAction.ADMIN_LOGIN, NONE, target, FAILURE);
    }

    @Override
    public void membersSearched(long adminMemberId, MemberRole role, int page, int size) {
        String target = "members?role=" + (role == null ? "ALL" : role.name()) + "&page=" + page + "&size=" + size;
        write(AdminAuditAction.MEMBER_SEARCH, String.valueOf(adminMemberId), target, SUCCESS);
    }

    private static String member(long memberId) {
        return "member:" + memberId;
    }

    private static void write(AdminAuditAction action, String actorMemberId, String target, String result) {
        AUDIT.info("action={} actorMemberId={} target={} result={}", action, actorMemberId, target, result);
    }

    /** 감사 행위 이름. 로그 검색 키라 한번 정한 이름을 바꾸지 않는다. */
    enum AdminAuditAction {
        ADMIN_LOGIN,
        MEMBER_SEARCH
    }
}
