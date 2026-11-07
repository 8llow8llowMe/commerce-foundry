package com.commercefoundry.apigateway.filter;

import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

/** 상담 자격·본문을 담을 수 있는 응답은 보안·요청 제한 거절을 포함해 캐시하지 않는다. */
@Component
public class GatewaySupportCacheControlFilter implements WebFilter, Ordered {
    private static final PathPattern SUPPORT = PathPatternParser.defaultInstance.parse("/api/v1/support/**");

    @Override
    public int getOrder() {
        // 상관 ID와 함께 가장 앞에서 실행한다. 요청 가드·본문 크기·인증 거절보다 먼저 적용한다.
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (SUPPORT.matches(exchange.getRequest().getPath().pathWithinApplication())) {
            exchange.getResponse().getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");
            exchange.getResponse().beforeCommit(() -> {
                exchange.getResponse().getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-store");
                return Mono.empty();
            });
        }
        return chain.filter(exchange);
    }
}
