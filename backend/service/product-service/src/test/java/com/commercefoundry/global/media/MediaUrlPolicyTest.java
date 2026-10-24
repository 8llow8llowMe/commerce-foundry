package com.commercefoundry.global.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.commercefoundry.global.properties.StoreMediaProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class MediaUrlPolicyTest {

    private static final String BASE = "https://cdn.example.com/media";

    @Nested
    @DisplayName("공개 주소 설정")
    class Base {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        @DisplayName("비어 있으면 설정 안 됨이고 어떤 주소도 받지 않는다 (닫힌 기본값)")
        void notConfigured(String base) {
            MediaUrlPolicy policy = MediaUrlPolicy.of(base);

            assertThat(policy.isConfigured()).isFalse();
            assertThat(policy.isAllowed(BASE + "/a.jpg")).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"http://cdn.example.com", "ftp://cdn.example.com", "cdn.example.com", "https://cdn.example.com?x=1", "https://u@cdn.example.com", "https://"})
        @DisplayName("https 절대 주소가 아니거나 쿼리 · 사용자 정보가 있으면 기동을 멈춘다 — 메시지에 값을 싣지 않는다")
        void invalidBaseFailsFast(String base) {
            assertThatThrownBy(() -> MediaUrlPolicy.of(base))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(base);
        }

        @ParameterizedTest
        @ValueSource(strings = {"http://localhost:9000/bucket", "http://127.0.0.1:9000/bucket", "http://[::1]:9000/bucket"})
        @DisplayName("루프백 호스트만 http 를 허용한다 (local MinIO)")
        void loopbackHttp(String base) {
            MediaUrlPolicy policy = MediaUrlPolicy.of(base);

            assertThat(policy.isConfigured()).isTrue();
            assertThat(policy.isAllowed(base + "/displays/a.jpg")).isTrue();
        }

        @Test
        @DisplayName("프로퍼티는 앞뒤 공백과 끝의 '/' 를 지운다")
        void propertiesNormalize() {
            assertThat(new StoreMediaProperties("  https://cdn.example.com/media//  ").publicBaseUrl()).isEqualTo(BASE);
            assertThat(new StoreMediaProperties(null).publicBaseUrl()).isEmpty();
            assertThat(new StoreMediaProperties("/").publicBaseUrl()).isEmpty();
        }
    }

    @Nested
    @DisplayName("주소 판정")
    class Urls {

        private final MediaUrlPolicy policy = MediaUrlPolicy.of(BASE);

        @ParameterizedTest
        @ValueSource(strings = {BASE + "/a.jpg", BASE + "/displays/2026/hero-pc.webp", BASE + "/%ED%95%9C%EB%B3%B5.jpg"})
        @DisplayName("공개 주소 + '/' 아래의 키는 받는다")
        void allowed(String url) {
            assertThat(policy.isAllowed(url)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {
            BASE, BASE + "/", "https://cdn.example.com/mediax/a.jpg", "https://cdn.example.com/other/a.jpg", "https://cdn.example.com.evil.com/media/a.jpg",
            "http://cdn.example.com/media/a.jpg", "https://evil.example.com/media/a.jpg", BASE + "/../secret.jpg", BASE + "/%2e%2e/secret.jpg",
            BASE + "/%2E%2E/secret.jpg", BASE + "/a%2fb.jpg", BASE + "/a%5Cb.jpg", BASE + "/a\\b.jpg", BASE + "/a b.jpg", BASE + "/a　b.jpg",
            BASE + "/a.jpg?v=1", BASE + "/a.jpg#x", BASE + "/a\u0000.jpg", BASE + "/a​.jpg", "HTTPS://CDN.EXAMPLE.COM/media/a.jpg"
        })
        @DisplayName("공개 주소 밖 · 경로 조작 · 인코딩한 구분자 · 공백 · 제어 · 서식 문자 · 쿼리 · 프래그먼트는 거절한다")
        void rejected(String url) {
            assertThat(policy.isAllowed(url)).isFalse();
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"'", "(", ")", ",", ";", ":", "=", "+", "$", "@", "!", "*", "[", "]", "&", "\"", "<", ">", "`", "^", "|", "{", "}", "한"})
        @DisplayName("키의 허용 문자 집합(영숫자 · . _ ~ - / · %XX) 밖의 문자는 어디에 있든 거절한다 — CSS url() 탈출 · 매개변수 꼴 추적 값 방지")
        void rejectsCharacterOutsideAllowList(String character) {
            assertThat(policy.isAllowed(BASE + "/a" + character + "b.jpg")).isFalse();
            assertThat(policy.isAllowed(BASE + "/" + character + "a.jpg")).isFalse();
            assertThat(policy.isAllowed(BASE + "/a.jpg" + character)).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {BASE + "//a.jpg", BASE + "/a//b.jpg", BASE + "/a/", BASE + "/a%2", BASE + "/a%zzb.jpg", BASE + "/a%", BASE + "/a.jpg;v=1"})
        @DisplayName("빈 구간(연속 · 경계 · 끝의 //, /) · 깨진 퍼센트 인코딩 · 매개변수 꼴은 거절한다")
        void rejectsMalformedKey(String url) {
            assertThat(policy.isAllowed(url)).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {BASE + "/a-b_c~d.e/f/G9.webp", BASE + "/%EC%83%9D%ED%99%9C.jpg", BASE + "/a%20b.jpg"})
        @DisplayName("영숫자 · . _ ~ - / 와 올바른 %XX 만으로 된 키는 받는다")
        void acceptsAllowList(String url) {
            assertThat(policy.isAllowed(url)).isTrue();
        }

        @Test
        @DisplayName("null 은 거절한다")
        void nullUrl() {
            assertThat(policy.isAllowed(null)).isFalse();
        }
    }
}
