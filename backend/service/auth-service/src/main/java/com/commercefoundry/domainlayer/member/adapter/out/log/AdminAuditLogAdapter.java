package com.commercefoundry.domainlayer.member.adapter.out.log;

import com.commercefoundry.domainlayer.member.application.model.AdminAuditRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 관리자 감사 로그 한 줄 — 전용 로거 {@value #LOGGER_NAME} 에 INFO {@code key=value} 로 남긴다. 로그 수집기가 로거 이름으로 따로 모아
 * 보관 기간을 앱 로그와 다르게 둘 수 있게 이름을 나눈다. 형식의 정본은 {@code security-guide.md} §11-4 다.
 *
 * <pre>
 * action=ADMIN_LOGIN actorMemberId=- target=member:4611686018427387904 result=FAILURE
 * action=MEMBER_SEARCH actorMemberId=4611686018427387904 target=members?role=ALL&amp;page=0&amp;size=20 result=SUCCESS
 * </pre>
 *
 * <p>칸은 언제나 {@code action · actorMemberId · target · result} 넷이고 순서도 같다 — 모르는 칸은 {@code -}. 값에 공백 · 개인정보를 넣지 않는다
 * (이메일 · 이름 · 전화 원문, 비밀번호 · 토큰). 실패를 감사할 때 실패 이유(회원 없음 · 비밀번호 틀림 · 고객 계정)도 나누지 않는다.
 *
 * <p>#192 부터 {@code AdminAuditPort} 구현은 DB 어댑터({@code AdminAuditLogRepositoryAdapter})다. 그 어댑터가 DB 에 쓰기 전에 이 줄을 먼저 남긴다 —
 * DB 저장이 실패해도 이 줄이 증거로 남고, 다른 서비스(operations 등)와 같은 수집 형식이 유지된다. 이 클래스를 지우지 않는다.
 */
@Component
public class AdminAuditLogAdapter {

    public static final String LOGGER_NAME = "admin.audit";

    private static final Logger AUDIT = LoggerFactory.getLogger(LOGGER_NAME);
    private static final String NONE = "-";

    public void write(AdminAuditRecord record) {
        AUDIT.info("action={} actorMemberId={} target={} result={}",
            record.action(), orNone(record.actorMemberId()), orNone(record.target()), record.result());
    }

    private static String orNone(Object value) {
        return value == null ? NONE : String.valueOf(value);
    }
}
