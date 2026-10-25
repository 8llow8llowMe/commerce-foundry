package com.commercefoundry.common.log;

import java.util.Arrays;

/**
 * 로그에 남길 요청 경로를 가린다 ({@code coding-conventions.md} §8, #280 · #291).
 * 서블릿도 리액터도 모르므로 서비스 폴백({@code FallbackErrorResolver}) · 보안 거부({@code SecurityErrorResponder}) · 게이트웨이가 같이 쓴다.
 *
 * <p>앞 {@value #VISIBLE_PATH_SEGMENTS} 구간만 남기고 나머지를 {@code /…} 로 가린다. 남기는 구간도 {@code ;} 뒤(매트릭스 파라미터 —
 * {@code jsessionid} · 방화벽이 거부한 경로의 공격 문자열)는 {@code ;…} 로 가린다. 쿼리 문자열은 받지 않는다(경로만 넘긴다).
 * {@code /api/v1/orders/4611686018427387904} → {@code /api/v1/orders/…},
 * {@code /api/v1/admin;jsessionid=x/y} → {@code /api/v1/admin;…/…}, {@code /api;x=y} → {@code /api;…}.
 */
public final class LogPathMask {

    /** 로그에 남기는 경로 구간 수. */
    public static final int VISIBLE_PATH_SEGMENTS = 3;

    private static final String MASKED_REST = "/…";
    private static final String MASKED_PARAMETERS = ";…";

    private LogPathMask() {
    }

    /**
     * {@code null} 이면 {@code null}. 구간이 {@value #VISIBLE_PATH_SEGMENTS} 이하면 매트릭스 파라미터만 가리고 그대로 둔다.
     */
    public static String mask(String path) {
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
}
