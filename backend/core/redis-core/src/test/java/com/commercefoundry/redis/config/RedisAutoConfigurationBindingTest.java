package com.commercefoundry.redis.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.ClientOptions;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisReactiveAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 가드가 검사한 프로퍼티가 Boot 자동설정을 거쳐 Lettuce 설정에 그대로 들어가는지 본다.
 * 접속 팩토리는 첫 사용 때 연결하므로(eager 초기화 꺼짐) Redis 없이 돈다.
 */
class RedisAutoConfigurationBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().getPropertySources().replace(
            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
            new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, Map.of())))
        .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class, DataRedisReactiveAutoConfiguration.class))
        .withUserConfiguration(RedisConfigurer.class);

    @Test
    @DisplayName("timeout 은 명령 타임아웃, connect-timeout 은 소켓 연결 타임아웃, password 는 standalone 비밀번호가 된다")
    void bindsTimeoutsAndPasswordToLettuce() {
        runner.withPropertyValues(
                "spring.data.redis.host=localhost", "spring.data.redis.port=6379", "spring.data.redis.password=test-password",
                "spring.data.redis.timeout=1s", "spring.data.redis.connect-timeout=2s")
            .run(context -> {
                assertThat(context).hasNotFailed();

                LettuceConnectionFactory factory = context.getBean(LettuceConnectionFactory.class);
                LettuceClientConfiguration client = factory.getClientConfiguration();
                assertThat(client.getCommandTimeout()).isEqualTo(Duration.ofSeconds(1));
                assertThat(client.getClientOptions().map(ClientOptions::getSocketOptions))
                    .hasValueSatisfying(socket -> assertThat(socket.getConnectTimeout()).isEqualTo(Duration.ofSeconds(2)));
                assertThat(factory.getStandaloneConfiguration().getHostName()).isEqualTo("localhost");
                assertThat(factory.getStandaloneConfiguration().getPassword().map(String::new)).hasValue("test-password");
            });
    }

    @Test
    @DisplayName("Boot 가 StringRedisTemplate · ReactiveStringRedisTemplate 을 만든다 — 서블릿 · 리액티브 서비스 모두 쓸 수 있다")
    void providesStringTemplates() {
        runner.withPropertyValues(
                "spring.data.redis.password=test-password", "spring.data.redis.timeout=1s", "spring.data.redis.connect-timeout=2s")
            .run(context -> {
                assertThat(context).hasSingleBean(StringRedisTemplate.class);
                assertThat(context).hasSingleBean(ReactiveStringRedisTemplate.class);
                // JDK 직렬화 템플릿도 함께 등록된다 — 있지만 주입하지 않는다 (coding-conventions.md §9-7)
                assertThat(context).hasBean("redisTemplate").hasBean("reactiveRedisTemplate");
            });
    }

    @Test
    @DisplayName("가드에 걸리면 접속 팩토리를 만들기 전에 멈춘다 — Lettuce 클라이언트 설정 훅이 한 번도 불리지 않는다")
    void guardFailsBeforeConnectionFactory() {
        AtomicInteger valid = new AtomicInteger();
        runner.withBean(LettuceClientConfigurationBuilderCustomizer.class, () -> builder -> valid.incrementAndGet())
            .withPropertyValues(
                "spring.data.redis.password=test-password", "spring.data.redis.timeout=1s", "spring.data.redis.connect-timeout=2s")
            .run(context -> assertThat(context).hasNotFailed());
        // 대조군 — 정상 설정이면 접속 팩토리를 만들며 훅이 불린다. 그래야 아래의 0 이 의미가 있다
        assertThat(valid).hasValue(1);

        AtomicInteger blocked = new AtomicInteger();
        runner.withBean(LettuceClientConfigurationBuilderCustomizer.class, () -> builder -> blocked.incrementAndGet())
            .withPropertyValues("spring.data.redis.timeout=1s", "spring.data.redis.connect-timeout=2s")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.data.redis.password");
            });
        assertThat(blocked).hasValue(0);
    }
}
