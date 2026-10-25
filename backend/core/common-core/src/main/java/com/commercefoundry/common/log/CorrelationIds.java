package com.commercefoundry.common.log;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * 요청 상관 id (#284, {@code coding-conventions.md} §8). 한 요청의 로그 줄과, 그 요청이 부른 internal 호출의 로그 줄을 묶는다.
 *
 * <p>Micrometer Tracing 은 두지 않는다. 추적 백엔드가 없고, W3C {@code traceparent} 를 받으면 외부 값을 그대로 믿게 된다.
 * id 는 게이트웨이가 헤더로 만들고 서비스가 MDC 에 넣는다. 응답 봉투 · 응답 헤더에는 싣지 않는다.
 *
 * <p>외부 값은 형식만 통과한 것을 쓴다. 아니면 버리고 새로 만든다 — 줄바꿈 · 긴 값이 로그 한 줄을 위조하지 못하게.
 * 값은 소문자 UUID 36자다. 틀린 원문은 로그에 남기지 않는다.
 */
public final class CorrelationIds {

    /** 게이트웨이 → 서비스 → internal Feign 이 같은 이름으로 주고받는다. */
    public static final String HEADER = "X-Correlation-Id";

    /** 로그 패턴 {@code %X{correlationId}} 가 읽는 MDC 키. */
    public static final String MDC_KEY = "correlationId";

    /** 에러 디스패치가 같은 요청의 id 를 다시 읽게 요청에 남겨 둔다. */
    public static final String REQUEST_ATTRIBUTE = "com.commercefoundry.correlationId";

    /**
     * 로그 패턴에 넣는 조각. id 가 없으면 비운다 ({@code CorrelationIdLoggingEnvironmentPostProcessor}).
     * Boot 기본 콘솔 패턴의 {@code logging.pattern.correlation} 자리다.
     */
    /** id 가 없으면 {@code []} 를 지운다. logback {@code %notEmpty} 는 이 버전에 없다. */
    public static final String LOG_PATTERN = "%replace([%X{correlationId}] ){'\\[\\] ', ''}";

    private static final Pattern CANONICAL_UUID = Pattern.compile(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private CorrelationIds() {
    }

    /** 값이 소문자 UUID 한 개면 그대로, 아니면 {@code false}. 대문자는 {@link #acceptOrCreate} 가 소문자로 맞춘 뒤에 본다. */
    public static boolean isAcceptable(String candidate) {
        return candidate != null && CANONICAL_UUID.matcher(candidate).matches();
    }

    /**
     * 헤더 값 하나면 소문자로 맞춰 받고, 비었거나 형식이 틀리면 새 UUID 를 만든다.
     * 값이 둘 이상이면 호출자가 {@code null} 을 넘긴다 — 어느 쪽을 믿을지 고르지 않는다.
     */
    public static String acceptOrCreate(String candidate) {
        if (candidate == null) {
            return newId();
        }
        String normalized = candidate.toLowerCase(Locale.ROOT);
        if (isAcceptable(normalized)) {
            return normalized;
        }
        return newId();
    }

    /** 이 스레드의 MDC 값. 없거나 형식이 아니면 {@code null} — internal 호출은 그때 헤더를 싣지 않는다. */
    public static String current() {
        String value = MDC.get(MDC_KEY);
        return isAcceptable(value) ? value : null;
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
