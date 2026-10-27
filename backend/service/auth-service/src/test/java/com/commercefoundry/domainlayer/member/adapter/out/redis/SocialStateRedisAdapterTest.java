package com.commercefoundry.domainlayer.member.adapter.out.redis;

import static org.assertj.core.api.Assertions.*;

import com.commercefoundry.domainlayer.member.domain.model.SocialProvider;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@EnabledWithDockerOrOnCi
class SocialStateRedisAdapterTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private SocialStateRedisAdapter adapter;

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
    }

    @AfterAll
    static void disconnect() {
        factory.destroy();
    }

    @BeforeEach
    void setup() {
        adapter = new SocialStateRedisAdapter(redis, new TokenStoreRedisSupport(Clock.systemUTC()));
    }

    @Test
    void wrongProviderOrVerifierDoesNotConsumeAndReplayFails() {
        String state = UUID.randomUUID().toString();
        assertThat(adapter.save(state, SocialProvider.KAKAO, "hash", Duration.ofMinutes(5))).isTrue();
        assertThat(redis.getExpire("auth:oauth-state:" + state)).isBetween(1L, 300L);
        assertThat(adapter.consume(state, SocialProvider.NAVER, "hash")).isFalse();
        assertThat(adapter.consume(state, SocialProvider.KAKAO, "wrong")).isFalse();
        assertThat(adapter.consume(state, SocialProvider.KAKAO, "hash")).isTrue();
        assertThat(adapter.consume(state, SocialProvider.KAKAO, "hash")).isFalse();
    }

    @Test
    void expiredStateCannotBeUsed() {
        String state = UUID.randomUUID().toString();
        adapter.save(state, SocialProvider.NAVER, "hash", Duration.ofMinutes(5));
        redis.expire("auth:oauth-state:" + state, Duration.ZERO);
        assertThat(adapter.consume(state, SocialProvider.NAVER, "hash")).isFalse();
    }

    @Test
    void concurrentConsumeHasExactlyOneWinner() throws Exception {
        String state = UUID.randomUUID().toString();
        adapter.save(state, SocialProvider.KAKAO, "hash", Duration.ofMinutes(5));
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> one = executor.submit(() -> { start.await(); return adapter.consume(state, SocialProvider.KAKAO, "hash"); });
            Future<Boolean> two = executor.submit(() -> { start.await(); return adapter.consume(state, SocialProvider.KAKAO, "hash"); });
            start.countDown();
            assertThat(java.util.List.of(one.get(5, TimeUnit.SECONDS), two.get(5, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
    }
}