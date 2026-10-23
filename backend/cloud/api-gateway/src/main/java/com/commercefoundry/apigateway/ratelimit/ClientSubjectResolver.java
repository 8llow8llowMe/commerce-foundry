package com.commercefoundry.apigateway.ratelimit;

import com.commercefoundry.apigateway.properties.GatewayClientIpProperties;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

/**
 * 요청의 클라이언트 주체 — 클라이언트 IP 를 {@link ClientIpResolver#bucketOf} 로 묶은 뒤 {@link ClientIpHasher} 로 바꾼 HMAC(base64url 22자) ({@code security-guide.md} §7-3).
 * 비로그인 버킷 키({@link GatewayRateLimitFilter})와 하류 헤더 {@code X-Client-Subject}({@link ClientSubjectHeaderFilter})가 <b>같은 값</b>을 쓰도록 한 곳에서 만든다.
 *
 * <ul>
 *   <li>{@code gateway.client-ip.enabled} 가 꺼져 있으면(기본) IP 를 해석 · 해시하지 않고 비어 있다 — 꺼진 해시기는 키가 없다.</li>
 *   <li>원격 주소조차 모르면 비어 있다.</li>
 *   <li>한 요청에서 두 번 계산하지 않도록 결과(빈 값 포함)를 exchange 속성에 둔다. 두 필터가 같은 요청에서 부른다.</li>
 * </ul>
 *
 * <p>IP 원문은 이 클래스 밖으로 나가지 않는다.
 */
@Component
public class ClientSubjectResolver {

    static final String ATTRIBUTE = ClientSubjectResolver.class.getName() + ".subject";

    private final GatewayClientIpProperties properties;
    private final ClientIpResolver clientIpResolver;
    private final ClientIpHasher clientIpHasher;

    public ClientSubjectResolver(GatewayClientIpProperties properties, ClientIpResolver clientIpResolver, ClientIpHasher clientIpHasher) {
        this.properties = properties;
        this.clientIpResolver = clientIpResolver;
        this.clientIpHasher = clientIpHasher;
    }

    /** @return {@code gateway.client-ip.enabled} — 꺼져 있으면 {@link #resolve} 는 늘 비어 있다 */
    public boolean enabled() {
        return properties.enabled();
    }

    /** @return 클라이언트 IP 의 HMAC. 꺼져 있거나 원격 주소를 모르면 비어 있다 */
    public Optional<String> resolve(ServerWebExchange exchange) {
        if (!properties.enabled()) {
            return Optional.empty();
        }
        Optional<String> cached = exchange.getAttribute(ATTRIBUTE);
        if (cached != null) {
            return cached;
        }
        Optional<String> subject = clientIpResolver.resolve(exchange.getRequest())
            .map(ClientIpResolver::bucketOf)
            .map(clientIpHasher::hash);
        exchange.getAttributes().put(ATTRIBUTE, subject);
        return subject;
    }
}
