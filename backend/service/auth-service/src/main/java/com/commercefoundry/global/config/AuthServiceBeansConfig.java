package com.commercefoundry.global.config;

import com.commercefoundry.common.config.SwaggerSecurityConfigurer;
import com.commercefoundry.persistence.config.QuerydslConfigurer;
import com.commercefoundry.persistence.config.SnowflakeConfigurer;
import com.commercefoundry.redis.config.RedisConfigurer;
import com.commercefoundry.security.issuer.JwtIssuerConfigurer;
import com.commercefoundry.security.resourceserver.InternalResourceServerConfigurer;
import com.commercefoundry.security.resourceserver.ResourceServerSecurityConfigurer;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
@Import({
    InternalResourceServerConfigurer.class,
    JwtIssuerConfigurer.class,
    QuerydslConfigurer.class,
    RedisConfigurer.class,
    ResourceServerSecurityConfigurer.class,
    SnowflakeConfigurer.class,
    SwaggerSecurityConfigurer.class
})
public class AuthServiceBeansConfig {

    /**
     * 비밀번호 해시는 BCrypt(기본 강도 10) 하나다. {@code PasswordEncoderFactories} 의 위임 인코더({@code {bcrypt}...} 접두어)를 쓰지 않는다 —
     * 해시 방식을 바꿀 일이 생기면 그때 저장 형식과 이관을 함께 정한다. 해시(60자)는 {@code member.password_hash}(100자)에 들어간다.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** refresh 토큰 회전 시각 · 재사용 유예 판정의 기준 시계. 테스트는 고정 시계로 바꿔 끼운다. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
