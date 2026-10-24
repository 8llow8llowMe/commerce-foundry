package com.commercefoundry.apigateway.log;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 게이트웨이 로그에 남길 값을 가린다 — 서비스의 "5xx 의 원인을 로그에 남기는 기준" ({@code coding-conventions.md} §8, #280 · #285)과 같다.
 * 남기는 것: 메서드 · 앞 {@value #VISIBLE_PATH_SEGMENTS} 구간 경로 · 원인 사슬의 예외 타입과 스택 프레임 · 표준 모양의 SQLState.
 * 남기지 않는 것: 넷째 구간 이후 경로(주문번호 같은 식별자) · {@code ;} 매트릭스 파라미터 · 원인 사슬 어디의 예외 메시지든(Netty · 코덱 예외는 헤더 · 본문 조각을 담는다).
 *
 * <p>규칙은 common-core {@code RedactedThrowable} · {@code FallbackErrorResolver#maskPath}(#280, PR #287)와 같다. 그 PR 이 머지되기 전이라 게이트웨이 안에 둔다 —
 * #287 머지 뒤 {@code RedactedThrowable} 은 공용 클래스로 바꾸고, 경로 가림은 서블릿 의존 없는 공용 위치로 옮길 때 함께 쓴다.
 * 게이트웨이는 리액티브라 서블릿 패키지({@code common.web.servlet})를 가져오지 않는다.
 */
public final class GatewayLogRedaction {

    /** 서비스 {@code SecurityErrorResponder} · {@code FallbackErrorResolver} 와 같은 값. */
    public static final int VISIBLE_PATH_SEGMENTS = 3;

    private static final String MASKED_REST = "/…";
    private static final String MASKED_PARAMETERS = ";…";

    private GatewayLogRedaction() {
    }

    /**
     * 앞 {@value #VISIBLE_PATH_SEGMENTS} 구간만 남기고 나머지를 {@code /…} 로 가린다. 남기는 구간도 {@code ;} 뒤(매트릭스 파라미터)는 {@code ;…} 로 가린다.
     * 쿼리 문자열은 받지 않는다(경로만 넘긴다). {@code /api/v1/orders/4611686018427387904} → {@code /api/v1/orders/…},
     * {@code /api/v1/admin;jsessionid=x/y} → {@code /api/v1/admin;…/…}, {@code /api;x=y} → {@code /api;…}.
     */
    public static String maskPath(String path) {
        if (path == null) {
            return null;
        }
        String[] parts = path.split("/", VISIBLE_PATH_SEGMENTS + 2);
        boolean hasRest = parts.length == VISIBLE_PATH_SEGMENTS + 2 && !parts[VISIBLE_PATH_SEGMENTS + 1].isEmpty();
        String[] visible = hasRest ? Arrays.copyOf(parts, VISIBLE_PATH_SEGMENTS + 1) : parts;
        for (int index = 0; index < visible.length; index++) {
            int parameterStart = visible[index].indexOf(';');
            if (parameterStart >= 0) {
                visible[index] = visible[index].substring(0, parameterStart) + MASKED_PARAMETERS;
            }
        }
        return String.join("/", visible) + (hasRest ? MASKED_REST : "");
    }

    /**
     * 로그 인자로만 넘기는 사본 — 원래 예외 사슬의 타입 · 스택 프레임만 남기고 메시지를 버린다. 로그 출력은 {@code ...Redacted: <원래 예외 FQCN>} 이다.
     * {@code null} 이면 {@code null}. 사본을 만들다 실패하면 타입만 담은 사본이다 — 오류 처리기가 로그 인자를 만들다 깨지지 않게.
     */
    public static Throwable redact(Throwable throwable) {
        if (throwable == null) {
            return null;
        }
        try {
            return Redacted.copy(throwable, Collections.newSetFromMap(new IdentityHashMap<>()));
        } catch (RuntimeException redactionFailure) {
            return new Redacted(throwable.getClass().getName() + " [redaction failed]", new StackTraceElement[0], null);
        }
    }

    /** 메시지 자리에 원래 타입을 둔 사본. 원인 · 억제된 예외도 같은 방식으로 바꾸고, 순환 사슬은 한 번만 따라간다. */
    static final class Redacted extends Throwable {

        private static final Pattern SQL_STATE = Pattern.compile("[0-9A-Z]{5}");

        private Redacted(String originalType, StackTraceElement[] frames, Redacted cause) {
            super(originalType, cause, true, true);
            setStackTrace(frames);
        }

        private static Redacted copy(Throwable throwable, Set<Throwable> visited) {
            visited.add(throwable);
            Throwable cause = throwable.getCause();
            Redacted redactedCause = cause == null || visited.contains(cause) ? null : copy(cause, visited);
            Redacted redacted = new Redacted(typeOf(throwable), throwable.getStackTrace(), redactedCause);
            for (Throwable suppressed : throwable.getSuppressed()) {
                if (!visited.contains(suppressed)) {
                    redacted.addSuppressed(copy(suppressed, visited));
                }
            }
            return redacted;
        }

        /** SQLState 는 생성자 인자라 아무 문자열이나 들어올 수 있다. 표준 모양(5자리 숫자 · 대문자)일 때만 싣는다. */
        private static String typeOf(Throwable throwable) {
            String type = throwable.getClass().getName();
            return throwable instanceof SQLException sqlException && sqlException.getSQLState() != null
                && SQL_STATE.matcher(sqlException.getSQLState()).matches()
                ? type + " [sqlState=" + sqlException.getSQLState() + "]"
                : type;
        }

        /** 스택은 원래 예외의 것을 쓴다. 만들 때 현재 스택을 채우지 않는다. */
        @Override
        public synchronized Throwable fillInStackTrace() {
            return this;
        }
    }
}
