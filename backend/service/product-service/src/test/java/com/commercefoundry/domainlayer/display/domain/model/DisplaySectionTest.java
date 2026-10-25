package com.commercefoundry.domainlayer.display.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DisplaySectionTest {

    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

    @Nested
    @DisplayName("노출 조건 isLiveAt — visible 이고 시작 포함 · 종료 제외")
    class IsLiveAt {

        @Test
        @DisplayName("기간이 없으면 visible 만 본다")
        void withoutPeriod() {
            assertThat(section(true, null, null).isLiveAt(NOW)).isTrue();
            assertThat(section(false, null, null).isLiveAt(NOW)).isFalse();
        }

        @Test
        @DisplayName("시작 시각과 같은 순간은 보인다(포함) — 1ns 전은 안 보인다")
        void startIsInclusive() {
            assertThat(section(true, NOW, null).isLiveAt(NOW)).isTrue();
            assertThat(section(true, NOW, null).isLiveAt(NOW.minusNanos(1))).isFalse();
        }

        @Test
        @DisplayName("종료 시각과 같은 순간은 안 보인다(제외) — 1ns 전은 보인다")
        void endIsExclusive() {
            assertThat(section(true, null, NOW).isLiveAt(NOW)).isFalse();
            assertThat(section(true, null, NOW).isLiveAt(NOW.minusNanos(1))).isTrue();
        }

        @Test
        @DisplayName("기간 안이어도 visible=false 면 안 보인다")
        void hiddenWinsOverPeriod() {
            assertThat(section(false, NOW.minusSeconds(60), NOW.plusSeconds(60)).isLiveAt(NOW)).isFalse();
        }

        @Test
        @DisplayName("이어 붙인 두 기간은 경계 순간에 하나만 보인다")
        void adjacentPeriodsDoNotOverlap() {
            DisplaySection first = section(true, NOW.minusSeconds(3600), NOW);
            DisplaySection second = section(true, NOW, NOW.plusSeconds(3600));

            assertThat(first.isLiveAt(NOW)).isFalse();
            assertThat(second.isLiveAt(NOW)).isTrue();
        }
    }

    @Nested
    @DisplayName("노출 상태 liveStatusAt — 숨김 → 예정 → 종료 → 노출 중")
    class LiveStatus {

        @Test
        @DisplayName("시작 시각과 같은 순간은 노출 중, 1μs 전은 예정이다")
        void startBoundary() {
            assertThat(section(true, NOW, null).liveStatusAt(NOW)).isEqualTo(DisplayLiveStatus.LIVE);
            assertThat(section(true, NOW, null).liveStatusAt(NOW.minusNanos(1_000))).isEqualTo(DisplayLiveStatus.SCHEDULED);
        }

        @Test
        @DisplayName("종료 시각과 같은 순간은 종료, 1μs 전은 노출 중이다")
        void endBoundary() {
            assertThat(section(true, null, NOW).liveStatusAt(NOW)).isEqualTo(DisplayLiveStatus.ENDED);
            assertThat(section(true, null, NOW).liveStatusAt(NOW.minusNanos(1_000))).isEqualTo(DisplayLiveStatus.LIVE);
        }

        @Test
        @DisplayName("숨김이 가장 앞선다 — 기간 전 · 안 · 뒤 모두 HIDDEN 이다")
        void hiddenFirst() {
            DisplaySection hidden = section(false, NOW, NOW.plusSeconds(60));

            assertThat(hidden.liveStatusAt(NOW.minusSeconds(1))).isEqualTo(DisplayLiveStatus.HIDDEN);
            assertThat(hidden.liveStatusAt(NOW)).isEqualTo(DisplayLiveStatus.HIDDEN);
            assertThat(hidden.liveStatusAt(NOW.plusSeconds(60))).isEqualTo(DisplayLiveStatus.HIDDEN);
        }

        @Test
        @DisplayName("isLiveAt 은 노출 상태가 LIVE 인가와 언제나 같다 — 고객 메인 필터와 어드민 노출 상태가 갈라지지 않는다")
        void isLiveMatchesStatus() {
            List<DisplaySection> sections = List.of(
                section(true, null, null), section(false, null, null), section(true, NOW, null), section(true, NOW.plusSeconds(1), null),
                section(true, null, NOW), section(true, null, NOW.plusSeconds(1)), section(true, NOW.minusSeconds(1), NOW.plusSeconds(1))
            );
            for (DisplaySection section : sections) {
                assertThat(section.isLiveAt(NOW)).as(section.toString()).isEqualTo(section.liveStatusAt(NOW) == DisplayLiveStatus.LIVE);
            }
        }
    }

    @Nested
    @DisplayName("기간 hasValidPeriod — 종료가 시작보다 뒤")
    class Period {

        @Test
        @DisplayName("한쪽이라도 없으면 맞다")
        void openEnded() {
            assertThat(section(true, null, null).hasValidPeriod()).isTrue();
            assertThat(section(true, NOW, null).hasValidPeriod()).isTrue();
            assertThat(section(true, null, NOW).hasValidPeriod()).isTrue();
        }

        @Test
        @DisplayName("종료가 시작과 같거나 앞이면 틀리다")
        void endNotAfterStart() {
            assertThat(section(true, NOW, NOW).hasValidPeriod()).isFalse();
            assertThat(section(true, NOW, NOW.minusSeconds(1)).hasValidPeriod()).isFalse();
            assertThat(section(true, NOW, NOW.plusNanos(1_000)).hasValidPeriod()).isTrue();
        }
    }

    @Nested
    @DisplayName("링크 주소 isAllowedLinkUrl")
    class LinkUrl {

        @ParameterizedTest
        @ValueSource(strings = {"/", "/products?categoryId=11", "/products/1#reviews", "https://brand.example.com/story", "HTTPS://brand.example.com"})
        @DisplayName("'/' 로 시작하는 상대 경로와 https 절대 주소는 받는다")
        void allowed(String url) {
            assertThat(DisplaySection.isAllowedLinkUrl(url)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "//evil.example.com/x", "http://brand.example.com", "javascript:alert(1)", "products/1", "https://user@brand.example.com",
            "https:///nohost", "/products /1", "/products\t1", "/a\\b", "/%zz", "", "mailto:a@b.c", "https://brand.example.com/\u0000"
        })
        @DisplayName("프로토콜 상대 · http · 다른 스킴 · 상대 경로(슬래시 없음) · 사용자 정보 · 공백 · 제어 문자 · 역슬래시 · 깨진 인코딩은 거절한다")
        void rejected(String url) {
            assertThat(DisplaySection.isAllowedLinkUrl(url)).isFalse();
        }
    }

    @Nested
    @DisplayName("저장 정밀도 · 범위")
    class Precision {

        @Test
        @DisplayName("시작 · 종료는 마이크로초로 잘린다 — 나노초만 다른 두 시각은 같은 시각이라 기간이 틀리다")
        void truncatesToMicros() {
            Instant start = Instant.parse("2026-11-01T00:00:00.123456001Z");
            DisplaySection section = section(true, start, start.plusNanos(1));

            assertThat(section.startAt()).isEqualTo(Instant.parse("2026-11-01T00:00:00.123456Z"));
            assertThat(section.endAt()).isEqualTo(section.startAt());
            assertThat(section.hasValidPeriod()).isFalse();
        }

        @Test
        @DisplayName("저장 가능 범위는 0001-01-01T00:00:00Z ~ 9999-12-31T23:59:59.999999Z 이다")
        void storableRange() {
            assertThat(DisplaySection.isStorablePeriodAt(null)).isTrue();
            assertThat(DisplaySection.isStorablePeriodAt(Instant.parse("9999-12-31T23:59:59.999999Z"))).isTrue();
            assertThat(DisplaySection.isStorablePeriodAt(Instant.parse("+10000-01-01T00:00:00Z"))).isFalse();
            assertThat(DisplaySection.isStorablePeriodAt(Instant.parse("0001-01-01T00:00:00Z"))).isTrue();
            assertThat(DisplaySection.isStorablePeriodAt(Instant.parse("0000-12-31T23:59:59Z"))).isFalse();
        }
    }

    @Test
    @DisplayName("노출 순서가 음수면 만들 수 없다")
    void negativeDisplayOrder() {
        assertThatThrownBy(() -> new DisplaySection(1L, DisplaySectionType.HERO, null, null, null, null, null, -1, true, null, null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static DisplaySection section(boolean visible, Instant startAt, Instant endAt) {
        return new DisplaySection(1L, DisplaySectionType.HERO, "제목", null, null, null, null, 0, visible, startAt, endAt);
    }
}
