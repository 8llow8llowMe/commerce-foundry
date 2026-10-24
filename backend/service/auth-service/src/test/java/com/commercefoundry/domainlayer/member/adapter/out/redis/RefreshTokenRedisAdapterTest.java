package com.commercefoundry.domainlayer.member.adapter.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.domainlayer.member.application.exception.MemberErrorCode;
import com.commercefoundry.domainlayer.member.application.exception.MemberException;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenRotation;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenRotationPolicy;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenRotationResult;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenSession;
import com.commercefoundry.security.issuer.OpaqueTokenGenerator;
import com.commercefoundry.security.support.TestTokens;
import com.commercefoundry.support.EnabledWithDockerOrOnCi;
import java.time.Clock;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
    private static final Duration MAX_LIFETIME = Duration.ofDays(30);
    private static final RefreshTokenRotationPolicy POLICY = new RefreshTokenRotationPolicy(GRACE, 3, TTL, MAX_LIFETIME);
    /** 로그인 시각이자 기본 회전 시각. 판정은 애플리케이션 시계(ARGV)라 실제 시각과 떨어져 있어도 된다. */
    private static final Instant NOW = Instant.parse("2026-10-18T03:00:00Z");
    private static final long MEMBER_ID = 4611686018427387904L;
    /** 숫자만 309자리 — 10^309 − 1 은 double 최대(약 1.8 × 10^308)를 넘어 Lua {@code tonumber} 가 무한대로 읽는다. */
    private static final String INFINITE_DIGITS = "9".repeat(309);

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
        adapter = new RefreshTokenRedisAdapter(redisTemplate, new TokenStoreRedisSupport(Clock.systemUTC()));
    }

    @Test
    @DisplayName("로그인 저장 — 토큰 HASH {memberId, familyId} 와 family 값 로그인 시각(epoch ms)이 둘 다 refresh TTL 로 생기고 rotatedAt 은 없다")
    void saveNewFamily() {
        String fingerprint = fingerprint();
        String familyId = familyId();

        adapter.saveNewFamily(fingerprint, MEMBER_ID, familyId, NOW, TTL);

        Map<Object, Object> hash = redisTemplate.opsForHash().entries(tokenKey(fingerprint));
        assertThat(hash).containsOnly(Map.entry("memberId", String.valueOf(MEMBER_ID)), Map.entry("familyId", familyId));
        assertThat(redisTemplate.opsForValue().get(familyKey(familyId))).isEqualTo(String.valueOf(NOW.toEpochMilli()));
        assertTtlAbout(tokenKey(fingerprint), TTL);
        assertTtlAbout(familyKey(familyId), TTL);
        assertThat(adapter.findSession(fingerprint)).contains(new RefreshTokenSession(fingerprint, MEMBER_ID, familyId));
    }

    @Test
    @DisplayName("정상 회전 — 옛 토큰에 rotatedAt=지금(ms) · TTL 은 그대로, 새 토큰은 같은 family 로 TTL 과 함께, family TTL 은 다시 refresh TTL 로 늘어나고 값(로그인 시각)은 그대로다")
    void rotate() {
        RefreshTokenSession session = login();
        redisTemplate.expire(familyKey(session.familyId()), Duration.ofDays(1));
        redisTemplate.expire(tokenKey(session.fingerprint()), Duration.ofDays(1));
        String next = fingerprint();
        Instant rotatedAt = NOW.plus(Duration.ofDays(2));

        assertThat(adapter.rotate(session, next, rotatedAt, POLICY)).isEqualTo(new RefreshTokenRotationResult(RefreshTokenRotation.ROTATED, TTL));

        assertThat(redisTemplate.opsForHash().get(tokenKey(session.fingerprint()), "rotatedAt")).isEqualTo(String.valueOf(rotatedAt.toEpochMilli()));
        assertTtlAbout(tokenKey(session.fingerprint()), Duration.ofDays(1));
        assertThat(redisTemplate.opsForHash().entries(tokenKey(next)))
            .containsOnly(Map.entry("memberId", String.valueOf(MEMBER_ID)), Map.entry("familyId", session.familyId()));
        assertTtlAbout(tokenKey(next), TTL);
        assertTtlAbout(familyKey(session.familyId()), TTL);
        assertThat(redisTemplate.opsForValue().get(familyKey(session.familyId()))).as("회전은 로그인 시각을 다시 쓰지 않는다")
            .isEqualTo(String.valueOf(NOW.toEpochMilli()));
        assertThat(adapter.findSession(next)).isPresent();
    }

    @Test
    @DisplayName("유예 안 재사용 — 형제 토큰을 저장하고 family TTL 을 늘리며, 옛 토큰의 rotatedAt 은 그대로다(유예가 미끄러지지 않는다)")
    void reuseInGrace() {
        RefreshTokenSession session = login();
        adapter.rotate(session, fingerprint(), NOW, POLICY);
        redisTemplate.expire(familyKey(session.familyId()), Duration.ofDays(1));
        String sibling = fingerprint();

        assertThat(rotation(session, sibling, NOW.plus(GRACE))).isEqualTo(RefreshTokenRotation.REISSUED_IN_GRACE);

        assertThat(redisTemplate.opsForHash().get(tokenKey(session.fingerprint()), "rotatedAt")).isEqualTo(String.valueOf(NOW.toEpochMilli()));
        assertThat(redisTemplate.opsForHash().get(tokenKey(session.fingerprint()), "siblings")).isEqualTo("1");
        assertThat(adapter.findSession(sibling)).isPresent();
        assertTtlAbout(tokenKey(sibling), TTL);
        assertTtlAbout(familyKey(session.familyId()), TTL);
        assertThat(rotation(session, fingerprint(), NOW.plus(GRACE).plusMillis(1)))
            .as("첫 회전부터 잰다 — 형제 발급이 창을 늘리지 않는다").isEqualTo(RefreshTokenRotation.REUSE_DETECTED);
    }

    @Test
    @DisplayName("지금이 rotatedAt 보다 이르면(인스턴스 간 시계 차이) 0 으로 보고 형제 발급이다")
    void negativeElapsedIsZero() {
        RefreshTokenSession session = login();
        adapter.rotate(session, fingerprint(), NOW, POLICY);

        assertThat(rotation(session, fingerprint(), NOW.minusSeconds(5))).isEqualTo(RefreshTokenRotation.REISSUED_IN_GRACE);
    }

    @Test
    @DisplayName("형제는 토큰당 상한(3)까지 — 넷째는 family 를 지우고 REUSE_DETECTED 다")
    void siblingCap() {
        RefreshTokenSession session = login();
        adapter.rotate(session, fingerprint(), NOW, POLICY);
        for (int i = 0; i < 3; i++) {
            assertThat(rotation(session, fingerprint(), NOW)).isEqualTo(RefreshTokenRotation.REISSUED_IN_GRACE);
        }

        assertThat(adapter.rotate(session, fingerprint(), NOW, POLICY)).isEqualTo(RefreshTokenRotationResult.rejected(RefreshTokenRotation.REUSE_DETECTED));
        assertThat(redisTemplate.hasKey(familyKey(session.familyId()))).isFalse();
    }

    @Test
    @DisplayName("유예 밖 재사용 — family 를 지우고, 그 family 의 형제 · 새 토큰까지 무효가 된다")
    void reuseOutsideGraceRevokesFamily() {
        RefreshTokenSession session = login();
        String next = fingerprint();
        adapter.rotate(session, next, NOW, POLICY);
        RefreshTokenSession nextSession = adapter.findSession(next).orElseThrow();

        assertThat(rotation(session, fingerprint(), NOW.plus(GRACE).plusMillis(1))).isEqualTo(RefreshTokenRotation.REUSE_DETECTED);

        assertThat(redisTemplate.hasKey(familyKey(session.familyId()))).isFalse();
        assertThat(adapter.findSession(next)).isEmpty();
        assertThat(rotation(nextSession, fingerprint(), NOW.plusSeconds(60))).isEqualTo(RefreshTokenRotation.INVALID);
    }

    @Test
    @DisplayName("rotatedAt 이 숫자가 아니면(손상) 재사용으로 보고 family 를 지운다")
    void corruptRotatedAtIsReuse() {
        RefreshTokenSession session = login();
        redisTemplate.opsForHash().put(tokenKey(session.fingerprint()), "rotatedAt", "not-a-number");

        assertThat(rotation(session, fingerprint(), NOW)).isEqualTo(RefreshTokenRotation.REUSE_DETECTED);
        assertThat(redisTemplate.hasKey(familyKey(session.familyId()))).isFalse();
    }

    @Test
    @DisplayName("절대 수명을 넘기면(로그인 + 상한과 같은 시각부터) 회전하지 않고 family 를 지운 뒤 FAMILY_EXPIRED 다 — 새 토큰도 rotatedAt 도 쓰지 않는다")
    void familyExpired() {
        RefreshTokenSession session = login();
        String next = fingerprint();

        assertThat(adapter.rotate(session, next, NOW.plus(MAX_LIFETIME), POLICY)).isEqualTo(RefreshTokenRotationResult.rejected(RefreshTokenRotation.FAMILY_EXPIRED));

        assertThat(redisTemplate.hasKey(familyKey(session.familyId()))).isFalse();
        assertThat(redisTemplate.hasKey(tokenKey(next))).isFalse();
        assertThat(redisTemplate.opsForHash().hasKey(tokenKey(session.fingerprint()), "rotatedAt")).isFalse();
        assertThat(adapter.findSession(session.fingerprint())).isEmpty();
    }

    @Test
    @DisplayName("이미 회전한 토큰이 유예 안에 와도 절대 수명을 넘겼으면 FAMILY_EXPIRED 다 — 만료 판정이 재사용 판정보다 먼저다")
    void familyExpiredBeforeReuseCheck() {
        RefreshTokenSession session = login();
        // family TTL 이 남은 시간(실제 Redis 시계로 10초)으로 줄어든다 — 그 안에 다음 회전을 부른다
        Instant rotatedAt = NOW.plus(MAX_LIFETIME).minusSeconds(10);
        adapter.rotate(session, fingerprint(), rotatedAt, POLICY);

        assertThat(rotation(session, fingerprint(), rotatedAt.plusSeconds(10))).isEqualTo(RefreshTokenRotation.FAMILY_EXPIRED);
        assertThat(redisTemplate.opsForHash().hasKey(tokenKey(session.fingerprint()), "siblings")).isFalse();
    }

    @Test
    @DisplayName("상한 근처에서 회전하면 새 토큰 · family TTL 이 남은 시간으로 줄고, 결과 TTL 도 그 값이다")
    void rotateNearMaxLifetimeShrinksTtl() {
        RefreshTokenSession session = login();
        String next = fingerprint();
        Duration remaining = Duration.ofDays(2);

        RefreshTokenRotationResult result = adapter.rotate(session, next, NOW.plus(MAX_LIFETIME).minus(remaining), POLICY);

        assertThat(result).isEqualTo(new RefreshTokenRotationResult(RefreshTokenRotation.ROTATED, remaining));
        assertTtlAbout(tokenKey(next), remaining);
        assertTtlAbout(familyKey(session.familyId()), remaining);
        assertThat(redisTemplate.opsForValue().get(familyKey(session.familyId()))).isEqualTo(String.valueOf(NOW.toEpochMilli()));
    }

    @Test
    @DisplayName("상한까지 999ms 남으면(1초 미만) 회전하지 않고 family 를 지운 뒤 FAMILY_EXPIRED 다")
    void lessThanOneSecondLeftExpires() {
        RefreshTokenSession session = login();
        String next = fingerprint();

        assertThat(adapter.rotate(session, next, NOW.plus(MAX_LIFETIME).minusMillis(999), POLICY))
            .isEqualTo(RefreshTokenRotationResult.rejected(RefreshTokenRotation.FAMILY_EXPIRED));
        assertThat(redisTemplate.hasKey(familyKey(session.familyId()))).isFalse();
        assertThat(redisTemplate.hasKey(tokenKey(next))).isFalse();
    }

    @Test
    @DisplayName("상한까지 1000ms 남으면 회전하고 TTL 이 1초다")
    void oneSecondLeftRotatesWithOneSecondTtl() {
        RefreshTokenSession session = login();
        String next = fingerprint();

        assertThat(adapter.rotate(session, next, NOW.plus(MAX_LIFETIME).minusMillis(1000), POLICY))
            .isEqualTo(new RefreshTokenRotationResult(RefreshTokenRotation.ROTATED, Duration.ofSeconds(1)));
        assertThat(redisTemplate.getExpire(tokenKey(next), TimeUnit.MILLISECONDS)).isBetween(1L, 1000L);
    }

    @Test
    @DisplayName("family 값 · rotatedAt 이 \"nan\" 이면(tonumber 는 받는다) 정수 문자열이 아니라 각각 FAMILY_CORRUPTED · REUSE_DETECTED 이고 family 를 지운다")
    void nanStoredValues() {
        RefreshTokenSession corruptFamily = login();
        redisTemplate.opsForValue().set(familyKey(corruptFamily.familyId()), "nan", Duration.ofMinutes(1));
        assertThat(adapter.rotate(corruptFamily, fingerprint(), NOW, POLICY)).isEqualTo(RefreshTokenRotationResult.rejected(RefreshTokenRotation.FAMILY_CORRUPTED));
        assertThat(redisTemplate.hasKey(familyKey(corruptFamily.familyId()))).isFalse();

        RefreshTokenSession corruptRotatedAt = login();
        redisTemplate.opsForHash().put(tokenKey(corruptRotatedAt.fingerprint()), "rotatedAt", "nan");
        assertThat(adapter.rotate(corruptRotatedAt, fingerprint(), NOW, POLICY))
            .isEqualTo(RefreshTokenRotationResult.rejected(RefreshTokenRotation.REUSE_DETECTED));
        assertThat(redisTemplate.hasKey(familyKey(corruptRotatedAt.familyId()))).isFalse();
    }

    @Test
    @DisplayName("유예 안 형제 발급도 상한 근처면 TTL 을 남은 시간으로 줄인다")
    void siblingNearMaxLifetimeShrinksTtl() {
        RefreshTokenSession session = login();
        Instant rotatedAt = NOW.plus(MAX_LIFETIME).minus(Duration.ofHours(3));
        adapter.rotate(session, fingerprint(), rotatedAt, POLICY);
        String sibling = fingerprint();

        RefreshTokenRotationResult result = adapter.rotate(session, sibling, rotatedAt.plusSeconds(20), POLICY);

        Duration remaining = Duration.ofHours(3).minusSeconds(20);
        assertThat(result).isEqualTo(new RefreshTokenRotationResult(RefreshTokenRotation.REISSUED_IN_GRACE, remaining));
        assertTtlAbout(tokenKey(sibling), remaining);
        assertTtlAbout(familyKey(session.familyId()), remaining);
        assertThat(redisTemplate.opsForValue().get(familyKey(session.familyId()))).isEqualTo(String.valueOf(NOW.toEpochMilli()));
    }

    @Test
    @DisplayName("지금이 로그인 시각보다 이르면(시계 차이) 경과 0 으로 보고 TTL 그대로 회전한다")
    void negativeAgeIsZero() {
        RefreshTokenSession session = login();

        assertThat(adapter.rotate(session, fingerprint(), NOW.minusSeconds(5), POLICY)).isEqualTo(new RefreshTokenRotationResult(RefreshTokenRotation.ROTATED, TTL));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-number", "inf", "1e3", "0x10", "-1"})
    @DisplayName("family 값이 정수 문자열이 아니면(손상 — tonumber 가 숫자로 읽는 inf · 1e3 · 0x10 포함) family 를 지우고 FAMILY_CORRUPTED 다. 새 토큰도 rotatedAt 도 쓰지 않는다")
    void nonIntegerFamilyValueIsCorrupted(String value) {
        RefreshTokenSession session = login();
        corruptFamily(session, value);
        String next = fingerprint();

        assertThat(adapter.rotate(session, next, NOW, POLICY)).isEqualTo(RefreshTokenRotationResult.rejected(RefreshTokenRotation.FAMILY_CORRUPTED));
        assertThat(redisTemplate.hasKey(familyKey(session.familyId()))).isFalse();
        assertThat(redisTemplate.hasKey(tokenKey(next))).isFalse();
        assertThat(redisTemplate.opsForHash().hasKey(tokenKey(session.fingerprint()), "rotatedAt")).isFalse();
    }

    @Test
    @DisplayName("숫자만 309자리인 family 값은 ^%d+$ 를 통과하지만 tonumber 가 무한대로 읽는다 — 유한성 검사로 FAMILY_CORRUPTED 이고 family 를 지운다")
    void infiniteDigitFamilyValueIsCorrupted() {
        RefreshTokenSession session = login();
        corruptFamily(session, INFINITE_DIGITS);

        assertThat(adapter.rotate(session, fingerprint(), NOW, POLICY)).isEqualTo(RefreshTokenRotationResult.rejected(RefreshTokenRotation.FAMILY_CORRUPTED));
        assertThat(redisTemplate.hasKey(familyKey(session.familyId()))).isFalse();
    }

    @Test
    @DisplayName("로그인 시각이 지금 + 허용 시계 오차(60초)보다 1ms 미래면 FAMILY_CORRUPTED 이고 family 를 지운다. 새 토큰을 쓰지 않는다")
    void futureLoginAtBeyondClockSkewIsCorrupted() {
        RefreshTokenSession session = login();
        corruptFamily(session, String.valueOf(NOW.plus(RefreshTokenRotationPolicy.CLOCK_SKEW).plusMillis(1).toEpochMilli()));
        String next = fingerprint();

        assertThat(adapter.rotate(session, next, NOW, POLICY)).isEqualTo(RefreshTokenRotationResult.rejected(RefreshTokenRotation.FAMILY_CORRUPTED));
        assertThat(redisTemplate.hasKey(familyKey(session.familyId()))).isFalse();
        assertThat(redisTemplate.hasKey(tokenKey(next))).isFalse();
    }

    @Test
    @DisplayName("로그인 시각이 지금 + 허용 시계 오차(60초)와 같으면 정상이다 — 경과 0 으로 보고 TTL 그대로 회전하고 로그인 시각은 그대로다")
    void futureLoginAtWithinClockSkewRotates() {
        RefreshTokenSession session = login();
        String loginAt = String.valueOf(NOW.plus(RefreshTokenRotationPolicy.CLOCK_SKEW).toEpochMilli());
        redisTemplate.opsForValue().set(familyKey(session.familyId()), loginAt, TTL);

        assertThat(adapter.rotate(session, fingerprint(), NOW, POLICY)).isEqualTo(new RefreshTokenRotationResult(RefreshTokenRotation.ROTATED, TTL));
        assertThat(redisTemplate.opsForValue().get(familyKey(session.familyId()))).isEqualTo(loginAt);
    }

    @Test
    @DisplayName("#4 의 옛 family 값 \"1\" 은 1970년 로그인으로 읽혀 FAMILY_EXPIRED 이고 family 를 지운다")
    void legacyFamilyValueExpires() {
        RefreshTokenSession session = login();
        corruptFamily(session, "1");
        String next = fingerprint();

        assertThat(adapter.rotate(session, next, NOW, POLICY)).isEqualTo(RefreshTokenRotationResult.rejected(RefreshTokenRotation.FAMILY_EXPIRED));
        assertThat(redisTemplate.hasKey(familyKey(session.familyId()))).isFalse();
        assertThat(redisTemplate.hasKey(tokenKey(next))).isFalse();
    }

    @Test
    @DisplayName("family 값이 손상됐으면 이미 회전한 토큰이 유예 밖에 와도 재사용(REUSE_DETECTED)보다 FAMILY_CORRUPTED 가 먼저다 — siblings 도 올리지 않는다")
    void corruptedFamilyBeforeReuseCheck() {
        RefreshTokenSession session = login();
        adapter.rotate(session, fingerprint(), NOW, POLICY);
        corruptFamily(session, "nan");

        assertThat(adapter.rotate(session, fingerprint(), NOW.plus(GRACE).plusSeconds(1), POLICY))
            .isEqualTo(RefreshTokenRotationResult.rejected(RefreshTokenRotation.FAMILY_CORRUPTED));
        assertThat(redisTemplate.hasKey(familyKey(session.familyId()))).isFalse();
        assertThat(redisTemplate.opsForHash().hasKey(tokenKey(session.fingerprint()), "siblings")).isFalse();
        assertThat(redisTemplate.opsForHash().get(tokenKey(session.fingerprint()), "rotatedAt")).isEqualTo(String.valueOf(NOW.toEpochMilli()));
    }

    @Test
    @DisplayName("rotatedAt 이 309자리 숫자열(무한대)이거나 지금 + 허용 시계 오차보다 1ms 미래면 재사용으로 보고 family 를 지운다")
    void infiniteOrFutureRotatedAtIsReuse() {
        RefreshTokenSession infinite = login();
        redisTemplate.opsForHash().put(tokenKey(infinite.fingerprint()), "rotatedAt", INFINITE_DIGITS);
        assertThat(adapter.rotate(infinite, fingerprint(), NOW, POLICY)).isEqualTo(RefreshTokenRotationResult.rejected(RefreshTokenRotation.REUSE_DETECTED));
        assertThat(redisTemplate.hasKey(familyKey(infinite.familyId()))).isFalse();

        RefreshTokenSession future = login();
        redisTemplate.opsForHash().put(tokenKey(future.fingerprint()), "rotatedAt", String.valueOf(NOW.plus(RefreshTokenRotationPolicy.CLOCK_SKEW).plusMillis(1).toEpochMilli()));
        assertThat(adapter.rotate(future, fingerprint(), NOW, POLICY)).isEqualTo(RefreshTokenRotationResult.rejected(RefreshTokenRotation.REUSE_DETECTED));
        assertThat(redisTemplate.hasKey(familyKey(future.familyId()))).isFalse();
    }

    @Test
    @DisplayName("rotatedAt 이 지금 + 허용 시계 오차(60초)와 같으면 경과 0 으로 보고 유예 안 형제 발급이다")
    void futureRotatedAtWithinClockSkewIsGrace() {
        RefreshTokenSession session = login();
        redisTemplate.opsForHash().put(tokenKey(session.fingerprint()), "rotatedAt", String.valueOf(NOW.plus(RefreshTokenRotationPolicy.CLOCK_SKEW).toEpochMilli()));

        assertThat(rotation(session, fingerprint(), NOW)).isEqualTo(RefreshTokenRotation.REISSUED_IN_GRACE);
    }

    @Test
    @DisplayName("family 를 지우면(로그아웃) 그 family 토큰은 읽기 · 회전 모두 무효이고 아무것도 쓰지 않는다")
    void revokedFamilyIsInvalid() {
        RefreshTokenSession session = login();

        adapter.revokeFamily(session.familyId());

        assertThat(adapter.findSession(session.fingerprint())).isEmpty();
        String next = fingerprint();
        assertThat(rotation(session, next, NOW)).isEqualTo(RefreshTokenRotation.INVALID);
        assertThat(redisTemplate.hasKey(tokenKey(next))).isFalse();
        assertThat(redisTemplate.opsForHash().hasKey(tokenKey(session.fingerprint()), "rotatedAt")).isFalse();
    }

    @Test
    @DisplayName("없는 토큰 · 읽은 값과 다른 memberId · familyId 는 INVALID 이고 아무것도 쓰지 않는다")
    void mismatchIsInvalid() {
        RefreshTokenSession session = login();
        String next = fingerprint();

        assertThat(rotation(new RefreshTokenSession(fingerprint(), MEMBER_ID, session.familyId()), next, NOW)).isEqualTo(RefreshTokenRotation.INVALID);
        assertThat(rotation(new RefreshTokenSession(session.fingerprint(), MEMBER_ID + 1, session.familyId()), next, NOW)).isEqualTo(RefreshTokenRotation.INVALID);
        assertThat(rotation(new RefreshTokenSession(session.fingerprint(), MEMBER_ID, familyId()), next, NOW)).isEqualTo(RefreshTokenRotation.INVALID);
        assertThat(redisTemplate.hasKey(tokenKey(next))).isFalse();
    }

    @Test
    @DisplayName("Lua 실행 오류(토큰 키가 HASH 가 아니다 — WRONGTYPE)는 AUTH_013 이다")
    void scriptErrorBecomesUnavailable() {
        String fingerprint = fingerprint();
        String familyId = familyId();
        redisTemplate.opsForValue().set(tokenKey(fingerprint), "not-a-hash", Duration.ofMinutes(1));
        redisTemplate.opsForValue().set(familyKey(familyId), String.valueOf(NOW.toEpochMilli()), Duration.ofMinutes(1));

        assertThatThrownBy(() -> adapter.rotate(new RefreshTokenSession(fingerprint, MEMBER_ID, familyId), fingerprint(), NOW, POLICY))
            .isInstanceOf(MemberException.class)
            .extracting(exception -> ((MemberException) exception).getErrorCode())
            .isEqualTo(MemberErrorCode.TOKEN_STORE_UNAVAILABLE);
    }

    @Test
    @DisplayName("로그아웃 차단 — auth:revoked-access-token:{jti} = \"1\", TTL 61분(상한 1시간 + clock-skew 60초)")
    void revokeAccessToken() {
        String jti = UUID.randomUUID().toString();

        new AccessTokenRevocationRedisAdapter(redisTemplate, TestTokens.verification(), new TokenStoreRedisSupport(Clock.systemUTC())).revoke(jti);

        assertThat(redisTemplate.opsForValue().get("auth:revoked-access-token:" + jti)).isEqualTo("1");
        assertTtlAbout("auth:revoked-access-token:" + jti, Duration.ofMinutes(61));
    }

    @Test
    @DisplayName("비밀번호가 틀린 접속은 AUTH_013 이다 — Redis 오류가 도메인 503 으로 바뀐다")
    void wrongPasswordBecomesUnavailable() {
        LettuceConnectionFactory wrong = connectionFactory("wrong-password");
        try {
            RefreshTokenRedisAdapter failing = new RefreshTokenRedisAdapter(new StringRedisTemplate(wrong), new TokenStoreRedisSupport(Clock.systemUTC()));

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

    /** NOW 에 로그인한 토큰의 세션. */
    private RefreshTokenSession login() {
        String fingerprint = fingerprint();
        String familyId = familyId();
        adapter.saveNewFamily(fingerprint, MEMBER_ID, familyId, NOW, TTL);
        return adapter.findSession(fingerprint).orElseThrow();
    }

    /** family 값을 바꾼다(손상 · 옛 값 흉내). TTL 은 1분 — 테스트 중에는 사라지지 않는다. */
    private static void corruptFamily(RefreshTokenSession session, String value) {
        redisTemplate.opsForValue().set(familyKey(session.familyId()), value, Duration.ofMinutes(1));
    }

    private RefreshTokenRotation rotation(RefreshTokenSession session, String newFingerprint, Instant now) {
        return adapter.rotate(session, newFingerprint, now, POLICY).rotation();
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
