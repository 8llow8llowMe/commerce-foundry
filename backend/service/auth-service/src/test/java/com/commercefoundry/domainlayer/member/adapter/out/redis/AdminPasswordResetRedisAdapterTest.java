package com.commercefoundry.domainlayer.member.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.domain.model.AdminPasswordResetGrant;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@EnabledWithDockerOrOnCi
class AdminPasswordResetRedisAdapterTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    static AdminPasswordResetRedisAdapter adapter;

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        adapter = new AdminPasswordResetRedisAdapter(redis, new TokenStoreRedisSupport(Clock.systemUTC()));
    }

    @AfterAll
    static void disconnect() { factory.destroy(); }

    @Test
    void storesOnlyFingerprintAndGrantWithTtlAndExpires() {
        String fingerprint = UUID.randomUUID().toString();
        adapter.save(fingerprint, new AdminPasswordResetGrant(7, 2), Duration.ofMinutes(15));
        String key = "auth:admin-password-reset:" + fingerprint;
        assertThat(redis.opsForValue().get(key)).isEqualTo("7:2");
        assertThat(redis.getExpire(key)).isBetween(895L, 900L);
        redis.expire(key, Duration.ZERO);
        assertThat(adapter.consume(fingerprint)).isEmpty();
    }

    @Test
    void concurrentGetDelHasExactlyOneWinner() throws Exception {
        String fingerprint = UUID.randomUUID().toString();
        adapter.save(fingerprint, new AdminPasswordResetGrant(7, 2), Duration.ofMinutes(15));
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                results.add(executor.submit(() -> { start.await(); return adapter.consume(fingerprint).isPresent(); }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> result : results) { if (result.get(10, TimeUnit.SECONDS)) { winners++; } }
            assertThat(winners).isEqualTo(1);
        } finally { executor.shutdownNow(); }
    }

    @Test
    void limitIsAtomicFixedWindowAndDoesNotDependOnToken() {
        String dimension = UUID.randomUUID().toString();
        adapter.limit(dimension, 1, Duration.ofMinutes(1));
        assertThatThrownBy(() -> adapter.limit(dimension, 1, Duration.ofMinutes(1)))
            .isInstanceOfSatisfying(MemberException.class, failure -> assertThat(failure.getErrorCode()).isEqualTo(MemberErrorCode.ADMIN_PASSWORD_RESET_LIMITED));
        assertThat(redis.getExpire("auth:admin-password-reset-attempt:" + dimension)).isBetween(55L, 60L);
    }
}
