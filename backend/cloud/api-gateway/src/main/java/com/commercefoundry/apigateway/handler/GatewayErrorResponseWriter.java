package com.commercefoundry.apigateway.handler;

import com.commercefoundry.apigateway.exception.GatewayErrorCode;
import com.commercefoundry.common.dto.Response;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * 게이트웨이가 직접 만드는 오류 응답을 서비스와 같은 {@link Response} 봉투로 쓴다 ({@code api-design-guide.md} §2-1).
 * 오류 핸들러와 요청 가드 필터가 함께 쓴다 — 봉투 모양이 두 곳에서 갈라지지 않게.
 */
@Component
@RequiredArgsConstructor
public class GatewayErrorResponseWriter {

    private final JsonMapper jsonMapper;

    /**
     * 본문에는 오류 코드와 고정된 사용자 문구만 싣는다. 예외 메시지·스택은 싣지 않는다.
     * {@code Allow} 같은 추가 헤더는 호출하는 쪽이 이 메서드보다 먼저 넣는다 (쓰기가 시작되면 헤더를 바꿀 수 없다).
     */
    public Mono<Void> write(ServerHttpResponse response, HttpStatusCode status, GatewayErrorCode errorCode) {
        byte[] body = jsonMapper.writeValueAsBytes(Response.fail(errorCode.getCode(), errorCode.getMessage()));
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }
}
