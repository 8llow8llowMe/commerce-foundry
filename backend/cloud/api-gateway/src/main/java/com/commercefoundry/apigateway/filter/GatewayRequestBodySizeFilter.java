package com.commercefoundry.apigateway.filter;

import com.commercefoundry.apigateway.exception.GatewayErrorCode;
import com.commercefoundry.apigateway.handler.GatewayErrorResponseWriter;
import com.commercefoundry.apigateway.properties.GatewayRequestBodyProperties;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 요청 본문이 상한({@link GatewayRequestBodyProperties})을 넘으면 413 {@code GATEWAY_007} 로 끊는다 ({@code security-guide.md} §7-4).
 * 서비스(Tomcat)의 {@code max-http-form-post-size} 는 폼에만 걸리고 JSON 에는 걸리지 않아, 상한이 없으면 한 요청에 큰 본문을 싣는 남용을 막지 못한다.
 *
 * <ul>
 *   <li><b>{@code Content-Length} 가 있으면</b> 그 값만 보고 정한다. 넘으면 본문을 읽지 않고 끊는다({@code Expect: 100-continue} 면 본문이 오기 전에 끝난다).</li>
 *   <li><b>chunked 면</b> 상한까지만 모은다. 넘으면 그때까지 받은 것을 버리고 끊는다 — <b>하류에는 한 바이트도 보내지 않는다.</b>
 *       넘지 않으면 모은 본문을 {@code Content-Length} 와 함께 넘긴다. 스트리밍으로 세면서 넘기면 상한을 넘기 전까지의 앞부분이 이미 서비스에 닿는다.
 *       모으는 동안 메모리를 잡으므로 두 상한을 더 건다 — 동시에 모으는 총량({@code buffering-budget}, 넘으면 411)과 수신 시간({@code body-read-timeout}, 넘으면 408).
 *       천천히 보내는 연결을 여러 개 열어 메모리를 채우는 공격(slowloris)을 막는다.</li>
 *   <li><b>둘 다 없으면</b> HTTP/1.1 에서 본문이 없는 요청이다(RFC 9112 §6.3). 모으지 않고 통과시킨다 — HTTP/2 를 켜면 길이 없는 본문이 생기므로 이 판단을 다시 본다.</li>
 * </ul>
 *
 * <p>요청 가드({@link GatewayRequestGuardFilter}) 바로 뒤, CORS · 보안 체인보다 앞이다 — 큰 본문은 토큰 검증 전에 끊는다. 그래서 이 거절에는 CORS 헤더가 없다.
 * 거절 응답은 {@code Connection: close} 로 연결을 닫는다 — 읽지 않은 본문을 끝까지 받아 비우지 않게. 거절은 남용 신호라 debug 로만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayRequestBodySizeFilter implements WebFilter, Ordered {

    private static final int LOG_PATH_MAX_LENGTH = 200;
    private static final byte[] EMPTY = new byte[0];

    private final GatewayRequestBodyProperties properties;
    private final GatewayErrorResponseWriter errorResponseWriter;
    /** 지금 chunked 본문을 모으려고 잡아 둔 바이트. 요청마다 상한만큼 잡고, 요청이 끝나면 돌려준다. */
    private final AtomicLong reservedBytes = new AtomicLong();

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        long limit = properties.maxSize().toBytes();
        long contentLength = request.getHeaders().getContentLength();

        if (contentLength > limit) {
            log.debug("gateway request rejected. reason=body-too-large, contentLength={}, limit={}, method={}, path={}",
                contentLength, limit, request.getMethod(), abbreviate(request.getPath().value()));
            return reject(exchange, GatewayErrorCode.PAYLOAD_TOO_LARGE.getHttpStatus(), GatewayErrorCode.PAYLOAD_TOO_LARGE);
        }
        if (!isChunked(request.getHeaders())) {
            return chain.filter(exchange);
        }
        if (!reserve(limit)) {
            log.debug("gateway request rejected. reason=buffering-budget-exhausted, limit={}, method={}, path={}",
                limit, request.getMethod(), abbreviate(request.getPath().value()));
            return reject(exchange, HttpStatus.LENGTH_REQUIRED, GatewayErrorCode.BAD_REQUEST);
        }
        AtomicBoolean released = new AtomicBoolean();
        return bufferAndForward(exchange, chain, limit)
            .doFinally(signal -> {
                if (released.compareAndSet(false, true)) {
                    reservedBytes.addAndGet(-limit);
                }
            });
    }

    /**
     * 오류 처리는 <b>본문을 모으는 단계에만</b> 건다 — 하류 체인(코덱의 {@code max-in-memory-size} 등)에서 난 같은 타입의 예외를 413 · 408 로 바꾸지 않게.
     * 거절했으면 빈 값으로 끝나 하류를 부르지 않는다.
     */
    private Mono<Void> bufferAndForward(ServerWebExchange exchange, WebFilterChain chain, long limit) {
        ServerHttpRequest request = exchange.getRequest();
        DataBufferFactory bufferFactory = exchange.getResponse().bufferFactory();
        return DataBufferUtils.join(request.getBody(), Math.toIntExact(limit))
            .timeout(properties.bodyReadTimeout())
            .map(GatewayRequestBodySizeFilter::toBytes)
            .defaultIfEmpty(EMPTY)
            .onErrorResume(DataBufferLimitException.class, exceeded -> {
                log.debug("gateway request rejected. reason=body-too-large, contentLength=chunked, limit={}, method={}, path={}",
                    limit, request.getMethod(), abbreviate(request.getPath().value()));
                return reject(exchange, GatewayErrorCode.PAYLOAD_TOO_LARGE.getHttpStatus(), GatewayErrorCode.PAYLOAD_TOO_LARGE).then(Mono.<byte[]>empty());
            })
            .onErrorResume(TimeoutException.class, timeout -> {
                log.debug("gateway request rejected. reason=body-read-timeout, timeout={}, method={}, path={}",
                    properties.bodyReadTimeout(), request.getMethod(), abbreviate(request.getPath().value()));
                return reject(exchange, HttpStatus.REQUEST_TIMEOUT, GatewayErrorCode.BAD_REQUEST).then(Mono.<byte[]>empty());
            })
            .flatMap(body -> chain.filter(exchange.mutate().request(withBufferedBody(request, body, bufferFactory)).build()));
    }

    /** 총량을 넘지 않을 때만 잡는다. 동시에 들어온 요청은 CAS 로 하나씩 잡는다. */
    private boolean reserve(long bytes) {
        long budget = properties.bufferingBudget().toBytes();
        while (true) {
            long current = reservedBytes.get();
            if (current + bytes > budget) {
                return false;
            }
            if (reservedBytes.compareAndSet(current, current + bytes)) {
                return true;
            }
        }
    }

    /** 테스트용 — 지금 잡혀 있는 바이트. 요청이 끝나면 0 으로 돌아와야 한다. */
    long reservedBytes() {
        return reservedBytes.get();
    }

    /** {@code Content-Length} 와 chunked 가 함께 오면 chunked 가 본문 경계를 정한다 (RFC 9112 §6.3) — 길이를 믿지 않고 모아서 센다. */
    private static boolean isChunked(HttpHeaders headers) {
        String transferEncoding = headers.getFirst(HttpHeaders.TRANSFER_ENCODING);
        return transferEncoding != null && transferEncoding.toLowerCase(Locale.ROOT).contains("chunked");
    }

    /** 모은 버퍼를 바이트 배열로 옮기고 바로 해제한다 — 풀 버퍼를 하류 응답까지 잡고 있지 않는다. */
    private static byte[] toBytes(DataBuffer buffer) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            return bytes;
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    /**
     * 모은 본문을 길이가 정해진 본문으로 바꾼다. 구독할 때마다 바이트 배열을 감싼 새 버퍼를 준다(풀 버퍼가 아니다) —
     * 보안 체인이 거절해 아무도 본문을 읽지 않아도 해제할 것이 남지 않는다.
     */
    private static ServerHttpRequest withBufferedBody(ServerHttpRequest request, byte[] body, DataBufferFactory bufferFactory) {
        ServerHttpRequest withLength = request.mutate().headers(headers -> {
            headers.remove(HttpHeaders.TRANSFER_ENCODING);
            headers.setContentLength(body.length);
        }).build();
        return new ServerHttpRequestDecorator(withLength) {
            @Override
            public Flux<DataBuffer> getBody() {
                return body.length == 0 ? Flux.empty() : Flux.defer(() -> Flux.just(bufferFactory.wrap(body)));
            }
        };
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, GatewayErrorCode errorCode) {
        exchange.getResponse().getHeaders().set(HttpHeaders.CONNECTION, "close");
        return errorResponseWriter.write(exchange.getResponse(), status, errorCode);
    }

    private static String abbreviate(String path) {
        return path.length() <= LOG_PATH_MAX_LENGTH ? path : path.substring(0, LOG_PATH_MAX_LENGTH) + "...(length=" + path.length() + ")";
    }
}
