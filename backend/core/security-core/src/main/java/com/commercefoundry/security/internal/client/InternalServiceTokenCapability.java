package com.commercefoundry.security.internal.client;

import com.commercefoundry.security.internal.InternalTokenIssuer;
import feign.Capability;
import feign.Client;
import feign.Request;
import feign.RequestTemplate;
import feign.Response;
import feign.Target;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.core.Ordered;

/**
 * internal Feign 클라이언트의 <b>최종 요청</b>에 서비스 토큰을 싣는 Feign {@link Capability} ({@code security-guide.md} §9-5, {@code coding-conventions.md} §10).
 *
 * <p>요청 인터셉터 단계가 아니라 {@link Client} 단계에서 바꾼다. 인터셉터는 순서대로 돌아서, 자기보다 뒤에 붙은 인터셉터(부모 컨텍스트의 전역 인터셉터 ·
 * {@code defaultConfiguration} · {@code spring.cloud.openfeign.client.config.default.request-interceptors})가 넣은 {@code Authorization} 을 지울 수 없다 —
 * 헤더가 두 개가 되면 받는 쪽은 첫 헤더만 읽어 사용자 토큰 · 외부 키가 internal 로 새고도 조용히 성공할 수 있다.
 * 감싼 {@link Client} 는 인터셉터 · 대상 URL 적용이 모두 끝난 {@link Request} 를 받는다.
 *
 * <ul>
 *   <li>최종 요청의 {@code Authorization} 을 대소문자와 무관하게 <b>전부 지우고</b> {@code Bearer <서비스 토큰>} 하나만 싣는다.
 *       사용자 식별이 필요하면 요청 본문 · 파라미터로 memberId 를 넘긴다.</li>
 *   <li>{@code aud} 는 Feign 대상 이름({@code @FeignClient(name = ...)} = Eureka 서비스 ID)이다. 대상이 없거나 이름이 비면 요청을 보내지 않고 실패한다.
 *       서비스 ID 모양(소문자 kebab)이 아니면 발급기가 거부한다. 다만 kebab 이름을 가진 외부 클라이언트({@code name = "toss-payments", url = ...})에
 *       잘못 붙이면 막지 못한다 — 외부 클라이언트에 붙이지 않는 것은 리뷰가 본다.</li>
 *   <li>재시도를 포함해 요청마다 새로 서명한다. 캐시하지 않고, 401 을 받아도 다시 발급해 재시도하지 않는다 ({@link InternalTokenIssuer}).</li>
 *   <li>{@link Ordered#HIGHEST_PRECEDENCE} — Spring Cloud OpenFeign 은 Capability 를 순서대로 더하고 나중 것이 바깥을 감싼다. 가장 먼저 더해져
 *       실제 Client(로드밸런서)를 바로 감싸야, 다른 Capability 가 Client 단계에서 더한 헤더까지 마지막에 정리한다.</li>
 * </ul>
 *
 * <p>전역 빈으로 등록하지 않는다 — {@link InternalFeignClientConfiguration} 으로 internal 클라이언트에만 붙인다.
 */
public final class InternalServiceTokenCapability implements Capability, Ordered {

    static final String AUTHORIZATION = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final InternalTokenIssuer issuer;

    public InternalServiceTokenCapability(InternalTokenIssuer issuer) {
        this.issuer = issuer;
    }

    @Override
    public Client enrich(Client client) {
        return new ServiceTokenClient(client, this);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /** 최종 요청에서 {@code Authorization} 을 모두 지우고 서비스 토큰 하나를 싣는다. 나머지(메서드 · URL · 헤더 · 본문)는 그대로다. */
    Request withServiceToken(Request request) {
        RequestTemplate template = request.requestTemplate();
        Target<?> target = template == null ? null : template.feignTarget();
        String audience = target == null ? null : target.name();
        if (audience == null || audience.isBlank()) {
            throw new IllegalStateException("Feign 대상 이름이 없어 서비스 토큰의 aud 를 정할 수 없습니다."
                + " internal 클라이언트는 @FeignClient(name = 대상 서비스 ID) 로 선언합니다 (coding-conventions.md §10)");
        }
        Map<String, Collection<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        request.headers().forEach((name, values) -> {
            if (!AUTHORIZATION.equalsIgnoreCase(name)) {
                headers.put(name, values);
            }
        });
        headers.put(AUTHORIZATION, List.of(BEARER_PREFIX + issuer.issue(audience)));
        return Request.create(request.httpMethod(), request.url(), headers, request.body(), request.charset(), template);
    }

    /** 실제 Client 를 감싼다. 이름이 있는 클래스로 둬서 스택 트레이스에서 보이게 한다. */
    private static final class ServiceTokenClient implements Client {

        private final Client delegate;
        private final InternalServiceTokenCapability capability;

        private ServiceTokenClient(Client delegate, InternalServiceTokenCapability capability) {
            this.delegate = delegate;
            this.capability = capability;
        }

        @Override
        public Response execute(Request request, Request.Options options) throws IOException {
            return delegate.execute(capability.withServiceToken(request), options);
        }
    }
}
