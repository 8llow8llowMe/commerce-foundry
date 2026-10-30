package com.commercefoundry.domainlayer.member.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.member.application.model.AdminOtpChallenge;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 관리자 로그인 2단계 챌린지의 Redis 저장 (#238 PR-B) — 키 · TTL · 값 · NX · GETDEL 원자성. Docker 가 없으면 건너뛴다(CI 는 돈다). 값은 전부 테스트 전용이다.
 */
@Testcontainers
@EnabledWithDockerOrOnCi
class AdminOtpChallengeRedisAdapterTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    private static final Duration TTL = Duration.ofMinutes(5);
    private static final AdminOtpChallenge CHALLENGE = new AdminOtpChallenge(4611686018427387904L, Instant.ofEpochMilli(1793242800000L));

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static AdminOtpChallengeRedisAdapter adapter;

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        adapter = new AdminOtpChallengeRedisAdapter(redis, new TokenStoreRedisSupport(Clock.systemUTC()));
    }

    @AfterAll
    static void disconnect() {
        factory.destroy();
    }

    /** base64url 43자 모양의 테스트 해시. */
    private static String hash() {
        return (UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "")).substring(0, 43);
    }

    @Test
    @DisplayName("auth:admin-otp-challenge:{해시} 에 memberId 를 TTL 과 함께 두고, 같은 해시는 덮지 않으며, 조회는 지우지 않고 소비는 지운다")
    void saveFindConsume() {
        String hash = hash();

        assertThat(adapter.save(hash, CHALLENGE, TTL)).isTrue();
        assertThat(adapter.save(hash, new AdminOtpChallenge(1L, CHALLENGE.issuedAt()), TTL)).isFalse();

        String key = "auth:admin-otp-challenge:" + hash;
        assertThat(redis.opsForValue().get(key)).isEqualTo("4611686018427387904:1793242800000");
        assertThat(redis.getExpire(key)).isBetween(TTL.toSeconds() - 5, TTL.toSeconds());
        assertThat(adapter.find(hash)).isEqualTo(Optional.of(CHALLENGE));
        assertThat(adapter.find(hash)).isEqualTo(Optional.of(CHALLENGE));
        assertThat(adapter.consume(hash)).isEqualTo(Optional.of(CHALLENGE));
        assertThat(redis.hasKey(key)).isFalse();
        assertThat(adapter.consume(hash)).isEmpty();
        assertThat(adapter.find(hash)).isEmpty();
    }

    @Test
    @DisplayName("만료된 챌린지는 조회 · 소비 모두 비어 있다")
    void expired() {
        String hash = hash();
        adapter.save(hash, new AdminOtpChallenge(7L, CHALLENGE.issuedAt()), TTL);
        redis.expire("auth:admin-otp-challenge:" + hash, Duration.ZERO);

        assertThat(adapter.find(hash)).isEmpty();
        assertThat(adapter.consume(hash)).isEmpty();
    }

    @Test
    @DisplayName("같은 챌린지를 동시에 소비하면 한쪽만 값을 받는다(GETDEL)")
    void concurrentConsume() throws Exception {
        String hash = hash();
        adapter.save(hash, new AdminOtpChallenge(7L, CHALLENGE.issuedAt()), TTL);
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Optional<AdminOtpChallenge>>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return adapter.consume(hash);
                }));
            }
            start.countDown();
            long winners = 0;
            for (Future<Optional<AdminOtpChallenge>> result : results) {
                if (result.get(10, TimeUnit.SECONDS).isPresent()) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }
}
