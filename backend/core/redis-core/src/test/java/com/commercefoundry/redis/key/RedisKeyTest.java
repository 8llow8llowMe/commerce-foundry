package com.commercefoundry.redis.key;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RedisKeyTest {

    @Nested
    @DisplayName("형식 — {owner}:{domain}:{id...}")
    class Format {

        @Test
        @DisplayName("owner · domain · id 를 ':' 로 잇는다")
        void joinsWithColon() {
            assertThat(RedisKey.of("auth", "refresh-token", "4611686018427387904").value())
                .isEqualTo("auth:refresh-token:4611686018427387904");
        }

        @Test
        @DisplayName("id 가 여럿이면 순서대로 잇는다")
        void joinsMultipleIdParts() {
            assertThat(RedisKey.of("order", "stock-lock", "4611686018427387904", "2").value())
                .isEqualTo("order:stock-lock:4611686018427387904:2");
        }

        @ParameterizedTest
        @ValueSource(strings = {"3f2a9c1e-7b4d-4e8a-9c0f-2d6b1a5e7c3f", "dGVzdC10b2tlbg_-", "203.0.113.7"})
        @DisplayName("UUID · base64url · 점이 든 값은 id 로 그대로 쓴다")
        void acceptsCommonIdShapes(String id) {
            assertThat(RedisKey.of("gateway", "rate-limit", id).value()).isEqualTo("gateway:rate-limit:" + id);
        }

        @Test
        @DisplayName("생성자로 만든 키도 같은 규칙을 지키고, of 로 만든 키와 같다")
        void constructorValidatesToo() {
            assertThat(new RedisKey("auth:refresh-token:1")).isEqualTo(RedisKey.of("auth", "refresh-token", "1"));
            assertThatThrownBy(() -> new RedisKey("auth:refresh-token")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new RedisKey("Auth:refresh-token:1")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new RedisKey("auth:refresh-token:1::2")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new RedisKey(null)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("owner · domain — 소문자 kebab 만")
    class Name {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"Auth", "AUTH", "refreshToken", "refresh_token", "refresh token", "auth:x", "1auth", "-auth", " auth"})
        @DisplayName("대문자 · 밑줄 · 공백 · 콜론 · 숫자나 '-' 로 시작 · 빈 값은 owner 로 거부한다")
        void rejectsInvalidOwner(String owner) {
            assertThatThrownBy(() -> RedisKey.of(owner, "refresh-token", "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner");
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"Refresh-token", "refreshToken", "refresh_token", "refresh token", "refresh:token", "2fa"})
        @DisplayName("같은 규칙을 domain 에도 적용한다")
        void rejectsInvalidDomain(String domain) {
            assertThatThrownBy(() -> RedisKey.of("auth", domain, "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("domain");
        }
    }

    @Nested
    @DisplayName("id — 비어 있지 않고 ':' · 공백이 없어야 한다")
    class IdPart {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"a:b", "a b", " ", "a\tb", "a\nb"})
        @DisplayName("빈 값 · 콜론 · 공백이 든 id 는 거부한다")
        void rejectsInvalidIdPart(String id) {
            assertThatThrownBy(() -> RedisKey.of("auth", "refresh-token", id)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("id 가 하나도 없으면 거부한다")
        void rejectsMissingIdParts() {
            assertThatThrownBy(() -> RedisKey.of("auth", "refresh-token")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> RedisKey.of("auth", "refresh-token", (String[]) null)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("뒤쪽 id 하나만 잘못돼도 거부한다")
        void rejectsInvalidTrailingIdPart() {
            assertThatThrownBy(() -> RedisKey.of("order", "stock-lock", "1", "")).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("거부 메시지에 id 값을 넣지 않는다 — 토큰 · 개인정보가 들어갈 수 있다")
        void messageOmitsIdValue() {
            assertThatThrownBy(() -> RedisKey.of("auth", "refresh-token", "secret token"))
                .isInstanceOf(IllegalArgumentException.class)
                .message().doesNotContain("secret");
        }
    }
}
