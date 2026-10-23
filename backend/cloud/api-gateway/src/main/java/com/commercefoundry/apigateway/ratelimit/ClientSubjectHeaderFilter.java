package com.commercefoundry.apigateway.ratelimit;

import java.util.Optional;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 하류로 가는 요청에 클라이언트 주체 헤더 {@value #HEADER} 를 싣는다 ({@code security-guide.md} §7-3 "하류 헤더 계약", #135).
 *
 * <ul>
 *   <li><b>클라이언트가 보낸 같은 이름의 헤더는 언제나 지운다</b> — 계수가 꺼져 있어도, 토큰이 있는 요청이어도, 대소문자가 달라도, 여러 개여도 전부.
 *       하류는 이 헤더를 게이트웨이가 만든 값으로만 믿는다.</li>
 *   <li>{@code gateway.client-ip.enabled} 가 켜져 있고 클라이언트 IP 를 정할 수 있으면 {@link ClientSubjectResolver} 의 값(비로그인 버킷 키와 같은 HMAC)을 <b>한 개</b> 싣는다.
 *       로그인 요청에도 싣는다 — 하류(auth 시도 제한, #66)가 로그인 여부와 무관하게 IP 차원을 쓸 수 있게.</li>
 *   <li>꺼져 있으면 싣지 않는다. 하류는 헤더가 없으면 IP 차원을 건너뛴다.</li>
 * </ul>
 *
 * <p><b>라우트 전역 필터(GlobalFilter)다.</b> 하류로 나가는 요청만 바꾸면 되고, SCG 라우팅(Netty 전송)이 읽는 요청이 이 필터가 바꾼 요청이다.
 * 전달 헤더({@code X-Forwarded-*} · {@code Forwarded})는 이 필터가 다루지 않는다 — SCG(5.0.3, 자체 trusted-proxies 미설정)가 지우고 다시 쓰지 않는다.
 * 하류 전달 헤더는 신뢰하지 않고, 클라이언트 식별은 이 헤더로만 한다(§7-3).
 * 헤더 값 · IP 원문은 로그에 남기지 않는다.
 */
@Component
public class ClientSubjectHeaderFilter implements GlobalFilter, Ordered {

    /** 하류 헤더 계약의 이름. 하류 서비스가 읽는 이름이라 바꾸면 #66 쪽도 같이 바꾼다. */
    public static final String HEADER = "X-Client-Subject";

    private final ClientSubjectResolver clientSubjectResolver;

    public ClientSubjectHeaderFilter(ClientSubjectResolver clientSubjectResolver) {
        this.clientSubjectResolver = clientSubjectResolver;
    }

    /** 요청 제한({@link GatewayRateLimitFilter}, HIGHEST+100) 뒤 — 제한에 걸린 요청은 하류로 가지 않으므로 그 뒤에 꾸민다. 라우팅(가장 낮은 우선순위)보다는 한참 앞이다. */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 200;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        Optional<String> subject = clientSubjectResolver.resolve(exchange);
        // 헤더 이름은 대소문자를 가리지 않는다 — 조회 · 제거가 모두 대소문자 무관이다
        if (subject.isEmpty() && !request.getHeaders().containsHeader(HEADER)) {
            return chain.filter(exchange);
        }
        ServerHttpRequest mutated = request.mutate()
            .headers(headers -> {
                headers.remove(HEADER);
                subject.ifPresent(value -> headers.set(HEADER, value));
            })
            .build();
        return chain.filter(exchange.mutate().request(mutated).build());
    }
}
