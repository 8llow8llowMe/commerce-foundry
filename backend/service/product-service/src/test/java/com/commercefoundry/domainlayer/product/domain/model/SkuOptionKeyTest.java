package com.commercefoundry.domainlayer.product.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SkuOptionKeyTest {

    @Nested
    @DisplayName("of")
    class Of {

        @Test
        @DisplayName("옵션 값 아이디를 오름차순으로 , 로 잇는다")
        void sortsAscending() {
            SkuOptionKey key = SkuOptionKey.of(List.of(23L, 5L, 11L));

            assertThat(key.value()).isEqualTo("5,11,23");
            assertThat(key.valueIds()).containsExactly(5L, 11L, 23L);
            assertThat(key.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("중복은 한 번만 남긴다")
        void removesDuplicates() {
            assertThat(SkuOptionKey.of(List.of(11L, 5L, 11L)).value()).isEqualTo("5,11");
        }

        @Test
        @DisplayName("옵션이 없으면 빈 문자열이다")
        void empty() {
            SkuOptionKey key = SkuOptionKey.of(List.of());

            assertThat(key.value()).isEmpty();
            assertThat(key.valueIds()).isEmpty();
            assertThat(key.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("순서가 달라도 같은 조합이면 같은 키다")
        void sameCombinationEquals() {
            assertThat(SkuOptionKey.of(List.of(3L, 1L))).isEqualTo(SkuOptionKey.of(List.of(1L, 3L))).hasSameHashCodeAs(SkuOptionKey.of(List.of(1L, 3L)));
            assertThat(SkuOptionKey.of(List.of(1L, 3L))).isNotEqualTo(SkuOptionKey.of(List.of(1L, 2L)));
        }

        @Test
        @DisplayName("중복을 빼고 MAX_GROUPS 개까지만 조합한다")
        void maxGroups() {
            assertThat(SkuOptionKey.of(List.of(1L, 2L, 3L, 4L, 5L, 5L)).valueIds()).hasSize(SkuOptionKey.MAX_GROUPS);
            assertThatThrownBy(() -> SkuOptionKey.of(List.of(1L, 2L, 3L, 4L, 5L, 6L))).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("양수가 아닌 아이디 · null 은 거절한다")
        void rejectsInvalidIds() {
            assertThatThrownBy(() -> SkuOptionKey.of(List.of(0L))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> SkuOptionKey.of(List.of(-1L))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> SkuOptionKey.of(Arrays.asList(1L, null))).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("parse")
    class Parse {

        @Test
        @DisplayName("저장된 문자열을 아이디 목록으로 읽는다 — 순서는 그대로다")
        void parsesInOrder() {
            assertThat(SkuOptionKey.parse("5,11,23")).containsExactly(5L, 11L, 23L);
            assertThat(SkuOptionKey.parse("23,5")).containsExactly(23L, 5L);
        }

        @Test
        @DisplayName("빈 문자열은 빈 목록이다")
        void empty() {
            assertThat(SkuOptionKey.parse("")).isEmpty();
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"1,,2", "a", "1,", ",1", " 1", "1;2"})
        @DisplayName("숫자가 아닌 조각이 있으면 거절한다")
        void rejectsMalformed(String value) {
            assertThatThrownBy(() -> SkuOptionKey.parse(value)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("parse → of 로 정규형을 되찾는다")
        void roundTrip() {
            SkuOptionKey key = SkuOptionKey.of(List.of(23L, 5L));

            assertThat(SkuOptionKey.of(SkuOptionKey.parse(key.value()))).isEqualTo(key);
            assertThat(SkuOptionKey.of(SkuOptionKey.parse("23,5,5")).value()).isEqualTo("5,23");
        }
    }
}
