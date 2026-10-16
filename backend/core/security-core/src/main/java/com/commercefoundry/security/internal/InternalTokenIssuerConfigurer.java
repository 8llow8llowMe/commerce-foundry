package com.commercefoundry.security.internal;

import java.time.Clock;
import org.springframework.context.annotation.Bean;

/**
 * internal API 를 <b>부르는</b> 서비스의 {@code *BeansConfig} 가 {@code @Import} 한다 — internal Feign 클라이언트를 처음 만드는 이슈에서 붙인다.
 * 받는 쪽 검증은 {@code resourceserver.InternalResourceServerConfigurer} 다.
 * 컴포넌트 스캔에 걸리지 않게 {@code @Configuration} 을 달지 않는다.
 *
 * <p>{@code internal.client.InternalFeignClientConfiguration} 이 이 빈을 쓴다. 빠뜨리면 Feign 클라이언트를 만들 때 기동이 멈춘다.
 */
public class InternalTokenIssuerConfigurer {

    @Bean
    public InternalTokenIssuer internalTokenIssuer(InternalTokenProperties internalTokenProperties) {
        return InternalTokenIssuer.create(internalTokenProperties, Clock.systemUTC());
    }
}
