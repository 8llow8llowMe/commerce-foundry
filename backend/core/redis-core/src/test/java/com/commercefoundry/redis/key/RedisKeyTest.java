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
        @ValueSource(strings = {
            "3f2a9c1e-7b4d-4e8a-9c0f-2d6b1a5e7c3f",
            "n4bQgYhMfWWaL-qgxVrQFaO_TxsrC4Is0V1sFbDwCgg",
            "n4bQgYhMfWWaL+qgxVrQFaO/TxsrC4Is0V1sFbDwCgg=",
            "v1.2~rc"
        })
        @DisplayName("UUID · base64url · base64 표준(+ / =) · 점과 물결이 든 값은 id 로 그대로 쓴다")
        void acceptsCommonIdShapes(String id) {
            assertThat(RedisKey.of("auth", "refresh-token", id).value()).isEqualTo("auth:refresh-token:" + id);
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
    @DisplayName("id — ASCII [A-Za-z0-9._~=+/-] 1~128자")
    class IdPart {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"a:b", "a b", " ", "a\tb", "a\nb"})
        @DisplayName("빈 값 · 콜론 · 공백이 든 id 는 거부한다")
        void rejectsInvalidIdPart(String id) {
            assertThatThrownBy(() -> RedisKey.of("auth", "refresh-token", id)).isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {"a*", "a?", "a[1]", "a\\b", "{a}", "a\u0000b", "a\u007Fb", "a b", "a​b", "회원"})
        @DisplayName("glob · 해시 태그 중괄호 · 제어문자 · 비ASCII(NBSP · zero-width · 한글)는 거부한다")
        void rejectsCharactersOutsideAllowList(String id) {
            assertThatThrownBy(() -> RedisKey.of("auth", "refresh-token", id)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("128자까지 허용하고 129자부터 거부한다")
        void limitsLength() {
            assertThat(RedisKey.of("auth", "refresh-token", "a".repeat(128)).value()).hasSize("auth:refresh-token:".length() + 128);
            assertThatThrownBy(() -> RedisKey.of("auth", "refresh-token", "a".repeat(129))).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("IPv6 원문은 ':' 가 있어 거부한다 — 정규화한 뒤 인코딩해서 넣는다")
        void rejectsRawIpv6() {
            assertThatThrownBy(() -> RedisKey.of("gateway", "rate-limit", "2001:db8::1")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> RedisKey.of("gateway", "rate-limit", "::1")).isInstanceOf(IllegalArgumentException.class);
            assertThat(RedisKey.of("gateway", "rate-limit", "20010db8000000000000000000000001").value())
                .isEqualTo("gateway:rate-limit:20010db8000000000000000000000001");
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
