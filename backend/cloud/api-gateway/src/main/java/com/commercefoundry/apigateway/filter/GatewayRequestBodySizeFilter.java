package com.commercefoundry.apigateway.filter;

import com.commercefoundry.apigateway.exception.GatewayErrorCode;
import com.commercefoundry.apigateway.handler.GatewayErrorResponseWriter;
import com.commercefoundry.apigateway.properties.GatewayRequestBodyProperties;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
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
import reactor.core.publisher.Sinks;
import reactor.core.publisher.SynchronousSink;

/**
 * 요청 본문이 상한({@link GatewayRequestBodyProperties})을 넘으면 413 {@code GATEWAY_007} 로 끊는다 ({@code security-guide.md} §7-4).
 * 서비스(Tomcat)의 {@code max-http-form-post-size} 는 폼에만 걸리고 JSON 에는 걸리지 않아, 상한이 없으면 한 요청에 큰 본문을 싣는 남용을 막지 못한다.
 *
 * <ul>
 *   <li><b>chunked 를 먼저 본다.</b> {@code Content-Length} 와 함께 오면 chunked 가 경계를 정하므로(RFC 9112 §6.3) 길이를 믿지 않고 아래 chunked 경로로 센다.</li>
 *   <li><b>chunked 가 아니고 {@code Content-Length} 가 있으면</b> 그 값만 보고 정한다. 넘으면 하류로 보내지 않고 413 을 쓴다 — 쓰기 전에 본문을 비우는지는 아래 "배출" 을 본다.</li>
 *   <li><b>chunked 면</b> 상한까지만 모은다. 넘으면 그때까지 받은 것을 버리고 끊는다 — <b>하류에는 한 바이트도 보내지 않는다.</b>
 *       넘지 않으면 모은 본문을 {@code Content-Length} 와 함께 넘긴다. 스트리밍으로 세면서 넘기면 상한을 넘기 전까지의 앞부분이 이미 서비스에 닿는다.
 *       모으는 동안 메모리를 잡으므로 두 상한을 더 건다 — 동시에 모으는 총량({@code buffering-budget}, 넘으면 411)과 수신 시간({@code body-read-timeout}, 넘으면 408).
 *       천천히 보내는 연결을 여러 개 열어 메모리를 채우는 공격(slowloris)을 막는다.</li>
 *   <li><b>둘 다 없으면</b> HTTP/1.1 에서 본문이 없는 요청이다(RFC 9112 §6.3). 모으지 않고 통과시킨다 — HTTP/2 를 켜면 길이 없는 본문이 생기므로 이 판단을 다시 본다.</li>
 * </ul>
 *
 * <p><b>배출(#145).</b> 거절 응답은 {@code Connection: close} 로 연결을 닫는다. Reactor Netty 는 그 응답을 다 쓰자마자 채널을 닫는데,
 * 클라이언트가 아직 보내는 본문이 수신 버퍼에 남아 있으면 커널이 FIN 대신 RST 를 보내 클라이언트는 413 을 읽기 전에 연결 리셋을 본다.
 * 그래서 전체 본문이 {@code discard-max-size} 이하면 {@code discard-timeout} 안에서 끝까지 읽어 버린 뒤 413 을 쓴다 — 클라이언트는 항상 413 을 받는다.
 * 받는 즉시 해제하므로 메모리는 잡지 않는다. 그보다 크거나 시간이 지나면 바로 끊는다 — 남용 쪽이라 연결 오류를 감수하고, 큰 본문을 끝까지 받지 않는다.
 * {@code Expect: 100-continue} 요청은 배출하지 않는다 — 본문을 구독하면 Reactor Netty 가 100 을 보내 버린다. 본문이 오기 전에 413 으로 끝난다.
 *
 * <p>요청 가드({@link GatewayRequestGuardFilter}) 바로 뒤, CORS · 보안 체인보다 앞이다 — 큰 본문은 토큰 검증 전에 끊는다. 그래서 이 거절에는 CORS 헤더가 없다.
 * 거절은 남용 신호라 debug 로만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayRequestBodySizeFilter implements WebFilter, Ordered {

    private static final int LOG_PATH_MAX_LENGTH = 200;
    private static final byte[] EMPTY = new byte[0];
    private static final String DISCARD_READ_FAILED = "read-failed";

    private final GatewayRequestBodyProperties properties;
    private final GatewayErrorResponseWriter errorResponseWriter;
    /** 지금 chunked 본문을 모으려고 잡아 둔 바이트. 요청마다 상한만큼 잡고, 상한을 넘거나 요청이 끝나면 돌려준다. */
    private final AtomicLong reservedBytes = new AtomicLong();

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        long limit = properties.maxSize().toBytes();
        // chunked 를 먼저 본다 — Content-Length 와 함께 오면 chunked 가 경계를 정하므로(RFC 9112 §6.3) 길이를 믿지 않고 센다.
        // 하위 계층(Reactor Netty)이 이때 Content-Length 를 지우는지에 기대지 않는다
        if (!isChunked(request.getHeaders())) {
            long contentLength = request.getHeaders().getContentLength();
            return contentLength > limit ? rejectDeclaredTooLarge(exchange, contentLength, limit) : chain.filter(exchange);
        }
        if (!reserve(limit)) {
            log.debug("gateway request rejected. reason=buffering-budget-exhausted, limit={}, method={}, path={}",
                limit, request.getMethod(), abbreviate(request.getPath().value()));
            return reject(exchange, HttpStatus.LENGTH_REQUIRED, GatewayErrorCode.BAD_REQUEST);
        }
        AtomicBoolean released = new AtomicBoolean();
        Runnable releaseReservation = () -> {
            if (released.compareAndSet(false, true)) {
                reservedBytes.addAndGet(-limit);
            }
        };
        return bufferAndForward(exchange, chain, limit, releaseReservation)
            .doFinally(signal -> releaseReservation.run());
    }

    /** {@code Content-Length} 로 상한 초과를 안 요청. 배출할 수 있으면 끝까지 읽어 버린 뒤, 아니면 읽지 않고 바로 413 을 쓴다. */
    private Mono<Void> rejectDeclaredTooLarge(ServerWebExchange exchange, long contentLength, long limit) {
        ServerHttpRequest request = exchange.getRequest();
        String skipReason = expectsContinue(request.getHeaders()) ? "expect-continue"
            : contentLength > properties.discardMaxSize().toBytes() ? "over-discard-max-size" : null;
        if (skipReason != null) {
            log.debug("gateway request rejected. reason=body-too-large, contentLength={}, limit={}, discard=skipped-{}, method={}, path={}",
                contentLength, limit, skipReason, request.getMethod(), abbreviate(request.getPath().value()));
            return rejectTooLarge(exchange);
        }
        return discard(request.getBody())
            .flatMap(outcome -> {
                log.debug("gateway request rejected. reason=body-too-large, contentLength={}, limit={}, discard={}, method={}, path={}",
                    contentLength, limit, outcome, request.getMethod(), abbreviate(request.getPath().value()));
                if (!DISCARD_READ_FAILED.equals(outcome)) {
                    return rejectTooLarge(exchange);
                }
                // 읽다 실패했으면 클라이언트가 이미 끊었을 가능성이 크다 — 닫힌 채널에 쓰다 난 오류를 기본 오류 로그로 올리지 않는다
                return rejectTooLarge(exchange).onErrorResume(error -> {
                    log.debug("gateway rejection not delivered. reason=body-too-large, error={}, method={}, path={}",
                        error.getClass().getSimpleName(), request.getMethod(), abbreviate(request.getPath().value()));
                    return Mono.empty();
                });
            });
    }

    /**
     * 본문을 읽는 즉시 해제하며 끝까지 받는다. 결과는 로그용 — 시간이 지나도 · 읽다 실패해도 413 은 쓴다(연결이 이미 끊겼으면 쓰기가 실패할 뿐이다).
     * {@code Content-Length} 가 배출 상한 이하인 요청만 오므로 바이트를 따로 세지 않는다 — 서버가 그 길이에서 본문을 끝낸다.
     */
    private Mono<String> discard(Flux<DataBuffer> body) {
        return body.doOnNext(DataBufferUtils::release)
            .doOnDiscard(DataBuffer.class, DataBufferUtils::release)
            .then(Mono.just("complete"))
            .timeout(properties.discardTimeout(), Mono.just("timeout"))
            .onErrorResume(error -> Mono.just(DISCARD_READ_FAILED));
    }

    /**
     * 오류 처리는 <b>본문을 모으는 단계에만</b> 건다 — 하류 체인(코덱의 {@code max-in-memory-size} 등)에서 난 같은 타입의 예외를 413 · 408 로 바꾸지 않게.
     * 상한을 넘으면 모은 것을 버리고 버퍼 자리를 바로 돌려준 뒤, 배출 상한 · 배출 시간까지 읽어 버리고 413 을 쓴다. 하류는 부르지 않는다.
     */
    private Mono<Void> bufferAndForward(ServerWebExchange exchange, WebFilterChain chain, long limit, Runnable releaseReservation) {
        ServerHttpRequest request = exchange.getRequest();
        DataBufferFactory bufferFactory = exchange.getResponse().bufferFactory();
        ChunkedBody body = new ChunkedBody(limit, properties.discardMaxSize().toBytes(), releaseReservation);
        return request.getBody()
            // 배출 시간은 상한을 넘은 때부터 센다. 지나면 본문이 끝난 것으로 보고 413 을 쓴다.
            // 아래 수신 시간(body-read-timeout)은 요청 전체에 걸려 있어 그쪽이 먼저 끝나면 배출도 거기서 잘린다 — 그때도 이미 넘었으므로 413 이다
            .takeUntilOther(body.overflowed().then(Mono.delay(properties.discardTimeout())))
            .handle((DataBuffer buffer, SynchronousSink<Object> sink) -> {
                if (!body.add(buffer)) {
                    sink.complete();
                }
            })
            .doOnDiscard(DataBuffer.class, DataBufferUtils::release)
            .then(Mono.just(body))
            .timeout(properties.bodyReadTimeout())
            .onErrorResume(TimeoutException.class, timeout -> {
                if (body.tooLarge()) {
                    return rejectChunkedTooLarge(exchange, limit, body).then(Mono.<ChunkedBody>empty());
                }
                log.debug("gateway request rejected. reason=body-read-timeout, timeout={}, method={}, path={}",
                    properties.bodyReadTimeout(), request.getMethod(), abbreviate(request.getPath().value()));
                return reject(exchange, HttpStatus.REQUEST_TIMEOUT, GatewayErrorCode.BAD_REQUEST).then(Mono.<ChunkedBody>empty());
            })
            .flatMap(received -> received.tooLarge()
                ? rejectChunkedTooLarge(exchange, limit, received)
                : chain.filter(exchange.mutate().request(withBufferedBody(request, received.array(), received.length(), bufferFactory)).build()));
    }

    private Mono<Void> rejectChunkedTooLarge(ServerWebExchange exchange, long limit, ChunkedBody body) {
        ServerHttpRequest request = exchange.getRequest();
        log.debug("gateway request rejected. reason=body-too-large, contentLength=chunked, limit={}, received={}, method={}, path={}",
            limit, body.received(), request.getMethod(), abbreviate(request.getPath().value()));
        return rejectTooLarge(exchange);
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

    /** {@code Expect} 값 중 하나라도 100-continue 면(대소문자 무시) — Reactor Netty 가 100 을 보낼지 정하는 판정({@code is100ContinueExpected})과 맞춘다. */
    private static boolean expectsContinue(HttpHeaders headers) {
        List<String> expectations = headers.get(HttpHeaders.EXPECT);
        return expectations != null && expectations.stream()
            .flatMap(value -> Arrays.stream(value.split(",")))
            .anyMatch(value -> "100-continue".equalsIgnoreCase(value.trim()));
    }

    /**
     * 모은 본문을 길이가 정해진 본문으로 바꾼다. 구독할 때마다 배열 앞 {@code length} 바이트를 감싼 새 버퍼를 준다(풀 버퍼가 아니고 복사하지 않는다) —
     * 보안 체인이 거절해 아무도 본문을 읽지 않아도 해제할 것이 남지 않는다.
     */
    private static ServerHttpRequest withBufferedBody(ServerHttpRequest request, byte[] body, int length, DataBufferFactory bufferFactory) {
        ServerHttpRequest withLength = request.mutate().headers(headers -> {
            headers.remove(HttpHeaders.TRANSFER_ENCODING);
            headers.setContentLength(length);
        }).build();
        return new ServerHttpRequestDecorator(withLength) {
            @Override
            public Flux<DataBuffer> getBody() {
                return length == 0 ? Flux.empty() : Flux.defer(() -> Flux.just(bufferFactory.wrap(ByteBuffer.wrap(body, 0, length))));
            }
        };
    }

    private Mono<Void> rejectTooLarge(ServerWebExchange exchange) {
        return reject(exchange, GatewayErrorCode.PAYLOAD_TOO_LARGE.getHttpStatus(), GatewayErrorCode.PAYLOAD_TOO_LARGE);
    }

    /** 배출을 마쳤어도 닫는다 — 배출하지 못한 거절과 응답 모양을 하나로 두고, 남용으로 본 연결을 다시 쓰지 않는다. */
    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, GatewayErrorCode errorCode) {
        exchange.getResponse().getHeaders().set(HttpHeaders.CONNECTION, "close");
        return errorResponseWriter.write(exchange.getResponse(), status, errorCode);
    }

    private static String abbreviate(String path) {
        return path.length() <= LOG_PATH_MAX_LENGTH ? path : path.substring(0, LOG_PATH_MAX_LENGTH) + "...(length=" + path.length() + ")";
    }

    /**
     * chunked 본문을 상한까지 모은다. 넘으면 모은 것을 버리고 버퍼 자리를 바로 돌려준 뒤({@code onOverflow}), 배출 상한까지 세기만 한다 —
     * 배출 중인 연결이 {@code buffering-budget} 을 잡고 있으면 천천히 보내는 연결 몇 개로 다른 chunked 요청을 411 로 막을 수 있다.
     *
     * <p>받은 조각은 <b>하나의 연속 배열</b>에 이어 쓰고 바로 해제한다. 조각마다 배열을 따로 두면 1바이트 청크 하나가 객체 머리 · 목록 칸까지 수십 바이트를 잡아
     * 상한 2MB 에 요청당 수십 MB 가 된다 — {@code buffering-budget} 이 막으려던 메모리 고갈이 다시 열린다.
     * 배열은 두 배씩 늘리되 상한에서 멈춘다. 그래서 청크 크기와 무관하게 상주 메모리는 상한 이하이고, 늘리는 순간에만 옛 배열과 새 배열이 함께 있어 상한의 2배 미만이다.
     * 하류로 넘길 때도 복사하지 않고 이 배열의 앞부분을 감싼다.
     *
     * <p>{@link #add} 는 Reactor 가 한 번에 하나씩 부르지만 수신 시간 초과는 다른 스레드에서 상태를 읽어 상태 필드를 volatile 로 둔다.
     */
    static final class ChunkedBody {

        /** 처음 잡는 배열 크기. 작은 본문이 상한만큼 잡지 않게 작게 시작한다. */
        private static final int INITIAL_CAPACITY = 16 * 1024;

        private final int limit;
        private final long discardLimit;
        private final Runnable onOverflow;
        private final Sinks.Empty<Void> overflowed = Sinks.empty();
        private byte[] collected = EMPTY;
        private volatile long received;
        private volatile boolean tooLarge;

        /** @param limit 본문 상한 — {@link GatewayRequestBodyProperties} 가 100MB 이하로 묶어 int 에 들어간다 */
        ChunkedBody(long limit, long discardLimit, Runnable onOverflow) {
            this.limit = Math.toIntExact(limit);
            this.discardLimit = discardLimit;
            this.onOverflow = onOverflow;
        }

        /** @return 더 읽을지. 배출 상한을 넘으면 {@code false} — 나머지는 받지 않고 끊는다 */
        boolean add(DataBuffer buffer) {
            try {
                int size = buffer.readableByteCount();
                long total = received + size;
                received = total;
                if (!tooLarge && total > limit) {
                    tooLarge = true;
                    collected = EMPTY;
                    onOverflow.run();
                    overflowed.tryEmitEmpty();
                }
                if (tooLarge) {
                    return total <= discardLimit;
                }
                int offset = (int) (total - size);
                ensureCapacity((int) total);
                buffer.read(collected, offset, size);
                return true;
            } finally {
                DataBufferUtils.release(buffer);
            }
        }

        /** 두 배씩 늘리되 상한을 넘지 않는다. 호출 쪽이 {@code needed <= limit} 을 보장한다. */
        private void ensureCapacity(int needed) {
            if (needed <= collected.length) {
                return;
            }
            long doubled = Math.max(INITIAL_CAPACITY, (long) collected.length * 2);
            int capacity = (int) Math.min(limit, Math.max(needed, doubled));
            collected = Arrays.copyOf(collected, capacity);
        }

        Mono<Void> overflowed() {
            return overflowed.asMono();
        }

        boolean tooLarge() {
            return tooLarge;
        }

        long received() {
            return received;
        }

        /** 테스트용 — 지금 잡고 있는 배열 크기. 상한을 넘지 않고, 상한을 넘은 뒤에는 0 이다. */
        int capacity() {
            return collected.length;
        }

        /** 상한 이하로 다 모은 본문의 배열. 앞 {@link #length()} 바이트만 본문이다 — 복사하지 않는다. */
        byte[] array() {
            return collected;
        }

        int length() {
            return (int) received;
        }
    }
}
