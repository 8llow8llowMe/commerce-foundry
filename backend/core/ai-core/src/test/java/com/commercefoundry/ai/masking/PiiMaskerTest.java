package com.commercefoundry.ai.masking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 픽스처 값은 가짜다. */
class PiiMaskerTest {

    private final PiiMasker masker = new PiiMasker(Map.of("order-number", "CF-\\d{8}-\\d{4}"));

    @Test
    @DisplayName("호출자가 아는 값은 긴 것부터 정확히 일치로 [PII] 가 된다 — 두 글자 미만 · null 은 무시한다")
    void masksKnownValues() {
        String masked = masker.mask("받는 사람 Hong Gildong, 별칭 Hong, 등급 A", java.util.Arrays.asList("Hong", "Hong Gildong", "A", null));

        assertThat(masked).isEqualTo("받는 사람 [PII], 별칭 [PII], 등급 A");
    }

    @Test
    @DisplayName("설정 패턴은 [이름 대문자] 로 바꾼다")
    void masksConfiguredPattern() {
        assertThat(masker.mask("주문 CF-20261024-0001 문의", List.of())).isEqualTo("주문 [ORDER_NUMBER] 문의");
    }

    @ParameterizedTest
    @ValueSource(strings = {"member@example.com", "first.last+tag@mail.example.co.kr"})
    @DisplayName("이메일")
    void email(String value) {
        assertThat(masker.mask("메일 " + value + " 입니다", List.of())).isEqualTo("메일 [EMAIL] 입니다");
    }

    @ParameterizedTest
    @ValueSource(strings = {"010-1234-5678", "01012345678", "010 1234 5678", "02-123-4567", "031-1234-5678", "+82-10-1234-5678", "1588-1234", "+1 415 555 0100"})
    @DisplayName("전화번호")
    void phone(String value) {
        assertThat(masker.mask("연락처 " + value + " 입니다", List.of())).isEqualTo("연락처 [PHONE] 입니다");
    }

    @Test
    @DisplayName("주민등록번호는 전화번호보다 먼저 잡는다")
    void residentRegistrationNumber() {
        assertThat(masker.mask("번호 900101-1234567", List.of())).isEqualTo("번호 [RRN]");
    }

    @ParameterizedTest
    @ValueSource(strings = {"테헤란로 123", "테헤란로12번길 34", "판교역로 235-1", "역삼동 123-4번지", "101동 1203호"})
    @DisplayName("도로명 · 번길 · 지번 · 동호수 주소")
    void address(String value) {
        assertThat(masker.mask("주소 " + value + " 입니다", List.of())).isEqualTo("주소 [ADDRESS] 입니다");
    }

    @Test
    @DisplayName("개인정보가 없는 본문 · 치수 · 가격은 그대로다")
    void leavesOrdinaryText() {
        String text = "가슴둘레 102cm, 총장 68cm 이고 가격은 89,000원입니다. 사이즈 M 을 추천합니다.";

        assertThat(masker.mask(text, List.of())).isEqualTo(text);
    }

    @Test
    @DisplayName("null 은 null 이다")
    void nullText() {
        assertThat(masker.mask(null, List.of("Hong Gildong"))).isNull();
    }
}
