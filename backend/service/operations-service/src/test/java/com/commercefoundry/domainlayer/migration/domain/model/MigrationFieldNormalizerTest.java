package com.commercefoundry.domainlayer.migration.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 이관 칸 정규화 규칙 ({@code migration-guide.md} §5). 값은 모두 합성이다. */
class MigrationFieldNormalizerTest {

    @Nested
    @DisplayName("상품명")
    class ProductName {

        @Test
        @DisplayName("앞뒤 공백을 빼고, 100자(코드 포인트)까지 그대로 둔다")
        void keepsWithinLimit() {
            assertThat(MigrationFieldNormalizer.productName("  합성 상품 0001  ").value()).isEqualTo("합성 상품 0001");
            assertThat(MigrationFieldNormalizer.productName("가".repeat(100)).value()).hasSize(100);
            assertThat(MigrationFieldNormalizer.productName("😀".repeat(100)).failed()).isFalse();
        }

        @Test
        @DisplayName("비었으면 NAME_REQUIRED, 100자를 넘으면 자르지 않고 NAME_TOO_LONG")
        void rejectsEmptyAndLong() {
            assertThat(MigrationFieldNormalizer.productName("   ").issue()).isEqualTo(MigrationIssueCode.NAME_REQUIRED);
            assertThat(MigrationFieldNormalizer.productName(null).issue()).isEqualTo(MigrationIssueCode.NAME_REQUIRED);
            assertThat(MigrationFieldNormalizer.productName("가".repeat(101)).issue()).isEqualTo(MigrationIssueCode.NAME_TOO_LONG);
        }

        @ParameterizedTest(name = "U+{0}")
        @ValueSource(ints = {0x0A, 0x09, 0x200B, 0x202E})
        @DisplayName("제어 · 서식 문자(줄바꿈 · 탭 · 폭 없는 공백 · 방향 바꿈)는 NAME_INVALID_CHARACTER")
        void rejectsControlAndFormat(int codePoint) {
            String raw = "합성" + Character.toString(codePoint) + "상품";
            assertThat(MigrationFieldNormalizer.productName(raw).issue()).isEqualTo(MigrationIssueCode.NAME_INVALID_CHARACTER);
        }
    }

    @Nested
    @DisplayName("금액")
    class Amount {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource(delimiter = '|', value = {"39000 | 39000", "39,000 | 39000", "39,000원 | 39000", " 0 | 0"})
        @DisplayName("쉼표 · 공백 · 원 을 빼고 0 이상 정수로 읽는다")
        void parses(String raw, long expected) {
            assertThat(MigrationFieldNormalizer.krwAmount(raw).value()).isEqualTo(expected);
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"-1000", "1000.5", "abc", "1e3", "1234567890123456"})
        @DisplayName("음수 · 소수 · 숫자 아님 · 너무 큰 값은 PRICE_INVALID")
        void rejectsInvalid(String raw) {
            assertThat(MigrationFieldNormalizer.krwAmount(raw).issue()).isEqualTo(MigrationIssueCode.PRICE_INVALID);
        }

        @Test
        @DisplayName("필수 금액이 비면 PRICE_REQUIRED, 선택 금액(정가)이 비면 빈 결과")
        void emptyAmount() {
            assertThat(MigrationFieldNormalizer.krwAmount(" ").issue()).isEqualTo(MigrationIssueCode.PRICE_REQUIRED);
            Normalized<Long> listPrice = MigrationFieldNormalizer.optionalKrwAmount("");
            assertThat(listPrice.failed()).isFalse();
            assertThat(listPrice.value()).isNull();
        }
    }

    @Nested
    @DisplayName("HS 코드 · 무게")
    class HsCodeAndWeight {

        @ParameterizedTest(name = "{0} → {1}")
        @CsvSource(delimiter = '|', value = {"6211.43 | 621143", "6211-43-0000 | 6211430000", "6211 43 | 621143"})
        @DisplayName("HS 코드는 점 · 하이픈 · 공백을 뺀 숫자 6~10자리다")
        void normalizesHsCode(String raw, String expected) {
            assertThat(MigrationFieldNormalizer.hsCode(raw).value()).isEqualTo(expected);
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"62114", "62114300001", "HS6211"})
        @DisplayName("형식 밖 HS 코드는 비우고 경고(HS_CODE_INVALID)")
        void warnsInvalidHsCode(String raw) {
            Normalized<String> result = MigrationFieldNormalizer.hsCode(raw);
            assertThat(result.issue()).isEqualTo(MigrationIssueCode.HS_CODE_INVALID);
            assertThat(result.issue().rejects()).isFalse();
        }

        @Test
        @DisplayName("무게는 그램 정수로 반올림한다 — 킬로그램은 1000 을 곱한다. 0 이하 · 숫자 아님은 경고, 빈 값은 빈 결과")
        void normalizesWeight() {
            assertThat(MigrationFieldNormalizer.weightGrams("350", WeightUnit.GRAM).value()).isEqualTo(350);
            assertThat(MigrationFieldNormalizer.weightGrams("0.3505", WeightUnit.KILOGRAM).value()).isEqualTo(351);
            assertThat(MigrationFieldNormalizer.weightGrams("1,200", WeightUnit.GRAM).value()).isEqualTo(1200);
            assertThat(MigrationFieldNormalizer.weightGrams("0", WeightUnit.GRAM).issue()).isEqualTo(MigrationIssueCode.WEIGHT_INVALID);
            assertThat(MigrationFieldNormalizer.weightGrams("0.0004", WeightUnit.KILOGRAM).issue()).isEqualTo(MigrationIssueCode.WEIGHT_INVALID);
            assertThat(MigrationFieldNormalizer.weightGrams("가벼움", WeightUnit.GRAM).issue()).isEqualTo(MigrationIssueCode.WEIGHT_INVALID);
            assertThat(MigrationFieldNormalizer.weightGrams(" ", WeightUnit.GRAM).value()).isNull();
        }
    }

    @Nested
    @DisplayName("이메일")
    class Email {

        @Test
        @DisplayName("앞뒤 공백을 빼고 소문자로 바꾼다")
        void normalizes() {
            assertThat(MigrationFieldNormalizer.email("  Member0001@Example.com ").value()).isEqualTo("member0001@example.com");
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"member0001", "member@example", "a@@example.com", "member 1@example.com"})
        @DisplayName("형식 밖이면 EMAIL_INVALID")
        void rejectsInvalid(String raw) {
            assertThat(MigrationFieldNormalizer.email(raw).issue()).isEqualTo(MigrationIssueCode.EMAIL_INVALID);
        }

        @Test
        @DisplayName("비었으면 EMAIL_REQUIRED, 254자를 넘으면 EMAIL_INVALID")
        void emptyAndLong() {
            assertThat(MigrationFieldNormalizer.email("").issue()).isEqualTo(MigrationIssueCode.EMAIL_REQUIRED);
            assertThat(MigrationFieldNormalizer.email("a".repeat(243) + "@example.com").issue()).isEqualTo(MigrationIssueCode.EMAIL_INVALID);
        }
    }
}
