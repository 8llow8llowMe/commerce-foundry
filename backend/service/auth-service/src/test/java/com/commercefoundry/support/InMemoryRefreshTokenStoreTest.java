package com.commercefoundry.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenRotation;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenRotationPolicy;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenRotationResult;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenSession;
import java.time.Duration;
import java.time.Instant;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 흐름 테스트용 스텁이 Lua({@code RefreshTokenRedisScripts#ROTATE})와 같은 경계로 판정하는지 고정한다 —
 * 같은 경우를 {@code RefreshTokenRedisAdapterTest}(Testcontainers)가 실제 Lua 로 본다.
 */
class InMemoryRefreshTokenStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-18T03:00:00Z");
    private static final Duration TTL = Duration.ofDays(14);
    private static final Duration GRACE = Duration.ofSeconds(30);
    private static final Duration MAX_LIFETIME = Duration.ofDays(30);
    private static final RefreshTokenRotationPolicy POLICY = new RefreshTokenRotationPolicy(GRACE, 3, TTL, MAX_LIFETIME);

    private final InMemoryRefreshTokenStore store = new InMemoryRefreshTokenStore();

    @Test
    @DisplayName("지금 − rotatedAt 이 유예와 같으면 형제 발급, 1ms 넘으면 재사용 감지다 (≤ 유예)")
    void graceBoundary() {
        RefreshTokenSession onBoundary = rotated("a");
        assertThat(store.rotate(onBoundary, "a-sibling", NOW.plus(GRACE), POLICY).rotation()).isEqualTo(RefreshTokenRotation.REISSUED_IN_GRACE);

        RefreshTokenSession pastBoundary = rotated("b");
        assertThat(store.rotate(pastBoundary, "b-sibling", NOW.plus(GRACE).plusMillis(1), POLICY).rotation()).isEqualTo(RefreshTokenRotation.REUSE_DETECTED);
        assertThat(store.familyAlive(pastBoundary.familyId())).isFalse();
    }

    @Test
    @DisplayName("지금이 rotatedAt 보다 이르면(인스턴스 간 시계 차이) 0 으로 보고 형제 발급이다")
    void negativeElapsedIsZero() {
        assertThat(store.rotate(rotated("c"), "c-sibling", NOW.minusSeconds(5), POLICY).rotation()).isEqualTo(RefreshTokenRotation.REISSUED_IN_GRACE);
    }

    @Test
    @DisplayName("형제는 토큰당 상한(3)까지, 넘으면 재사용 감지로 family 를 지운다")
    void siblingCap() {
        RefreshTokenSession session = rotated("d");
        for (int i = 1; i <= 3; i++) {
            assertThat(store.rotate(session, "d-sibling-" + i, NOW, POLICY).rotation()).isEqualTo(RefreshTokenRotation.REISSUED_IN_GRACE);
        }
        assertThat(store.rotate(session, "d-sibling-4", NOW, POLICY).rotation()).isEqualTo(RefreshTokenRotation.REUSE_DETECTED);
        assertThat(store.familyAlive(session.familyId())).isFalse();
    }

    @Test
    @DisplayName("절대 수명 경계 — 로그인 + 상한과 같은 시각은 FAMILY_EXPIRED(≥)이고 family 를 지운다")
    void maxLifetimeBoundaryExpires() {
        RefreshTokenSession session = login("e");

        RefreshTokenRotationResult result = store.rotate(session, "e-next", NOW.plus(MAX_LIFETIME), POLICY);

        assertThat(result).isEqualTo(RefreshTokenRotationResult.rejected(RefreshTokenRotation.FAMILY_EXPIRED));
        assertThat(store.familyAlive(session.familyId())).isFalse();
        assertThat(store.findSession("e-next")).isEmpty();
    }

    @Test
    @DisplayName("상한까지 999ms 남으면(1초 미만) 회전하지 않고 FAMILY_EXPIRED 다 — 응답 수명 0초 토큰을 내주지 않는다")
    void lessThanOneSecondLeftExpires() {
        RefreshTokenSession session = login("f");

        assertThat(store.rotate(session, "f-next", NOW.plus(MAX_LIFETIME).minusMillis(999), POLICY))
            .isEqualTo(RefreshTokenRotationResult.rejected(RefreshTokenRotation.FAMILY_EXPIRED));
        assertThat(store.familyAlive(session.familyId())).isFalse();
    }

    @Test
    @DisplayName("상한까지 1000ms 남으면 회전하고, 새 토큰 · family TTL 이 1초로 줄어든다. 로그인 시각은 그대로다")
    void oneSecondLeftRotatesWithOneSecondTtl() {
        RefreshTokenSession session = login("f2");

        RefreshTokenRotationResult result = store.rotate(session, "f2-next", NOW.plus(MAX_LIFETIME).minusMillis(1000), POLICY);

        assertThat(result).isEqualTo(new RefreshTokenRotationResult(RefreshTokenRotation.ROTATED, Duration.ofSeconds(1)));
        assertThat(store.familyTtl(session.familyId())).isEqualTo(Duration.ofSeconds(1));
        assertThat(store.familyLoginAt(session.familyId())).isEqualTo(NOW);
    }

    @ParameterizedTest
    @ValueSource(strings = {"nan", "inf", "0x10", "1e3", "-1", ""})
    @DisplayName("family 값이 정수 문자열이 아니면(nan 등) family 를 지우고 FAMILY_CORRUPTED 다")
    void nonIntegerFamilyValueIsCorrupted(String value) {
        RefreshTokenSession session = login("j" + value);
        store.corruptFamilyValue(session.familyId(), value);

        assertThat(store.rotate(session, "j-next" + value, NOW, POLICY)).isEqualTo(RefreshTokenRotationResult.rejected(RefreshTokenRotation.FAMILY_CORRUPTED));
        assertThat(store.familyAlive(session.familyId())).isFalse();
        assertThat(store.findSession("j-next" + value)).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("hugeDigitValues")
    @DisplayName("숫자만으로 된 family 값이라도 무한대로 읽히거나(309자리) long 을 넘으면 예외 없이 FAMILY_CORRUPTED 다 — Lua tonumber 와 같은 결과")
    void hugeDigitFamilyValueIsCorrupted(String value) {
        RefreshTokenSession session = login("big" + value.length());
        store.corruptFamilyValue(session.familyId(), value);

        assertThat(store.rotate(session, "big-next" + value.length(), NOW, POLICY))
            .isEqualTo(RefreshTokenRotationResult.rejected(RefreshTokenRotation.FAMILY_CORRUPTED));
        assertThat(store.familyAlive(session.familyId())).isFalse();
    }

    @Test
    @DisplayName("로그인 시각이 지금 + 허용 시계 오차(60초)와 같으면 받아 경과 0 으로 TTL 그대로 회전하고, 1ms 넘으면 FAMILY_CORRUPTED 다")
    void futureLoginAtBoundary() {
        RefreshTokenSession onBoundary = login("fut1");
        assertThat(store.rotate(onBoundary, "fut1-next", NOW.minus(RefreshTokenRotationPolicy.CLOCK_SKEW), POLICY))
            .isEqualTo(new RefreshTokenRotationResult(RefreshTokenRotation.ROTATED, TTL));

        RefreshTokenSession pastBoundary = login("fut2");
        assertThat(store.rotate(pastBoundary, "fut2-next", NOW.minus(RefreshTokenRotationPolicy.CLOCK_SKEW).minusMillis(1), POLICY))
            .isEqualTo(RefreshTokenRotationResult.rejected(RefreshTokenRotation.FAMILY_CORRUPTED));
        assertThat(store.familyAlive(pastBoundary.familyId())).isFalse();
        assertThat(store.findSession("fut2-next")).isEmpty();
    }

    @Test
    @DisplayName("#4 의 옛 family 값 \"1\" 은 1970년 로그인으로 읽혀 FAMILY_EXPIRED 이고 family 를 지운다")
    void legacyFamilyValueExpires() {
        RefreshTokenSession session = login("legacy");
        store.corruptFamilyValue(session.familyId(), "1");

        assertThat(store.rotate(session, "legacy-next", NOW, POLICY)).isEqualTo(RefreshTokenRotationResult.rejected(RefreshTokenRotation.FAMILY_EXPIRED));
        assertThat(store.familyAlive(session.familyId())).isFalse();
    }

    @Test
    @DisplayName("family 값이 손상됐으면 이미 회전한 토큰이 유예 밖에 와도 재사용(REUSE_DETECTED)보다 FAMILY_CORRUPTED 가 먼저다")
    void corruptedFamilyBeforeReuseCheck() {
        RefreshTokenSession session = rotated("cr");
        store.corruptFamilyValue(session.familyId(), "nan");

        assertThat(store.rotate(session, "cr-again", NOW.plus(GRACE).plusSeconds(1), POLICY))
            .isEqualTo(RefreshTokenRotationResult.rejected(RefreshTokenRotation.FAMILY_CORRUPTED));
        assertThat(store.familyAlive(session.familyId())).isFalse();
    }

    @ParameterizedTest
    @MethodSource("corruptRotatedAtValues")
    @DisplayName("rotatedAt 이 정수 문자열이 아니거나 · 무한대 · long 을 넘는 숫자열이면 재사용으로 보고 family 를 지운다")
    void corruptRotatedAtIsReuse(String value) {
        RefreshTokenSession session = rotated("k" + value);
        store.corruptRotatedAt(session.fingerprint(), value);

        assertThat(store.rotate(session, "k-sibling" + value, NOW, POLICY))
            .isEqualTo(RefreshTokenRotationResult.rejected(RefreshTokenRotation.REUSE_DETECTED));
        assertThat(store.familyAlive(session.familyId())).isFalse();
    }

    @Test
    @DisplayName("rotatedAt 이 지금 + 허용 시계 오차(60초)와 같으면 유예 안 형제 발급이고, 1ms 넘으면 재사용으로 보고 family 를 지운다")
    void futureRotatedAtBoundary() {
        RefreshTokenSession onBoundary = rotated("rf1");
        store.corruptRotatedAt(onBoundary.fingerprint(), String.valueOf(NOW.plus(RefreshTokenRotationPolicy.CLOCK_SKEW).toEpochMilli()));
        assertThat(store.rotate(onBoundary, "rf1-sibling", NOW, POLICY).rotation()).isEqualTo(RefreshTokenRotation.REISSUED_IN_GRACE);

        RefreshTokenSession pastBoundary = rotated("rf2");
        store.corruptRotatedAt(pastBoundary.fingerprint(), String.valueOf(NOW.plus(RefreshTokenRotationPolicy.CLOCK_SKEW).plusMillis(1).toEpochMilli()));
        assertThat(store.rotate(pastBoundary, "rf2-sibling", NOW, POLICY)).isEqualTo(RefreshTokenRotationResult.rejected(RefreshTokenRotation.REUSE_DETECTED));
        assertThat(store.familyAlive(pastBoundary.familyId())).isFalse();
    }

    @Test
    @DisplayName("상한까지 TTL 보다 많이 남았으면 TTL 그대로, 지금이 로그인 시각보다 이르면 경과 0 으로 보고 TTL 그대로다")
    void fullTtlWhileFarFromMaxLifetime() {
        assertThat(store.rotate(login("g"), "g-next", NOW.plus(Duration.ofDays(16)), POLICY))
            .isEqualTo(new RefreshTokenRotationResult(RefreshTokenRotation.ROTATED, TTL));
        assertThat(store.rotate(login("h"), "h-next", NOW.minusSeconds(5), POLICY))
            .isEqualTo(new RefreshTokenRotationResult(RefreshTokenRotation.ROTATED, TTL));
    }

    @Test
    @DisplayName("상한 근처의 유예 안 형제 발급도 TTL 을 남은 시간으로 줄인다")
    void siblingNearMaxLifetimeShrinksTtl() {
        RefreshTokenSession session = login("i");
        Instant rotatedAt = NOW.plus(MAX_LIFETIME).minusSeconds(10);
        assertThat(store.rotate(session, "i-next", rotatedAt, POLICY)).isEqualTo(new RefreshTokenRotationResult(RefreshTokenRotation.ROTATED, Duration.ofSeconds(10)));

        assertThat(store.rotate(session, "i-sibling", rotatedAt.plusSeconds(4), POLICY))
            .isEqualTo(new RefreshTokenRotationResult(RefreshTokenRotation.REISSUED_IN_GRACE, Duration.ofSeconds(6)));
        assertThat(store.familyTtl(session.familyId())).isEqualTo(Duration.ofSeconds(6));
    }

    /** NOW 에 로그인한 토큰의 세션. */
    private RefreshTokenSession login(String name) {
        store.saveNewFamily(name, 1L, "family-" + name, NOW, TTL);
        return store.findSession(name).orElseThrow();
    }

    /** 로그인 후 한 번 회전한(rotatedAt=NOW) 토큰의 세션. */
    private RefreshTokenSession rotated(String name) {
        RefreshTokenSession session = login(name);
        assertThat(store.rotate(session, name + "-next", NOW, POLICY).rotation()).isEqualTo(RefreshTokenRotation.ROTATED);
        return session;
    }

    /**
     * 숫자만으로 된 큰 값 — 309자리 9(10^309 − 1)는 double 최대(약 1.8 × 10^308)를 넘어 Lua {@code tonumber} 가 무한대로 읽는다.
     * 20자리는 long 최대(19자리)를 넘지만 double 로는 유한하고 지금 + 허용 오차보다 미래다.
     */
    static Stream<String> hugeDigitValues() {
        return Stream.of("9".repeat(309), "1" + "0".repeat(19));
    }

    static Stream<String> corruptRotatedAtValues() {
        return Stream.concat(Stream.of("nan", "inf", "0x10", "1e3"), hugeDigitValues());
    }
}
