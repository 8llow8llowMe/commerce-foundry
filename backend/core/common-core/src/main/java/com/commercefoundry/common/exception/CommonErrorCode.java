package com.commercefoundry.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * 서블릿 서비스에서 도메인 · 보안 advice 가 잡지 않은 오류의 코드 ({@code api-design-guide.md} §2-1 공통 오류 코드 표).
 * 매핑 없는 경로 · 지원하지 않는 메서드 · 미디어 타입 같은 프레임워크 오류와 처리되지 않은 예외가 여기로 온다.
 * 게이트웨이의 {@code GATEWAY_00x} 와 같은 짜임이다 — 클라이언트는 코드 접두어로 어디서 끊겼는지만 구분한다.
 *
 * <p>도메인 오류를 여기에 더하지 않는다. 도메인 오류는 도메인 3종 세트({@code coding-conventions.md} §8-1)가 맡는다.
 */
@Getter
@RequiredArgsConstructor
public enum CommonErrorCode {

    INTERNAL_ERROR("COMMON_000", "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.", HttpStatus.INTERNAL_SERVER_ERROR),
    PATH_NOT_FOUND("COMMON_001", "요청한 경로를 찾을 수 없습니다.", HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED("COMMON_002", "지원하지 않는 요청 메서드입니다.", HttpStatus.METHOD_NOT_ALLOWED),
    UNSUPPORTED_MEDIA_TYPE("COMMON_003", "지원하지 않는 요청 본문 형식입니다.", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    // 400 · 406 · 그 밖의 4xx. 상태 코드는 원래 오류의 값을 그대로 쓴다
    BAD_REQUEST("COMMON_004", "잘못된 요청입니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;

    /**
     * 응답 상태로 코드를 고른다. 404 · 405 · 415 는 전용 코드, 그 밖의 4xx 는 {@link #BAD_REQUEST}, 5xx 는 {@link #INTERNAL_ERROR} 다.
     * 4xx · 5xx 가 아닌 값은 오류로 오지 않지만, 오면 서버 결함으로 본다.
     */
    public static CommonErrorCode of(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> PATH_NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            default -> status.is4xxClientError() ? BAD_REQUEST : INTERNAL_ERROR;
        };
    }
}
