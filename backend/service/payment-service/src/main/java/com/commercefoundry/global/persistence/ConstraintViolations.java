package com.commercefoundry.global.persistence;

import java.util.Locale;
import org.hibernate.exception.ConstraintViolationException;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * 저장 예외가 어느 제약을 위반했는지 — 예외 메시지가 아니라 제약 이름으로 가린다 ({@code coding-conventions.md} §9-1). payment-service 의 결제 시도 어댑터가 쓴다.
 * order-service 와 같은 판정이다 — persistence-core 로 올리기 전까지 양쪽을 같이 고친다.
 *
 * <p>이름은 원인 사슬의 PostgreSQL 서버 오류 필드({@code PSQLException#getServerErrorMessage().getConstraint()})를 먼저 본다 — Hibernate 의
 * {@code getConstraintName()} 은 오류 메시지 문구에서 이름을 뽑아 서버의 {@code lc_messages} 로케일(한국어 등)에 따라 비어 있을 수 있다.
 * 서버 필드가 없으면(H2 등) Hibernate 의 이름을 쓴다. 비교는 대소문자 · 스키마 접두사 · 따옴표를 무시한 <b>같은 이름</b>이다.
 */
public final class ConstraintViolations {

    private ConstraintViolations() {
    }

    /**
     * 위반한 제약이 {@code constraintName} 인가. 이름은 {@link #constraintNameOf} 하나로 정한 뒤 정확히 비교한다 — 서버 필드가 있으면 Hibernate 이름은 보지 않는다.
     * (order-service 복사본은 원인마다 두 이름을 모두 비교한다 — #247 에서 이 방식으로 맞춘다)
     */
    public static boolean violates(Throwable exception, String constraintName) {
        return constraintName.toLowerCase(Locale.ROOT).equals(constraintNameOf(exception));
    }

    /**
     * 위반한 제약 이름 — 원인 사슬 전체에서 서버 필드를 먼저 찾고, 없으면 Hibernate 이름을 쓴다. 소문자로 바꾼 뒤 따옴표 · 스키마 접두사를 뗀다.
     * 예상 밖 위반을 다시 던질 때 메시지에도 싣는다.
     *
     * @return 이름을 알 수 없으면 null
     */
    public static String constraintNameOf(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof PSQLException psql && serverConstraintOf(psql) != null) {
                return normalize(serverConstraintOf(psql));
            }
        }
        for (Throwable cause = exception; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
                return normalize(violation.getConstraintName());
            }
        }
        return null;
    }

    private static String serverConstraintOf(PSQLException exception) {
        ServerErrorMessage serverError = exception.getServerErrorMessage();
        return serverError == null ? null : serverError.getConstraint();
    }

    private static String normalize(String actual) {
        String name = actual.toLowerCase(Locale.ROOT).replace("\"", "");
        return name.substring(name.lastIndexOf('.') + 1);
    }
}
