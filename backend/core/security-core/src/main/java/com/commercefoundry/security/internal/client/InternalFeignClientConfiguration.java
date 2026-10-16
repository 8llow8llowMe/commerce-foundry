package com.commercefoundry.security.internal.client;

import com.commercefoundry.security.internal.InternalTokenIssuer;
import feign.RequestInterceptor;
import org.springframework.context.annotation.Bean;

/**
 * internal Feign 클라이언트에만 붙이는 설정. 서비스 토큰 인터셉터({@link InternalServiceTokenInterceptor})를 그 클라이언트의 자식 컨텍스트에 등록한다.
 *
 * <pre>{@code
 * @FeignClient(
 *     name = "${feign-client.target-services.product-service:product-service}",
 *     contextId = "productStockClient",
 *     configuration = InternalFeignClientConfiguration.class)
 * }</pre>
 *
 * <ul>
 *   <li><b>전역 설정이 아니다.</b> {@code @Configuration} 을 달지 않고 서비스 스캔 범위({@code domainlayer} · {@code global}) 밖에 둔다 —
 *       스캔되면 모든 Feign 클라이언트(외부 PG 포함)에 인터셉터가 붙는다. 같은 이유로 {@code RequestInterceptor} 를 서비스 빈으로 등록하지 않는다.</li>
 *   <li>발급기({@link InternalTokenIssuer})는 서비스 컨텍스트에서 받는다 — 서비스의 {@code *BeansConfig} 가 {@code InternalTokenIssuerConfigurer} 를 import 한다.</li>
 *   <li>Feign 은 security-core 가 {@code compileOnly} 로만 둔다. 이 클래스는 Feign 을 쓰는 서비스에서만 로드된다.</li>
 * </ul>
 */
public class InternalFeignClientConfiguration {

    @Bean
    public RequestInterceptor internalServiceTokenInterceptor(InternalTokenIssuer internalTokenIssuer) {
        return new InternalServiceTokenInterceptor(internalTokenIssuer);
    }
}
