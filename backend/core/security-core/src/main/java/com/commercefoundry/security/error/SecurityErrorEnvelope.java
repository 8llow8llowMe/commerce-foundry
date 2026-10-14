package com.commercefoundry.security.error;

import com.commercefoundry.common.dto.Response;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 인증·인가 실패 응답의 본문과 {@code WWW-Authenticate} 값을 만든다. 서블릿·리액티브 의존이 없다 —
 * 서비스의 진입점과 게이트웨이(#4)의 writer 가 이 클래스로 <b>같은 바이트</b>를 쓴다 ({@code api-design-guide.md} §2-1).
 *
 * <p>본문은 {@code Response.fail(code, message)} 다. 예외 메시지·토큰 내용은 싣지 않는다.
 */
public final class SecurityErrorEnvelope {

    private static final String BEARER = "Bearer";
    private static final String INVALID_TOKEN = BEARER + " error=\"invalid_token\"";
    /** RFC 6750 §3 — error_description 은 ASCII 만 허용한다. 사용자 문구(한국어)는 본문에 있다. */
    private static final String EXPIRED = INVALID_TOKEN + ", error_description=\"The access token expired\"";

    private final JsonMapper jsonMapper;

    /** 서비스·게이트웨이 컨텍스트의 {@code JsonMapper} 빈을 넘긴다. 설정이 같아야 다른 오류 응답과 모양이 같다. */
    public SecurityErrorEnvelope(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public byte[] body(SecurityErrorCode errorCode) {
        return jsonMapper.writeValueAsBytes(Response.fail(errorCode.getCode(), errorCode.getMessage()));
    }

    /** 401 이면 RFC 6750 의 {@code WWW-Authenticate} 값, 403 · 503 이면 비어 있다. */
    public static Optional<String> wwwAuthenticate(SecurityErrorCode errorCode) {
        return switch (errorCode) {
            case UNAUTHORIZED -> Optional.of(BEARER);
            case TOKEN_EXPIRED -> Optional.of(EXPIRED);
            case TOKEN_INVALID, TOKEN_REVOKED -> Optional.of(INVALID_TOKEN);
            case FORBIDDEN, TOKEN_VERIFICATION_UNAVAILABLE -> Optional.empty();
        };
    }
}
