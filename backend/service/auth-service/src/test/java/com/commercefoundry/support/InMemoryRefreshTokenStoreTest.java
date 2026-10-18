package com.commercefoundry.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenRotation;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenRotationPolicy;
import com.commercefoundry.domainlayer.member.domain.model.RefreshTokenSession;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 흐름 테스트용 스텁이 Lua({@code RefreshTokenRedisScripts#ROTATE})와 같은 경계로 판정하는지 고정한다 —
 * 같은 경우를 {@code RefreshTokenRedisAdapterTest}(Testcontainers)가 실제 Lua 로 본다.
 */
class InMemoryRefreshTokenStoreTest {

    private static final Instant NOW = Instant.parse("2026-10-18T03:00:00Z");
    private static final Duration GRACE = Duration.ofSeconds(30);
    private static final RefreshTokenRotationPolicy POLICY = new RefreshTokenRotationPolicy(GRACE, 3, Duration.ofDays(14));

    private final InMemoryRefreshTokenStore store = new InMemoryRefreshTokenStore();

    @Test
    @DisplayName("지금 − rotatedAt 이 유예와 같으면 형제 발급, 1ms 넘으면 재사용 감지다 (≤ 유예)")
    void graceBoundary() {
        RefreshTokenSession onBoundary = rotated("a");
        assertThat(store.rotate(onBoundary, "a-sibling", NOW.plus(GRACE), POLICY)).isEqualTo(RefreshTokenRotation.REISSUED_IN_GRACE);

        RefreshTokenSession pastBoundary = rotated("b");
        assertThat(store.rotate(pastBoundary, "b-sibling", NOW.plus(GRACE).plusMillis(1), POLICY)).isEqualTo(RefreshTokenRotation.REUSE_DETECTED);
        assertThat(store.familyAlive(pastBoundary.familyId())).isFalse();
    }

    @Test
    @DisplayName("지금이 rotatedAt 보다 이르면(인스턴스 간 시계 차이) 0 으로 보고 형제 발급이다")
    void negativeElapsedIsZero() {
        assertThat(store.rotate(rotated("c"), "c-sibling", NOW.minusSeconds(5), POLICY)).isEqualTo(RefreshTokenRotation.REISSUED_IN_GRACE);
    }

    @Test
    @DisplayName("형제는 토큰당 상한(3)까지, 넘으면 재사용 감지로 family 를 지운다")
    void siblingCap() {
        RefreshTokenSession session = rotated("d");
        for (int i = 1; i <= 3; i++) {
            assertThat(store.rotate(session, "d-sibling-" + i, NOW, POLICY)).isEqualTo(RefreshTokenRotation.REISSUED_IN_GRACE);
        }
        assertThat(store.rotate(session, "d-sibling-4", NOW, POLICY)).isEqualTo(RefreshTokenRotation.REUSE_DETECTED);
        assertThat(store.familyAlive(session.familyId())).isFalse();
    }

    /** 로그인 후 한 번 회전한(rotatedAt=NOW) 토큰의 세션. */
    private RefreshTokenSession rotated(String name) {
        store.saveNewFamily(name, 1L, "family-" + name, Duration.ofDays(14));
        RefreshTokenSession session = store.findSession(name).orElseThrow();
        assertThat(store.rotate(session, name + "-next", NOW, POLICY)).isEqualTo(RefreshTokenRotation.ROTATED);
        return session;
    }
}
