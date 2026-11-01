package com.commercefoundry.domainlayer.member.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.security.token.JwtVerificationProperties;
import com.commercefoundry.security.token.revocation.AdminAccessCutoffKeys;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 실제 Redis에서 컷오프 최댓값과 TTL을 확인한다. Docker 없는 로컬은 건너뛰고 CI에서는 실행한다. */
@Testcontainers
@EnabledWithDockerOrOnCi
class AdminAccessCutoffRedisAdapterTest {

    private static final String PASSWORD = "auth-cutoff-test-only";
    private static final long MEMBER_ID = 998877L;

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
        .withCommand("redis-server", "--requirepass", PASSWORD)
        .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;
    private AdminAccessCutoffRedisAdapter adapter;
    private String key;

    @BeforeAll
    static void connect() {
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        standalone.setPassword(PASSWORD);
        connectionFactory = new LettuceConnectionFactory(standalone,
            LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(5)).build());
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redisTemplate = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void setUp() {
        key = AdminAccessCutoffKeys.of(MEMBER_ID).value();
        redisTemplate.delete(key);
        adapter = new AdminAccessCutoffRedisAdapter(redisTemplate,
            new JwtVerificationProperties("test-issuer", "test-audience", Duration.ofMinutes(1), null, null),
            new TokenStoreRedisSupport(Clock.systemUTC()));
    }

    @Test
    @DisplayName("늦게 도착한 옛 컷오프는 새 값을 덮지 않고 남은 TTL도 늘리지 않는다")
    void olderWriteDoesNotRollBackCutoffOrExtendTtl() {
        Instant old = Instant.parse("2026-10-29T03:00:00Z");
        Instant newer = old.plusSeconds(10);
        adapter.cutOff(MEMBER_ID, newer);
        redisTemplate.expire(key, Duration.ofMinutes(1));

        adapter.cutOff(MEMBER_ID, old);

        assertThat(adapter.findCutoff(MEMBER_ID)).contains(newer);
        assertThat(redisTemplate.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, Duration.ofMinutes(1).toMillis());

        adapter.cutOff(MEMBER_ID, newer.plusSeconds(1));
        assertThat(adapter.findCutoff(MEMBER_ID)).contains(newer.plusSeconds(1));
        assertThat(redisTemplate.getExpire(key, TimeUnit.MILLISECONDS)).isGreaterThan(Duration.ofMinutes(1).toMillis());
    }

    @Test
    @DisplayName("저장된 값이 손상됐으면 덮지 않고 AUTH_013 fail-closed 다")
    void corruptedValueFailsClosed() {
        redisTemplate.opsForValue().set(key, "corrupt", Duration.ofMinutes(1));

        assertThatThrownBy(() -> adapter.cutOff(MEMBER_ID, Instant.now()))
            .isInstanceOf(MemberException.class).extracting("errorCode").isEqualTo(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("corrupt");
    }
}
