package com.commercefoundry.domainlayer.member.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class RecoveryCodeTest {

    private final SecureRandom random = new SecureRandom();

    @Test
    @DisplayName("표시 형식은 xxxxx-xxxxx 이고 헷갈리는 글자(I · O · 0 · 1)가 없다")
    void generatesDisplayFormat() {
        for (int i = 0; i < 200; i++) {
            assertThat(RecoveryCode.generate(random)).matches("[A-HJ-NP-Z2-9]{5}-[A-HJ-NP-Z2-9]{5}");
        }
    }

    @Test
    @DisplayName("한 번에 서로 다른 8개를 만든다 — 정규화하면 그대로 대문자 10자다")
    void generatesDistinctSet() {
        List<String> codes = RecoveryCode.generate(random, RecoveryCode.COUNT);

        assertThat(codes).hasSize(8).doesNotHaveDuplicates();
        assertThat(codes).allSatisfy(code -> assertThat(RecoveryCode.normalize(code)).hasValue(code.replace("-", "")));
    }

    @Test
    @DisplayName("개수는 1 이상이어야 한다")
    void rejectsNonPositiveCount() {
        assertThatThrownBy(() -> RecoveryCode.generate(random, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"ABCDE-FGHJK, ABCDEFGHJK", "abcde-fghjk, ABCDEFGHJK", "ABCDEFGHJK, ABCDEFGHJK", "' abcde-fghjk ', ABCDEFGHJK", "AB-CDE-FGH-JK, ABCDEFGHJK"})
    @DisplayName("정규화 — 앞뒤 공백을 자르고 대문자로 바꾸고 하이픈을 뺀다")
    void normalizes(String input, String expected) {
        assertThat(RecoveryCode.normalize(input)).hasValue(expected);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "ABCDE-FGHJ", "ABCDE-FGHJKL", "ABCDE-FGHJ0", "ABCDE-FGHJI", "ABCDE FGHJK", "ABCDE_FGHJK"})
    @DisplayName("길이 · 알파벳이 맞지 않으면 비어 있다 — 해시 비교 전에 걸러 낸다")
    void rejectsMalformed(String input) {
        assertThat(RecoveryCode.normalize(input)).isEmpty();
    }
}
