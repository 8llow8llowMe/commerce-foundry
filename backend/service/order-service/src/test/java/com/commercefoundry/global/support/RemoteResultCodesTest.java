package com.commercefoundry.global.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 원격 resultCode 를 로그에 넣을 값으로 바꾸는 규칙 — product · delivery 응답 support 가 함께 쓴다 (#281). */
class RemoteResultCodesTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"DELIVERY_118", "PRODUCT_104", "SECURITY_007", "COMMON_005", "A_000"})
    @DisplayName("코드 모양이면 그대로 남긴다")
    void keepsCodeShape(String resultCode) {
        assertThat(RemoteResultCodes.loggable(resultCode)).isEqualTo(resultCode);
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"", " ", "delivery_118", "DELIVERY_1", "DELIVERY-118", "DELIVERY_118 ", "DELIVERY_118\r\nINJECTED level=ERROR", "<script>", "배송_118"})
    @DisplayName("코드 모양이 아니면(CR/LF · 소문자 · 임의 문자열) <unrecognized> 다")
    void replacesOtherStrings(String resultCode) {
        assertThat(RemoteResultCodes.loggable(resultCode)).isEqualTo(RemoteResultCodes.UNRECOGNIZED);
    }

    @Test
    @DisplayName("코드 모양이어도 40자를 넘으면 <unrecognized> 다 — 긴 값을 로그에 넣지 않는다. 코드가 없으면 null 그대로다")
    void longOrAbsent() {
        String forty = "D".repeat(36) + "_001";
        assertThat(RemoteResultCodes.loggable(forty)).isEqualTo(forty);
        assertThat(RemoteResultCodes.loggable("D" + forty)).isEqualTo(RemoteResultCodes.UNRECOGNIZED);
        assertThat(RemoteResultCodes.loggable(null)).isNull();
    }
}
