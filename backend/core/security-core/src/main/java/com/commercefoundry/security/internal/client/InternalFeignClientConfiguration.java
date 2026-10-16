package com.commercefoundry.security.internal.client;

import com.commercefoundry.security.internal.InternalTokenIssuer;
import feign.Capability;
import org.springframework.context.annotation.Bean;

/**
 * internal Feign 클라이언트에만 붙이는 설정. 서비스 토큰 Capability({@link InternalServiceTokenCapability})를 그 클라이언트의 자식 컨텍스트에 등록한다.
 *
 * <pre>{@code
 * @FeignClient(
 *     name = "${feign-client.target-services.product-service:product-service}",
 *     contextId = "productStockClient",
 *     configuration = InternalFeignClientConfiguration.class)
 * }</pre>
 *
 * <ul>
 *   <li>Spring Cloud OpenFeign 의 {@code FeignClientFactoryBean#configureUsingConfiguration} 이 클라이언트 컨텍스트의 {@link Capability} 빈을
 *       순서대로 {@code Feign.Builder#addCapability} 한다. 그래서 인터셉터가 아니라 Capability 로 둔다 — 최종 요청을 Client 단계에서 정리한다.</li>
 *   <li><b>전역 설정이 아니다.</b> {@code @Configuration} 을 달지 않고 서비스 스캔 범위({@code domainlayer} · {@code global}) 밖에 둔다 —
 *       스캔되면 모든 Feign 클라이언트(외부 PG 포함)에 서비스 토큰이 붙는다. {@code @EnableFeignClients(defaultConfiguration = ...)} 에도 넣지 않는다.</li>
 *   <li>발급기({@link InternalTokenIssuer})는 서비스 컨텍스트에서 받는다 — 서비스의 {@code *BeansConfig} 가 {@code InternalTokenIssuerConfigurer} 를 import 한다.</li>
 *   <li>Feign 은 security-core 가 {@code compileOnly} 로만 둔다. 이 클래스는 Feign 을 쓰는 서비스에서만 로드된다.</li>
 * </ul>
 */
public class InternalFeignClientConfiguration {

    @Bean
    public Capability internalServiceTokenCapability(InternalTokenIssuer internalTokenIssuer) {
        return new InternalServiceTokenCapability(internalTokenIssuer);
    }
}
