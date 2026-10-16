package com.commercefoundry.security.internal.client;

import com.commercefoundry.security.internal.InternalTokenIssuer;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import feign.Target;

/**
 * internal Feign 요청에 서비스 토큰을 싣는다 ({@code security-guide.md} §9, {@code coding-conventions.md} §10).
 *
 * <ul>
 *   <li>{@code aud} 는 Feign 대상 이름({@code @FeignClient(name = ...)} = Eureka 서비스 ID)이다. 비어 있으면 요청을 보내지 않고 실패한다.</li>
 *   <li>기존 {@code Authorization} 헤더를 <b>지운 뒤</b> {@code Bearer <서비스 토큰>} 을 넣는다 — 사용자 access 토큰이나 다른 인터셉터가 넣은 값을
 *       internal 로 넘기지 않는다. 사용자 식별이 필요하면 요청 본문 · 파라미터로 memberId 를 넘긴다. Feign 헤더 맵은 대소문자를 가리지 않는다.</li>
 *   <li>요청마다 새로 서명한다. 캐시하지 않고, 401 을 받아도 다시 발급해 재시도하지 않는다 ({@link InternalTokenIssuer}).</li>
 * </ul>
 *
 * <p>전역 빈으로 등록하지 않는다 — {@link InternalFeignClientConfiguration} 으로 internal 클라이언트에만 붙인다.
 * 전역이면 외부 API(토스 등) 호출에 서비스 토큰이 실리고 그쪽 {@code Authorization} 을 덮어쓴다.
 */
public final class InternalServiceTokenInterceptor implements RequestInterceptor {

    static final String AUTHORIZATION = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final InternalTokenIssuer issuer;

    public InternalServiceTokenInterceptor(InternalTokenIssuer issuer) {
        this.issuer = issuer;
    }

    @Override
    public void apply(RequestTemplate template) {
        Target<?> target = template.feignTarget();
        String audience = target == null ? null : target.name();
        if (audience == null || audience.isBlank()) {
            throw new IllegalStateException("Feign 대상 이름이 없어 서비스 토큰의 aud 를 정할 수 없습니다."
                + " internal 클라이언트는 @FeignClient(name = 대상 서비스 ID) 로 선언합니다 (coding-conventions.md §10)");
        }
        template.removeHeader(AUTHORIZATION);
        template.header(AUTHORIZATION, BEARER_PREFIX + issuer.issue(audience));
    }
}
