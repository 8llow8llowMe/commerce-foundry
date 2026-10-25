package com.commercefoundry.apigateway.filter;

import com.commercefoundry.common.log.CorrelationIds;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Mono;

/**
 * 게이트웨이에서 상관 id 를 정하고 하류 요청과 이 요청의 로그에 싣는다 (#284, {@code coding-conventions.md} §8).
 *
 * <p>헤더가 UUID 하나면 소문자로 맞추고, 없거나 둘 이상이거나 형식이 틀리면 새로 만든다. 클라이언트가 보낸 같은 이름 헤더는 지우고 고른 값 하나만 넘긴다.
 * 틀린 원문은 로그에 남기지 않는다. 응답 헤더 · 봉투에는 싣지 않는다.
 *
 * <p>가장 앞이다. 가드 · 본문 크기 거절 로그도 같은 id 를 가진다. Reactor 가 스레드를 바꿔도 MDC 가 따라가게 컨텍스트에 넣는다
 * ({@link CorrelationIdReactorConfiguration}).
 */
@Component
public class GatewayCorrelationIdFilter implements WebFilter, Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId = CorrelationIds.acceptOrCreate(singleHeader(exchange.getRequest()));
        ServerHttpRequest mutated = exchange.getRequest().mutate().headers(headers -> {
            headers.remove(CorrelationIds.HEADER);
            headers.set(CorrelationIds.HEADER, correlationId);
        }).build();
        ServerWebExchange forwarded = exchange.mutate().request(mutated).build();
        forwarded.getAttributes().put(CorrelationIds.REQUEST_ATTRIBUTE, correlationId);
        return new SubscribeThreadMdcMono(chain, forwarded, correlationId);
    }

    /**
     * 구독 스레드에만 MDC 를 올렸다가, 그 구독이 반환되면 같은 스레드에서 지운다.
     * {@code doFinally} 는 다른 스레드에서 돌아 이벤트 루프에 이전 요청의 id 가 남을 수 있다.
     * 다음 필터는 이 구독 안에서 돈다. 그 뒤 시그널은 Reactor 컨텍스트 전파가 MDC 를 채운다.
     */
    private static final class SubscribeThreadMdcMono extends Mono<Void> {

        private final WebFilterChain chain;
        private final ServerWebExchange exchange;
        private final String correlationId;

        private SubscribeThreadMdcMono(WebFilterChain chain, ServerWebExchange exchange, String correlationId) {
            this.chain = chain;
            this.exchange = exchange;
            this.correlationId = correlationId;
        }

        @Override
        public void subscribe(CoreSubscriber<? super Void> actual) {
            MDC.put(CorrelationIds.MDC_KEY, correlationId);
            try {
                chain.filter(exchange)
                    .contextWrite(context -> context.put(CorrelationIds.MDC_KEY, correlationId))
                    .subscribe(actual);
            } finally {
                MDC.remove(CorrelationIds.MDC_KEY);
            }
        }
    }

    /** 값이 정확히 하나일 때만 그 문자열. 없거나 둘 이상이면 {@code null}. */
    private static String singleHeader(ServerHttpRequest request) {
        List<String> values = request.getHeaders().get(CorrelationIds.HEADER);
        if (values == null || values.size() != 1) {
            return null;
        }
        return values.getFirst();
    }
}
