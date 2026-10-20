package com.commercefoundry.domainlayer.shippingaddress.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ShippingAddressContentTest {

    @Nested
    @DisplayName("국내(KR)")
    class Domestic {

        @Test
        @DisplayName("앞뒤 공백을 지우고, 연락처는 하이픈 · 공백을 빼고, 빈 선택 칸은 null 로 저장한다")
        void normalizes() {
            ShippingAddressContent content = ShippingAddressContent.of(
                "  ", " 홍길동 ", "010-1234 5678", "KR", " 06236 ", " 서울특별시 강남구 테헤란로 123 ", "", null, "   "
            );

            assertThat(content).isEqualTo(new ShippingAddressContent(
                null, "홍길동", "01012345678", "KR", "06236", "서울특별시 강남구 테헤란로 123", null, null, null
            ));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"01012345678", "010-123-4567", "0111234567", "02-123-4567", "031-1234-5678", "064 712 3456"})
        @DisplayName("휴대폰(01x 10~11자리)과 지역번호 유선(0으로 시작 9~11자리)을 받는다")
        void acceptsPhones(String phone) {
            assertThat(ShippingAddressContent.isValidPhone("KR", phone)).isTrue();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"1588-1234", "+821012345678", "010-1234-56789", "02-12-345", "010-abcd-5678", ""})
        @DisplayName("0 으로 시작하지 않거나 · + 국가번호 · 자릿수 밖 · 숫자 아닌 값은 거절한다")
        void rejectsPhones(String phone) {
            assertThat(ShippingAddressContent.isValidPhone("KR", phone)).isFalse();
        }

        @ParameterizedTest(name = "[{0}]")
        @CsvSource(nullValues = "NULL", value = {"NULL", "''", "1234", "123456", "12a45"})
        @DisplayName("우편번호는 숫자 5자리 필수다")
        void postalCodeRequired(String postalCode) {
            assertThatThrownBy(() -> domestic(postalCode, null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("postalCode");
        }

        @Test
        @DisplayName("도시는 선택이다 — 있으면 저장한다")
        void cityOptional() {
            assertThat(domestic("06236", null).city()).isNull();
            assertThat(domestic("06236", "서울").city()).isEqualTo("서울");
        }

        private ShippingAddressContent domestic(String postalCode, String city) {
            return ShippingAddressContent.of(null, "홍길동", "01012345678", "KR", postalCode, "서울특별시 강남구 테헤란로 123", null, city, null);
        }
    }

    @Nested
    @DisplayName("해외")
    class International {

        @Test
        @DisplayName("우편번호는 선택이고, 연락처는 + 를 남기고 구분자를 뺀다")
        void normalizes() {
            ShippingAddressContent content = ShippingAddressContent.of(
                "Office", "John Doe", "+1 415-555-0100", "US", null, "1 Market St", "Suite 100", "San Francisco", "CA"
            );

            assertThat(content.recipientPhone()).isEqualTo("+14155550100");
            assertThat(content.postalCode()).isNull();
            assertThat(content.city()).isEqualTo("San Francisco");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"SW1A 1AA", "100-0001", "10001", "a"})
        @DisplayName("우편번호는 영문 · 숫자 · 공백 · 하이픈 20자 이하면 받는다")
        void acceptsPostalCodes(String postalCode) {
            assertThat(international(postalCode, "London").postalCode()).isEqualTo(postalCode);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"SW1A_1AA", "123456789012345678901", "우편"})
        @DisplayName("그 밖의 문자 · 20자 초과 우편번호는 거절한다")
        void rejectsPostalCodes(String postalCode) {
            assertThatThrownBy(() -> international(postalCode, "London")).isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest(name = "[{0}]")
        @CsvSource(nullValues = "NULL", value = {"NULL", "''", "'   '"})
        @DisplayName("도시가 없으면 거절한다")
        void cityRequired(String city) {
            assertThatThrownBy(() -> international(null, city)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("city");
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"+14155550100, true", "14155550100, true", "1234567, true", "123456, false", "+1234567890123456, false", "+1-415-555-0100, true"})
        @DisplayName("연락처는 + 로 시작할 수 있는 숫자 7~15자리(E.164)다")
        void phones(String phone, boolean valid) {
            assertThat(ShippingAddressContent.isValidPhone("US", phone)).isEqualTo(valid);
        }

        private ShippingAddressContent international(String postalCode, String city) {
            return ShippingAddressContent.of(null, "John Doe", "+447700900123", "GB", postalCode, "10 Downing St", null, city, null);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"kr", "KOR", "K", "1R"})
    @DisplayName("국가 코드는 대문자 두 글자 형식만 받는다 — 지원 국가 목록은 보지 않는다")
    void countryCodeFormat(String countryCode) {
        assertThat(ShippingAddressContent.isValidCountryCode(countryCode)).isFalse();
        assertThat(ShippingAddressContent.isValidCountryCode("ZZ")).isTrue();
        assertThatThrownBy(() -> ShippingAddressContent.of(null, "홍길동", "01012345678", countryCode, "06236", "주소", null, "도시", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("길이를 넘으면 거절하고 메시지에 값을 싣지 않는다")
    void lengthLimits() {
        assertThatThrownBy(() -> ShippingAddressContent.of("가".repeat(31), "홍길동", "01012345678", "KR", "06236", "주소", null, null, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("label 길이가 규칙을 벗어났다");
        assertThatThrownBy(() -> ShippingAddressContent.of(null, "가".repeat(51), "01012345678", "KR", "06236", "주소", null, null, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("가가");
        assertThatThrownBy(() -> ShippingAddressContent.of(null, "홍길동", "01012345678", "KR", "06236", "가".repeat(201), null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ShippingAddressContent.of(null, "홍길동", "01012345678", "KR", "06236", " ", null, null, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("addressLine1 는 필수다");
    }

    @Test
    @DisplayName("저장된 행은 검증 없이 되살린다 — 규칙 밖 값(이관 데이터)도 그대로")
    void restoreSkipsValidation() {
        ShippingAddressContent restored = ShippingAddressContent.restore(null, "홍길동", "1588-1234", "KR", null, "주소", null, null, null);

        assertThat(restored.recipientPhone()).isEqualTo("1588-1234");
        assertThat(restored.postalCode()).isNull();
    }

    @Test
    @DisplayName("toString 은 국가 코드만 싣는다 — 받는 사람 · 연락처 · 주소를 싣지 않는다")
    void toStringMasksPersonalData() {
        ShippingAddressContent content = ShippingAddressContent.of("집", "홍길동", "01012345678", "KR", "06236", "서울 강남구", "101호", null, null);
        ShippingAddress address = ShippingAddress.create(1L, 2L, content, true);

        assertThat(content.toString()).isEqualTo("ShippingAddressContent[countryCode=KR]");
        assertThat(address.toString()).doesNotContain("홍길동", "01012345678", "06236", "서울", "101호", "집");
    }

    @Test
    @DisplayName("내용 교체는 아이디 · 회원 · 기본 여부 · 버전을 그대로 둔다")
    void replaceContentKeepsIdentity() {
        ShippingAddressContent before = ShippingAddressContent.of(null, "홍길동", "01012345678", "KR", "06236", "주소", null, null, null);
        ShippingAddressContent after = ShippingAddressContent.of("회사", "김철수", "0212345678", "KR", "04524", "다른 주소", null, null, null);

        ShippingAddress replaced = ShippingAddress.restore(1L, 2L, before, true, 3L).replaceContent(after);

        assertThat(replaced).isEqualTo(new ShippingAddress(1L, 2L, after, true, 3L));
    }
}
