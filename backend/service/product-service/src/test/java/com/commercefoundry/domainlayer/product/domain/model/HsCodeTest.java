package com.commercefoundry.domainlayer.product.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HsCodeTest {

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"621142", "62114200", "6211420000", "000000"})
    @DisplayName("숫자 6~10자리는 그대로 받는다 — 앞자리 0 도 지우지 않는다")
    void accepts(String value) {
        assertThat(new HsCode(value).value()).isEqualTo(value);
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"", "62114", "62114200001", "6211.42", "6211-42", " 621142", "621142 ", "62114A", "６２１１４２"})
    @DisplayName("숫자 6~10자리가 아니면(5 · 11자리, 점 · 하이픈 · 공백 · 문자 · 전각 숫자) 고쳐 받지 않고 실패한다")
    void rejects(String value) {
        assertThatThrownBy(() -> new HsCode(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null 은 받지 않는다 — 미입력은 HsCode 가 아니라 null 로 둔다")
    void rejectsNull() {
        assertThatThrownBy(() -> new HsCode(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("저장값 읽기: null 은 미입력(null), 맞는 값은 HsCode 다")
    void fromStored() {
        assertThat(HsCode.fromStoredOrNull(null)).isNull();
        assertThat(HsCode.fromStoredOrNull("6211420000")).isEqualTo(new HsCode("6211420000"));
    }

    @Test
    @DisplayName("저장값 읽기: 형식이 틀린 저장값은 데이터 오류로 실패하고 메시지에 저장값을 담는다")
    void fromStoredMalformed() {
        assertThatThrownBy(() -> HsCode.fromStoredOrNull("6211.42"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("6211.42");
    }
}
