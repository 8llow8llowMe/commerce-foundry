package com.commercefoundry.domainlayer.cart.adapter.in.web.dto.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class NoControlCharactersValidatorTest {

    private final NoControlCharactersValidator validator = new NoControlCharactersValidator();

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "96\u00005", "\u0000", "96.5\u0000", "\u000096.5", "a\u0001b", "소매\t길게", "소매\n길게", "소매\r\n길게", "a\u007fb", "a\u0085b", "a\u009fb",
        "a\uD800b", "a\uDC00b", "\uD83D"
    })
    @DisplayName("제어 문자(C0 · DEL · C1 — NUL · 탭 · 개행 포함)와 짝 없는 서로게이트는 위치와 상관없이 위반이다")
    void rejects(String value) {
        assertThat(validator.isValid(value, null)).isFalse();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
        "96.5", "소매 길게 해 주세요", " 앞뒤 공백 ", "전각　공백", "😀", "👨‍👩‍👧", "👍🏽",
        "​", " ", ""
    })
    @DisplayName("한글 · 이모지(ZWJ · 피부색 포함) · 공백 · 서식 문자는 통과한다 — 비었는지는 @NotBlank 가 본다")
    void accepts(String value) {
        assertThat(validator.isValid(value, null)).isTrue();
    }

    @Test
    @DisplayName("null 은 통과한다 — @NotBlank 가 본다")
    void nullIsValid() {
        assertThat(validator.isValid(null, null)).isTrue();
    }
}
