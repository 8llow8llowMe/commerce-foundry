package com.commercefoundry.domainlayer.member.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenRotation;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenRotationPolicy;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenSession;
import com.commercefoundry.security.issuer.OpaqueTokenGenerator;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
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

/**
 * Redis 컨테이너(requirepass)에서 Lua 스크립트 · 키 · TTL 을 실제로 돌린다 ({@code coding-conventions.md} §9-7).
 * Docker 없는 로컬은 건너뛰고, CI 에서는 Docker 가 없으면 실패한다 ({@link EnabledWithDockerOrOnCi}).
 */
@Testcontainers
@EnabledWithDockerOrOnCi
class RefreshTokenRedisAdapterTest {

    /** 테스트 전용 값이다. 비밀이 아니다. */
    private static final String PASSWORD = "auth-redis-test-only";
    private static final Duration TTL = Duration.ofDays(14);
    private static final Duration GRACE = Duration.ofSeconds(30);
    private static final RefreshTokenRotationPolicy POLICY = new RefreshTokenRotationPolicy(GRACE, 3, TTL);
    private static final Instant NOW = Instant.parse("2026-10-18T03:00:00Z");
    private static final long MEMBER_ID = 4611686018427387904L;

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
        .withCommand("redis-server", "--requirepass", PASSWORD)
        .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redisTemplate;

    private final OpaqueTokenGenerator tokens = new OpaqueTokenGenerator();
    private RefreshTokenRedisAdapter adapter;

    @BeforeAll
    static void connect() {
        connectionFactory = connectionFactory(PASSWORD);
        redisTemplate = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void disconnect() {
        connectionFactory.destroy();
    }

    @BeforeEach
    void setUp() {
        adapter = new RefreshTokenRedisAdapter(redisTemplate);
    }

    @Test
    @DisplayName("로그인 저장 — 토큰 HASH {memberId, familyId} 와 family \"1\" 이 둘 다 refresh TTL 로 생기고 rotatedAt 은 없다")
    void saveNewFamily() {
        String fingerprint = fingerprint();
        String familyId = familyId();

        adapter.saveNewFamily(fingerprint, MEMBER_ID, familyId, TTL);

        Map<Object, Object> hash = redisTemplate.opsForHash().entries(tokenKey(fingerprint));
        assertThat(hash).containsOnly(Map.entry("memberId", String.valueOf(MEMBER_ID)), Map.entry("familyId", familyId));
        assertThat(redisTemplate.opsForValue().get(familyKey(familyId))).isEqualTo("1");
        assertTtlAbout(tokenKey(fingerprint), TTL);
        assertTtlAbout(familyKey(familyId), TTL);
        assertThat(adapter.findSession(fingerprint)).contains(new RefreshTokenSession(fingerprint, MEMBER_ID, familyId));
    }

    @Test
    @DisplayName("정상 회전 — 옛 토큰에 rotatedAt=지금(ms) · TTL 은 그대로, 새 토큰은 같은 family 로 TTL 과 함께, family TTL 은 다시 refresh TTL 로 늘어난다")
    void rotate() {
        RefreshTokenSession session = login();
        redisTemplate.expire(familyKey(session.familyId()), Duration.ofDays(1));
        redisTemplate.expire(tokenKey(session.fingerprint()), Duration.ofDays(1));
        String next = fingerprint();

        assertThat(adapter.rotate(session, next, NOW, POLICY)).isEqualTo(RefreshTokenRotation.ROTATED);

        assertThat(redisTemplate.opsForHash().get(tokenKey(session.fingerprint()), "rotatedAt")).isEqualTo(String.valueOf(NOW.toEpochMilli()));
        assertTtlAbout(tokenKey(session.fingerprint()), Duration.ofDays(1));
        assertThat(redisTemplate.opsForHash().entries(tokenKey(next)))
            .containsOnly(Map.entry("memberId", String.valueOf(MEMBER_ID)), Map.entry("familyId", session.familyId()));
        assertTtlAbout(tokenKey(next), TTL);
        assertTtlAbout(familyKey(session.familyId()), TTL);
        assertThat(adapter.findSession(next)).isPresent();
    }

    @Test
    @DisplayName("유예 안 재사용 — 형제 토큰을 저장하고 family TTL 을 늘리며, 옛 토큰의 rotatedAt 은 그대로다(유예가 미끄러지지 않는다)")
    void reuseInGrace() {
        RefreshTokenSession session = login();
        adapter.rotate(session, fingerprint(), NOW, POLICY);
        redisTemplate.expire(familyKey(session.familyId()), Duration.ofDays(1));
        String sibling = fingerprint();

        assertThat(adapter.rotate(session, sibling, NOW.plus(GRACE), POLICY)).isEqualTo(RefreshTokenRotation.REISSUED_IN_GRACE);

        assertThat(redisTemplate.opsForHash().get(tokenKey(session.fingerprint()), "rotatedAt")).isEqualTo(String.valueOf(NOW.toEpochMilli()));
        assertThat(redisTemplate.opsForHash().get(tokenKey(session.fingerprint()), "siblings")).isEqualTo("1");
        assertThat(adapter.findSession(sibling)).isPresent();
        assertTtlAbout(tokenKey(sibling), TTL);
        assertTtlAbout(familyKey(session.familyId()), TTL);
        assertThat(adapter.rotate(session, fingerprint(), NOW.plus(GRACE).plusMillis(1), POLICY))
            .as("첫 회전부터 잰다 — 형제 발급이 창을 늘리지 않는다").isEqualTo(RefreshTokenRotation.REUSE_DETECTED);
    }

    @Test
    @DisplayName("지금이 rotatedAt 보다 이르면(인스턴스 간 시계 차이) 0 으로 보고 형제 발급이다")
    void negativeElapsedIsZero() {
        RefreshTokenSession session = login();
        adapter.rotate(session, fingerprint(), NOW, POLICY);

        assertThat(adapter.rotate(session, fingerprint(), NOW.minusSeconds(5), POLICY)).isEqualTo(RefreshTokenRotation.REISSUED_IN_GRACE);
    }

    @Test
    @DisplayName("형제는 토큰당 상한(3)까지 — 넷째는 family 를 지우고 REUSE_DETECTED 다")
    void siblingCap() {
        RefreshTokenSession session = login();
        adapter.rotate(session, fingerprint(), NOW, POLICY);
        for (int i = 0; i < 3; i++) {
            assertThat(adapter.rotate(session, fingerprint(), NOW, POLICY)).isEqualTo(RefreshTokenRotation.REISSUED_IN_GRACE);
        }

        assertThat(adapter.rotate(session, fingerprint(), NOW, POLICY)).isEqualTo(RefreshTokenRotation.REUSE_DETECTED);
        assertThat(redisTemplate.hasKey(familyKey(session.familyId()))).isFalse();
    }

    @Test
    @DisplayName("유예 밖 재사용 — family 를 지우고, 그 family 의 형제 · 새 토큰까지 무효가 된다")
    void reuseOutsideGraceRevokesFamily() {
        RefreshTokenSession session = login();
        String next = fingerprint();
        adapter.rotate(session, next, NOW, POLICY);
        RefreshTokenSession nextSession = adapter.findSession(next).orElseThrow();

        assertThat(adapter.rotate(session, fingerprint(), NOW.plus(GRACE).plusMillis(1), POLICY)).isEqualTo(RefreshTokenRotation.REUSE_DETECTED);

        assertThat(redisTemplate.hasKey(familyKey(session.familyId()))).isFalse();
        assertThat(adapter.findSession(next)).isEmpty();
        assertThat(adapter.rotate(nextSession, fingerprint(), NOW.plusSeconds(60), POLICY)).isEqualTo(RefreshTokenRotation.INVALID);
    }

    @Test
    @DisplayName("rotatedAt 이 숫자가 아니면(손상) 재사용으로 보고 family 를 지운다")
    void corruptRotatedAtIsReuse() {
        RefreshTokenSession session = login();
        redisTemplate.opsForHash().put(tokenKey(session.fingerprint()), "rotatedAt", "not-a-number");

        assertThat(adapter.rotate(session, fingerprint(), NOW, POLICY)).isEqualTo(RefreshTokenRotation.REUSE_DETECTED);
        assertThat(redisTemplate.hasKey(familyKey(session.familyId()))).isFalse();
    }

    @Test
    @DisplayName("family 를 지우면(로그아웃) 그 family 토큰은 읽기 · 회전 모두 무효이고 아무것도 쓰지 않는다")
    void revokedFamilyIsInvalid() {
        RefreshTokenSession session = login();

        adapter.revokeFamily(session.familyId());

        assertThat(adapter.findSession(session.fingerprint())).isEmpty();
        String next = fingerprint();
        assertThat(adapter.rotate(session, next, NOW, POLICY)).isEqualTo(RefreshTokenRotation.INVALID);
        assertThat(redisTemplate.hasKey(tokenKey(next))).isFalse();
        assertThat(redisTemplate.opsForHash().hasKey(tokenKey(session.fingerprint()), "rotatedAt")).isFalse();
    }

    @Test
    @DisplayName("없는 토큰 · 읽은 값과 다른 memberId · familyId 는 INVALID 이고 아무것도 쓰지 않는다")
    void mismatchIsInvalid() {
        RefreshTokenSession session = login();
        String next = fingerprint();

        assertThat(adapter.rotate(new RefreshTokenSession(fingerprint(), MEMBER_ID, session.familyId()), next, NOW, POLICY)).isEqualTo(RefreshTokenRotation.INVALID);
        assertThat(adapter.rotate(new RefreshTokenSession(session.fingerprint(), MEMBER_ID + 1, session.familyId()), next, NOW, POLICY))
            .isEqualTo(RefreshTokenRotation.INVALID);
        assertThat(adapter.rotate(new RefreshTokenSession(session.fingerprint(), MEMBER_ID, familyId()), next, NOW, POLICY)).isEqualTo(RefreshTokenRotation.INVALID);
        assertThat(redisTemplate.hasKey(tokenKey(next))).isFalse();
    }

    @Test
    @DisplayName("Lua 실행 오류(토큰 키가 HASH 가 아니다 — WRONGTYPE)는 AUTH_013 이다")
    void scriptErrorBecomesUnavailable() {
        String fingerprint = fingerprint();
        String familyId = familyId();
        redisTemplate.opsForValue().set(tokenKey(fingerprint), "not-a-hash", Duration.ofMinutes(1));
        redisTemplate.opsForValue().set(familyKey(familyId), "1", Duration.ofMinutes(1));

        assertThatThrownBy(() -> adapter.rotate(new RefreshTokenSession(fingerprint, MEMBER_ID, familyId), fingerprint(), NOW, POLICY))
            .isInstanceOf(MemberException.class)
            .extracting(exception -> ((MemberException) exception).getErrorCode())
            .isEqualTo(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
    }

    @Test
    @DisplayName("로그아웃 차단 — auth:revoked-access-token:{jti} = \"1\", TTL 61분(상한 1시간 + clock-skew 60초)")
    void revokeAccessToken() {
        String jti = UUID.randomUUID().toString();

        new AccessTokenRevocationRedisAdapter(redisTemplate, TestTokens.verification()).revoke(jti);

        assertThat(redisTemplate.opsForValue().get("auth:revoked-access-token:" + jti)).isEqualTo("1");
        assertTtlAbout("auth:revoked-access-token:" + jti, Duration.ofMinutes(61));
    }

    @Test
    @DisplayName("비밀번호가 틀린 접속은 AUTH_013 이다 — Redis 오류가 도메인 503 으로 바뀐다")
    void wrongPasswordBecomesUnavailable() {
        LettuceConnectionFactory wrong = connectionFactory("wrong-password");
        try {
            RefreshTokenRedisAdapter failing = new RefreshTokenRedisAdapter(new StringRedisTemplate(wrong));

            assertThatThrownBy(() -> failing.revokeFamily(familyId()))
                .isInstanceOf(MemberException.class)
                .extracting(exception -> ((MemberException) exception).getErrorCode())
                .isEqualTo(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
        } finally {
            wrong.destroy();
        }
    }

    private static LettuceConnectionFactory connectionFactory(String password) {
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        standalone.setPassword(password);
        LettuceConnectionFactory factory = new LettuceConnectionFactory(standalone, LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(5)).build());
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    private RefreshTokenSession login() {
        String fingerprint = fingerprint();
        String familyId = familyId();
        adapter.saveNewFamily(fingerprint, MEMBER_ID, familyId, TTL);
        return adapter.findSession(fingerprint).orElseThrow();
    }

    private String fingerprint() {
        return tokens.fingerprint(tokens.generate());
    }

    private static String familyId() {
        return UUID.randomUUID().toString();
    }

    private static String tokenKey(String fingerprint) {
        return "auth:refresh-token:" + fingerprint;
    }

    private static String familyKey(String familyId) {
        return "auth:refresh-family:" + familyId;
    }

    /** TTL 이 있고(영구 키 아님) 기대값에서 1분 안이다. */
    private static void assertTtlAbout(String key, Duration expected) {
        Long ttlMillis = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
        assertThat(ttlMillis).as(key + " TTL").isNotNull().isPositive().isBetween(expected.minusMinutes(1).toMillis(), expected.toMillis());
    }
}
