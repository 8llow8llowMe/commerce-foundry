package com.commercefoundry.common.exception;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 로그 전용 사본 — 원래 예외 사슬의 <b>타입 · 스택 프레임</b>만 남기고 메시지를 버린다 ({@code coding-conventions.md} §8, #280).
 * 메시지에는 DB 행 값(DETAIL) · 서비스 간 호출의 대상 응답 본문 · 요청 값이 들어갈 수 있다. 프레임(클래스 · 메서드 · 줄 번호)은 코드 위치뿐이라
 * 처리되지 않은 500 을 어디서 났는지 추적하는 데 필요한 정보는 그대로다.
 *
 * <p>로그 출력은 {@code ...RedactedThrowable: <원래 예외 클래스 FQCN>} 이다 — logback 은 예외의 클래스 이름과 {@code getMessage()} 를 찍으므로
 * 원래 타입을 메시지 자리에 둔다. {@link SQLException} 은 {@code [sqlState=23505]} 처럼 SQLState(값이 아닌 표준 코드)를 덧붙인다.
 * 원인(cause) · 억제된 예외(suppressed)도 같은 방식으로 바꾸고, 순환 사슬은 한 번만 따라간다.
 *
 * <p>응답에 싣지 않는다 — 로그 인자로만 넘긴다: {@code log.error("... code={}", code, RedactedThrowable.of(exception))}.
 */
public final class RedactedThrowable extends Throwable {

    private static final Pattern SQL_STATE = Pattern.compile("[0-9A-Z]{5}");

    private RedactedThrowable(String originalType, StackTraceElement[] frames, RedactedThrowable cause) {
        super(originalType, cause, true, true);
        setStackTrace(frames);
    }

    /**
     * {@code null} 이면 {@code null} 이다 — 원인이 없을 수 있는 자리에 그대로 넘긴다.
     * 사본을 만들다 실패하면(스택을 {@code null} 로 돌려주는 비정상 예외 등) 타입만 담은 사본이다 — 로그 인자를 만드는 중에 오류 처리기가 깨지지 않게.
     */
    public static RedactedThrowable of(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        try {
            return redact(throwable, Collections.newSetFromMap(new IdentityHashMap<>()));
        } catch (RuntimeException redactionFailure) {
            return new RedactedThrowable(throwable.getClass().getName() + " [redaction failed]", new StackTraceElement[0], null);
        }
    }

    private static RedactedThrowable redact(Throwable throwable, Set<Throwable> visited) {
        visited.add(throwable);
        Throwable cause = throwable.getCause();
        RedactedThrowable redactedCause = cause == null || visited.contains(cause) ? null : redact(cause, visited);
        RedactedThrowable copy = new RedactedThrowable(typeOf(throwable), throwable.getStackTrace(), redactedCause);
        for (Throwable suppressed : throwable.getSuppressed()) {
            if (!visited.contains(suppressed)) {
                copy.addSuppressed(redact(suppressed, visited));
            }
        }
        return copy;
    }

    /** SQLState 는 생성자 인자라 아무 문자열이나 들어올 수 있다. 표준 모양(5자리 숫자 · 대문자)일 때만 싣는다. */
    private static String typeOf(Throwable throwable) {
        String type = throwable.getClass().getName();
        return throwable instanceof SQLException sqlException && sqlException.getSQLState() != null && SQL_STATE.matcher(sqlException.getSQLState()).matches()
            ? type + " [sqlState=" + sqlException.getSQLState() + "]"
            : type;
    }

    /** 스택은 원래 예외의 것을 쓴다. 만들 때 현재 스택을 채우지 않는다. */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
