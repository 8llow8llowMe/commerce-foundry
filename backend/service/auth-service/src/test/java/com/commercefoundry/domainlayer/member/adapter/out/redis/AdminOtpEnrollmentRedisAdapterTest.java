package com.commercefoundry.domainlayer.member.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.commercefoundry.domainlayer.member.adapter.out.crypto.AdminOtpSecretCipher;
import com.commercefoundry.domainlayer.member.adapter.out.crypto.AdminOtpSecretCipherTest;
import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 확인 전 OTP 등록의 Redis 저장 (#238 PR-A2) — 키 · TTL · 암호문만 저장 · 덮어쓰기 · 다른 회원 거절. Docker 가 없으면 건너뛴다(CI 는 돈다). 값은 전부 테스트 전용이다.
 */
@Testcontainers
@EnabledWithDockerOrOnCi
class AdminOtpEnrollmentRedisAdapterTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    private static final byte[] SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    private static final Duration TTL = Duration.ofMinutes(15);

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    private final AdminOtpSecretCipher cipher = AdminOtpSecretCipherTest.cipher(AdminOtpSecretCipherTest.KEY, null);
    private AdminOtpEnrollmentRedisAdapter adapter;

    @BeforeEach
    void setup() {
        adapter = new AdminOtpEnrollmentRedisAdapter(redis, cipher, new TokenStoreRedisSupport(Clock.systemUTC()));
    }

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

    private static long memberId() {
        return ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    }

    @Test
    @DisplayName("auth:admin-otp-enrollment:{memberId} 에 TTL 과 함께 {keyId}:{암호문} 만 저장하고, 읽으면 원문이다")
    void saveAndFind() {
        long memberId = memberId();

        adapter.save(memberId, SECRET, TTL);

        String key = "auth:admin-otp-enrollment:" + memberId;
        String stored = redis.opsForValue().get(key);
        assertThat(stored).startsWith(cipher.currentKeyId() + ":").doesNotContain("1234567890");
        assertThat(redis.getExpire(key)).isBetween(TTL.toSeconds() - 5, TTL.toSeconds());
        assertThat(adapter.find(memberId)).hasValueSatisfying(secret -> assertThat(secret).isEqualTo(SECRET));
    }

    @Test
    @DisplayName("같은 관리자가 다시 시작하면 덮어쓴다 · 지우면 비어 있다 · 만료되면 비어 있다")
    void overwriteDeleteAndExpire() {
        long memberId = memberId();
        byte[] next = "abcdefghijabcdefghij".getBytes(StandardCharsets.US_ASCII);

        adapter.save(memberId, SECRET, TTL);
        adapter.save(memberId, next, TTL);
        assertThat(adapter.find(memberId)).hasValueSatisfying(secret -> assertThat(secret).isEqualTo(next));

        adapter.delete(memberId);
        assertThat(adapter.find(memberId)).isEmpty();
        adapter.delete(memberId);

        adapter.save(memberId, SECRET, TTL);
        redis.expire("auth:admin-otp-enrollment:" + memberId, Duration.ZERO);
        assertThat(adapter.find(memberId)).isEmpty();
    }

    @Test
    @DisplayName("다른 관리자 키로 암호문을 옮겨 붙이면 AAD 가 달라 AUTH_039 다. 모양이 깨진 값도 AUTH_039 이고 값을 싣지 않는다")
    void otherMemberCiphertextRejected() {
        long owner = memberId();
        long attacker = owner == 1 ? 2 : owner - 1;
        adapter.save(owner, SECRET, TTL);
        redis.opsForValue().set("auth:admin-otp-enrollment:" + attacker, redis.opsForValue().get("auth:admin-otp-enrollment:" + owner), TTL);

        assertUnavailable(catchThrowable(() -> adapter.find(attacker)));

        redis.opsForValue().set("auth:admin-otp-enrollment:" + attacker, "no-separator", TTL);
        Throwable malformed = catchThrowable(() -> adapter.find(attacker));
        assertUnavailable(malformed);
        assertThat(malformed.getMessage()).doesNotContain("no-separator");
    }

    @Test
    @DisplayName("Redis 에 닿지 못하면 AUTH_013 이다")
    void redisFailureBecomesUnavailable() {
        LettuceConnectionFactory closed = new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1", 1));
        closed.afterPropertiesSet();
        closed.start();
        try {
            AdminOtpEnrollmentRedisAdapter down = new AdminOtpEnrollmentRedisAdapter(new StringRedisTemplate(closed), cipher,
                new TokenStoreRedisSupport(Clock.systemUTC()));

            Throwable thrown = catchThrowable(() -> down.find(1L));

            assertThat(thrown).isInstanceOf(MemberException.class);
            assertThat(((MemberException) thrown).getErrorCode()).isEqualTo(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
        } finally {
            closed.destroy();
        }
    }

    private static void assertUnavailable(Throwable thrown) {
        assertThat(thrown).isInstanceOf(MemberException.class);
        assertThat(((MemberException) thrown).getErrorCode()).isEqualTo(MemberErrorCode.OTP_UNAVAILABLE);
    }
}
