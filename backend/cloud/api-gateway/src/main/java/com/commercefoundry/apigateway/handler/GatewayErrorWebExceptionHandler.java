package com.commercefoundry.apigateway.handler;

import com.commercefoundry.apigateway.exception.GatewayErrorCode;
import java.net.SocketException;
import java.net.UnknownHostException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.NotFoundException;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.cloud.gateway.support.TimeoutException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.ErrorResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.PrematureCloseException;

/**
 * 게이트웨이가 직접 끊은 요청(라우트 없음, 인스턴스 없음, 연결 실패, 응답 전 연결 끊김, 응답 타임아웃)을
 * 서비스와 같은 {@code Response} 봉투로 내보낸다. 클라이언트는 응답이 어디서 끊겼는지 가리지 않고 같은 파서를 쓴다 ({@code api-design-guide.md} §2-1).
 *
 * <p>Boot 기본 핸들러({@code DefaultErrorWebExceptionHandler}, order -1)보다 먼저 받도록 -2 로 둔다.
 * 서비스가 돌려준 오류 응답은 예외가 아니라 그대로 중계되므로 이 핸들러를 지나지 않는다.
 *
 * <p>예외 메시지·스택은 응답에 싣지 않는다. 로그에만 경로·라우트·예외 타입을 남긴다.
 */
@Slf4j
@Component
@Order(-2)
@RequiredArgsConstructor
public class GatewayErrorWebExceptionHandler implements ErrorWebExceptionHandler {

    private static final String LOG_FORMAT = "gateway request failed. status={}, method={}, path={}, routeId={}, exception={}";
    private static final String NO_ROUTE = "-";

    private final GatewayErrorResponseWriter errorResponseWriter;

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.error(ex);
        }

        HttpStatusCode status = resolveStatus(ex);
        GatewayErrorCode errorCode = resolveErrorCode(status);
        // 그 밖의 4xx(405 등)는 원래 상태 코드를 지킨다 — 클라이언트가 고칠 수 있는 오류의 종류를 잃지 않게
        HttpStatusCode responseStatus = errorCode == GatewayErrorCode.BAD_REQUEST ? status : errorCode.getHttpStatus();
        logFailure(exchange, responseStatus, ex);

        if (ex instanceof ErrorResponse errorResponse) {
            // 405 의 Allow 처럼 예외가 실어 둔 응답 헤더를 지킨다
            response.getHeaders().addAll(errorResponse.getHeaders());
        }
        return errorResponseWriter.write(response, responseStatus, errorCode);
    }

    /**
     * 예외를 HTTP 상태로 정규화한다.
     * 인스턴스 없음·연결 실패는 503(요청이 서비스에 닿지 않았다), 응답 전 연결 끊김은 502, 응답 타임아웃은 504 다.
     */
    private static HttpStatusCode resolveStatus(Throwable ex) {
        // NotFoundException 은 "라우트는 있는데 살아 있는 인스턴스가 없다" 는 뜻이다. loadbalancer.use404 와 관계없이 503 으로 본다.
        // SocketException 은 ConnectException(연결 거부·연결 타임아웃)과 NoRouteToHostException 을 함께 덮는다
        if (ex instanceof NotFoundException || hasCause(ex, SocketException.class) || hasCause(ex, UnknownHostException.class)) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        // 요청은 보냈는데 응답 전에 서비스가 연결을 끊었다. 처리됐는지 알 수 없다
        if (hasCause(ex, PrematureCloseException.class)) {
            return HttpStatus.BAD_GATEWAY;
        }
        // 게이트웨이는 response-timeout 초과를 게이트웨이 자체 TimeoutException(java.util.concurrent 것이 아니다)을
        // 원인으로 가진 504 ResponseStatusException 으로 던진다. 원인만 올라와도 504 로 본다
        if (hasCause(ex, TimeoutException.class)) {
            return HttpStatus.GATEWAY_TIMEOUT;
        }
        if (ex instanceof ErrorResponse errorResponse) {
            return errorResponse.getStatusCode();
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private static GatewayErrorCode resolveErrorCode(HttpStatusCode status) {
        if (status.isSameCodeAs(HttpStatus.NOT_FOUND)) {
            return GatewayErrorCode.ROUTE_NOT_FOUND;
        }
        if (status.isSameCodeAs(HttpStatus.SERVICE_UNAVAILABLE)) {
            return GatewayErrorCode.SERVICE_UNAVAILABLE;
        }
        if (status.isSameCodeAs(HttpStatus.GATEWAY_TIMEOUT)) {
            return GatewayErrorCode.GATEWAY_TIMEOUT;
        }
        if (status.isSameCodeAs(HttpStatus.BAD_GATEWAY)) {
            return GatewayErrorCode.BAD_GATEWAY;
        }
        if (status.is4xxClientError()) {
            return GatewayErrorCode.BAD_REQUEST;
        }
        return GatewayErrorCode.INTERNAL_ERROR;
    }

    private static boolean hasCause(Throwable ex, Class<? extends Throwable> type) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
            if (current.getCause() == current) {
                return false;
            }
        }
        return false;
    }

    private static void logFailure(ServerWebExchange exchange, HttpStatusCode status, Throwable ex) {
        ServerHttpRequest request = exchange.getRequest();
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        String routeId = route == null ? NO_ROUTE : route.getId();
        String path = request.getPath().value();
        String exceptionType = ex.getClass().getName();

        if (status.isSameCodeAs(HttpStatus.INTERNAL_SERVER_ERROR)) {
            // 예상하지 못한 오류만 스택을 남긴다. 502 · 503 · 504 는 원인이 대상 서비스 쪽이라 스택이 정보를 더하지 않는다
            log.error(LOG_FORMAT, status.value(), request.getMethod(), path, routeId, exceptionType, ex);
        } else if (status.is5xxServerError()) {
            log.warn(LOG_FORMAT, status.value(), request.getMethod(), path, routeId, exceptionType);
        } else {
            log.debug(LOG_FORMAT, status.value(), request.getMethod(), path, routeId, exceptionType);
        }
    }
}
