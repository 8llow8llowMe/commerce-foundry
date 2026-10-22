package com.commercefoundry.global.persistence;

import java.util.Locale;
import org.hibernate.exception.ConstraintViolationException;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * 저장 예외가 어느 제약을 위반했는지 — 예외 메시지가 아니라 제약 이름으로 가린다 ({@code coding-conventions.md} §9-1). order-service 의 장바구니 · 주문 어댑터가 같이 쓴다.
 *
 * <p>이름은 원인 사슬의 PostgreSQL 서버 오류 필드({@code PSQLException#getServerErrorMessage().getConstraint()})를 먼저 본다 — Hibernate 의
 * {@code getConstraintName()} 은 오류 메시지 문구에서 이름을 뽑아 서버의 {@code lc_messages} 로케일(한국어 등)에 따라 비어 있을 수 있다.
 * 서버 필드가 없으면(H2 등) Hibernate 의 이름을 쓴다. 비교는 대소문자 · 스키마 접두사 · 따옴표를 무시한 <b>같은 이름</b>이다.
 */
public final class ConstraintViolations {

    private ConstraintViolations() {
    }

    /** 원인 사슬에 {@code constraintName} 제약 위반이 있는가. */
    public static boolean violates(Throwable exception, String constraintName) {
        String expected = constraintName.toLowerCase(Locale.ROOT);
        for (Throwable cause = exception; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof PSQLException psql && matches(serverConstraintOf(psql), expected)) {
                return true;
            }
            if (cause instanceof ConstraintViolationException violation && matches(violation.getConstraintName(), expected)) {
                return true;
            }
        }
        return false;
    }

    private static String serverConstraintOf(PSQLException exception) {
        ServerErrorMessage serverError = exception.getServerErrorMessage();
        return serverError == null ? null : serverError.getConstraint();
    }

    private static boolean matches(String actual, String expected) {
        if (actual == null) {
            return false;
        }
        String name = actual.toLowerCase(Locale.ROOT).replace("\"", "");
        return name.equals(expected) || name.endsWith("." + expected);
    }
}
