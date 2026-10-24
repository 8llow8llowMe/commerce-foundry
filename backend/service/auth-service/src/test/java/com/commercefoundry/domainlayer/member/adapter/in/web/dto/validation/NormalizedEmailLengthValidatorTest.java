package com.commercefoundry.domainlayer.member.adapter.in.web.dto.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.domainlayer.member.domain.model.Member;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 소문자화로 길어지는 이메일(#233) — 원문 254자 이하 · 정규화 뒤 254자 초과만 거절하고, 나머지는 {@code @Size} · {@code @NotBlank} 에 맡긴다. */
class NormalizedEmailLengthValidatorTest {

    /** 소문자로 바꾸면 {@code i̇}(2글자)가 된다. */
    private static final String DOTTED_CAPITAL_I = "İ";

    private final NormalizedEmailLengthValidator validator = new NormalizedEmailLengthValidator();

    @Test
    @DisplayName("원문 254자 · 정규화 255자(İ 하나)는 거절한다")
    void rejectsWhenLowercasingExceedsMax() {
        String email = email(1, 254);

        assertThat(email).hasSize(254);
        assertThat(Member.canonicalEmail(email)).hasSize(255);
        assertThat(validator.isValid(email, null)).isFalse();
    }

    @Test
    @DisplayName("이슈 재현 — 로컬 파트에 İ 64자, 원문 204자 · 정규화 268자는 거절한다")
    void rejectsIssueReproduction() {
        String email = DOTTED_CAPITAL_I.repeat(64) + "@" + "a".repeat(63) + "." + "b".repeat(63) + ".example.com";

        assertThat(email).hasSize(204);
        assertThat(validator.isValid(email, null)).isFalse();
    }

    @Test
    @DisplayName("정규화 뒤 정확히 254자는 통과한다")
    void acceptsWhenLowercasedLengthIsMax() {
        String email = email(1, 253);

        assertThat(Member.canonicalEmail(email)).hasSize(254);
        assertThat(validator.isValid(email, null)).isTrue();
    }

    @Test
    @DisplayName("원문이 이미 254자를 넘으면 통과시킨다 — @Size 가 AUTH_102 를 한 번만 낸다")
    void leavesRawOverflowToSize() {
        assertThat(validator.isValid(email(0, 255), null)).isTrue();
        assertThat(validator.isValid(email(10, 255), null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"customer@example.com", "Customer@Example.COM", " customer@example.com "})
    @DisplayName("보통 이메일은 통과한다")
    void acceptsOrdinaryEmail(String email) {
        assertThat(validator.isValid(email, null)).isTrue();
    }

    @Test
    @DisplayName("ASCII 254자는 통과한다 — 소문자화로 길이가 바뀌지 않는다")
    void acceptsAsciiMax() {
        assertThat(validator.isValid(email(0, 254).toUpperCase(Locale.ROOT), null)).isTrue();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "　", "\t"})
    @DisplayName("null · 공백만인 값은 통과시킨다 — @NotBlank(AUTH_101) 몫이다")
    void leavesBlankToNotBlank(String email) {
        assertThat(validator.isValid(email, null)).isTrue();
    }

    @Test
    @DisplayName("앞뒤 공백은 지운 뒤 잰다 — 원문 254자에 공백 4자면 정규화 상한까지 İ 4개는 통과, 5개는 거절")
    void stripsBeforeMeasuring() {
        String fourDotted = "  " + email(4, 250) + "  ";
        String fiveDotted = "  " + email(5, 250) + "  ";

        assertThat(fourDotted).hasSize(254);
        assertThat(Member.canonicalEmail(fourDotted)).hasSize(254);
        assertThat(validator.isValid(fourDotted, null)).isTrue();

        assertThat(Member.canonicalEmail(fiveDotted)).hasSize(255);
        assertThat(validator.isValid(fiveDotted, null)).isFalse();
    }

    /** 로컬 파트를 İ {@code dotted} 개로 시작해 원문 {@code length} 자를 맞춘 이메일. 정규화하면 {@code length + dotted} 자다. */
    private static String email(int dotted, int length) {
        String local = DOTTED_CAPITAL_I.repeat(dotted) + "u";
        return local + "@" + "a".repeat(length - local.length() - 1 - ".com".length()) + ".com";
    }
}
