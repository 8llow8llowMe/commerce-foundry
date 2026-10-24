package com.commercefoundry.ai.masking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.Arrays;
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
    @DisplayName("호출자가 아는 값은 [PII] 가 된다 — 두 글자 미만 · null 은 무시한다")
    void masksKnownValues() {
        String masked = masker.mask("받는 사람 Hong Gildong, 별칭 Hong, 등급 A", Arrays.asList("Hong", "Hong Gildong", "A", null));

        assertThat(masked).isEqualTo("받는 사람 [PII], 별칭 [PII], 등급 A");
    }

    @Test
    @DisplayName("알려진 값은 대소문자 · 공백 차이를 무시하고 맞춘다")
    void knownValuesIgnoreCaseAndSpacing() {
        assertThat(masker.mask("hong   gildong 님, 홍 길동 님", List.of("Hong Gildong", "홍 길동"))).isEqualTo("[PII] 님, [PII] 님");
        assertThat(masker.mask("홍길동 님", List.of("홍 길동"))).isEqualTo("[PII] 님");
    }

    @Test
    @DisplayName("알려진 값과 기본 패턴이 겹치면 합친 구간을 가린다 — 이메일 앞부분만 가려 도메인이 남지 않는다. 주소의 일부만 가려 앞이 남지 않는다")
    void overlappingSpansMerge() {
        assertThat(masker.mask("메일 gildong@company.example 로", List.of("gildong"))).isEqualTo("메일 [PII] 로");
        assertThat(masker.mask("주소 서울 강남구 테헤란로 123 입니다", List.of("서울 강남구 테헤란로 123"))).isEqualTo("주소 [PII] 입니다");
    }

    @Test
    @DisplayName("설정 패턴은 [이름 대문자] 로 바꾼다")
    void masksConfiguredPattern() {
        assertThat(masker.mask("주문 CF-20261024-0001 문의", List.of())).isEqualTo("주문 [ORDER_NUMBER] 문의");
    }

    @ParameterizedTest
    @ValueSource(strings = {"member@example.com", "first.last+tag@mail.example.co.kr", "user @ example.com", "user＠example.com", "홍길동@test.kr"})
    @DisplayName("이메일 — 공백 · 전각 @ · 유니코드 앞부분 포함")
    void email(String value) {
        assertThat(masker.mask("메일 " + value + " 입니다", List.of())).isEqualTo("메일 [EMAIL] 입니다");
    }

    @ParameterizedTest
    @ValueSource(strings = {"010-1234-5678", "01012345678", "010 1234 5678", "02-123-4567", "(02) 123-4567", "031-1234-5678", "+82-10-1234-5678",
        "821012345678", "010 - 1234 - 5678", "010–1234–5678", "010_1234_5678", "０１０-１２３４-５６７８", "1588-1234", "+1 415 555 0100"})
    @DisplayName("전화번호 — 구분자 · 괄호 · 대시 변형 · 전각 숫자 · 국가번호 포함")
    void phone(String value) {
        assertThat(masker.mask("연락처 " + value + " 입니다", List.of())).isEqualTo("연락처 [PHONE] 입니다");
    }

    @ParameterizedTest
    @ValueSource(strings = {"900101-1234567", "9001011234567", "800101–1234567"})
    @DisplayName("주민등록번호는 전화 · 카드보다 먼저 잡는다")
    void residentRegistrationNumber(String value) {
        assertThat(masker.mask("번호 " + value, List.of())).isEqualTo("번호 [RRN]");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234-5678-9012-3456", "1234 5678 9012 3456", "1234567890123456"})
    @DisplayName("카드번호")
    void card(String value) {
        assertThat(masker.mask("카드 " + value + " 로 결제", List.of())).isEqualTo("카드 [CARD] 로 결제");
    }

    @ParameterizedTest
    @ValueSource(strings = {"110-123-456789", "1002-123-456789", "123-456789-01-234"})
    @DisplayName("계좌번호 — 환불 계좌를 본문에 적는 문의")
    void account(String value) {
        assertThat(masker.mask("환불 계좌 " + value + " 입니다", List.of())).isEqualTo("환불 계좌 [ACCOUNT] 입니다");
    }

    @ParameterizedTest
    @ValueSource(strings = {"테헤란로 123", "테헤란로12번길 34", "판교역로 235-1", "역삼동 123-4번지", "101동 1203호"})
    @DisplayName("도로명 · 번길 · 지번 · 동호수 주소")
    void address(String value) {
        assertThat(masker.mask("주소 " + value + " 입니다", List.of())).isEqualTo("주소 [ADDRESS] 입니다");
    }

    @Test
    @DisplayName("개인정보가 없는 본문 · 치수 · 가격 · 날짜는 그대로다")
    void leavesOrdinaryText() {
        String text = "가슴둘레 102cm, 총장 68cm 이고 가격은 89,000원입니다. 사이즈 M 을 추천합니다. 2026-10-24 입고 예정.";

        assertThat(masker.mask(text, List.of())).isEqualTo(text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"가", "a", "1", "ab@", "가1"})
    @DisplayName("공백 없는 긴 입력(20만 자)도 길이에 비례하는 시간에 끝난다 — 되추적 폭주(ReDoS)가 없다")
    void linearTimeOnLongRuns(String unit) {
        String text = unit.repeat(200_000 / unit.length());

        assertTimeoutPreemptively(Duration.ofSeconds(3), () -> masker.mask(text, List.of("Hong Gildong")));
    }

    @Test
    @DisplayName("null 은 null 이다")
    void nullText() {
        assertThat(masker.mask(null, List.of("Hong Gildong"))).isNull();
    }
}
