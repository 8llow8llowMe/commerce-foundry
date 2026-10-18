package com.commercefoundry.apigateway.config;

import com.commercefoundry.redis.config.RedisConfigurer;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 공통 Configurer {@code @Import} ({@code bootstrap-conventions.md}). 목록은 단순 클래스명 알파벳순, 한 줄에 하나다.
 */
@Configuration
@Import({
    RedisConfigurer.class
})
public class GatewayBeansConfig {

}
