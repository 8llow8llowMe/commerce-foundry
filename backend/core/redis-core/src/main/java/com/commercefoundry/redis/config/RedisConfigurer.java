package com.commercefoundry.redis.config;

import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Redis 를 쓰는 서비스의 {@code *BeansConfig} 가 {@code @Import} 한다 ({@code bootstrap-conventions.md}).
 * 컴포넌트 스캔에 걸리지 않게 {@code @Configuration} 을 달지 않는다.
 *
 * <p>접속은 Boot 의 {@code spring.data.redis.*} 자동설정(Lettuce)이 만든다. 이 설정은 기동 가드만 더한다.
 * 템플릿은 Boot 가 만드는 {@code StringRedisTemplate} · {@code ReactiveStringRedisTemplate} 만 쓴다
 * ({@code coding-conventions.md} §9-7).
 */
public class RedisConfigurer {

    /**
     * static — {@code BeanFactoryPostProcessor} 는 설정 클래스 인스턴스보다 먼저 만들어져야 한다.
     * 접속 팩토리가 생기기 전에 설정을 검사한다.
     */
    @Bean
    public static RedisStartupGuard redisStartupGuard(Environment environment) {
        return new RedisStartupGuard(environment);
    }
}
