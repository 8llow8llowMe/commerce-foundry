package com.commercefoundry.security.token.revocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.security.issuer.JwtIssuanceProperties;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RevokedAccessTokenKeysTest {

    @Test
    @DisplayName("키는 auth:revoked-access-token:{jti} 다 — auth 가 쓰고 게이트웨이가 읽는 형식이 이 함수 하나로 고정된다")
    void keyFormat() {
        String jti = UUID.randomUUID().toString();

        assertThat(RevokedAccessTokenKeys.of(jti).value()).isEqualTo("auth:revoked-access-token:" + jti);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a:b", "a*", "has space"})
    @DisplayName("RedisKey id 규칙에 맞지 않는 jti 는 거부한다")
    void rejectsMalformedJti(String jti) {
        assertThatThrownBy(() -> RevokedAccessTokenKeys.of(jti)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("TTL 은 access 토큰 수명 상한(1시간) + 시계 오차 허용이다 — 설정한 access TTL 과 무관하다")
    void ttlIsUpperBoundPlusClockSkew() {
        assertThat(RevokedAccessTokenKeys.ttl(Duration.ofSeconds(60))).isEqualTo(Duration.ofMinutes(61));
        assertThat(RevokedAccessTokenKeys.ttl(Duration.ZERO)).isEqualTo(JwtIssuanceProperties.MAX_ACCESS_TOKEN_TTL);
    }

    @Test
    @DisplayName("음수 · null 시계 오차는 거부한다")
    void rejectsInvalidClockSkew() {
        assertThatThrownBy(() -> RevokedAccessTokenKeys.ttl(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RevokedAccessTokenKeys.ttl(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
