package com.commercefoundry.security.support.archfixture.compliant.config;

import com.commercefoundry.security.support.archfixture.FakeEnableFeignClients;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 준수 예시 — {@code defaultConfiguration} 없는 Feign 활성화와, Feign 요청을 건드리지 않는 빈. */
@Configuration
@FakeEnableFeignClients
public class CompliantFeignConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
