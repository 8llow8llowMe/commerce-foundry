package com.commercefoundry.apigateway.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 게이트웨이가 직접 끊은 요청의 오류 코드 ({@code api-design-guide.md} §2-1 게이트웨이 오류 코드 표).
 * 서비스가 돌려준 오류 응답은 그대로 중계하므로 여기 코드로 바뀌지 않는다.
 */
@Getter
@RequiredArgsConstructor
public enum GatewayErrorCode {

    INTERNAL_ERROR("GATEWAY_000", "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.", HttpStatus.INTERNAL_SERVER_ERROR),
    ROUTE_NOT_FOUND("GATEWAY_001", "요청한 경로를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    SERVICE_UNAVAILABLE("GATEWAY_002", "서비스를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해주세요.", HttpStatus.SERVICE_UNAVAILABLE),
    // 003 · 005 는 요청이 서비스에 닿은 뒤 끊겼다 — 처리됐을 수 있으니 재시도 전에 확인하라고 안내한다
    GATEWAY_TIMEOUT("GATEWAY_003", "응답 시간이 초과되었습니다. 처리 여부를 확인한 뒤 다시 시도해주세요.", HttpStatus.GATEWAY_TIMEOUT),
    BAD_REQUEST("GATEWAY_004", "잘못된 요청입니다.", HttpStatus.BAD_REQUEST),
    BAD_GATEWAY("GATEWAY_005", "서비스 응답을 받지 못했습니다. 처리 여부를 확인한 뒤 다시 시도해주세요.", HttpStatus.BAD_GATEWAY),
    // 요청이 서비스에 닿지 않았다 — Retry-After 뒤에 같은 요청을 다시 보내도 된다 (security-guide.md §7-3)
    TOO_MANY_REQUESTS("GATEWAY_006", "요청이 너무 많습니다. 잠시 후 다시 시도해주세요.", HttpStatus.TOO_MANY_REQUESTS);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
