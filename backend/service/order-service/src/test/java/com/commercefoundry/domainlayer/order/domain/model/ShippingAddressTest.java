package com.commercefoundry.domainlayer.order.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** 주문 배송지 규칙 — #7 주소록({@code ShippingAddressContent})과 같은 정규화 · 연락처 · 해외 도시 규칙. 값은 모두 가짜다. */
class ShippingAddressTest {

    private static ShippingAddress domestic(String phone) {
        return new ShippingAddress("테스트수령인", phone, "KR", "63100", "테스트시 테스트로 1", null, null, null, null);
    }

    private static ShippingAddress international(String phone, String city, String stateProvince) {
        return new ShippingAddress("Test Recipient", phone, "US", null, "1 Test St", null, city, stateProvince, null);
    }

    @Test
    @DisplayName("연락처는 공백 · 하이픈을 뺀 값으로 둔다 — 국내 0 으로 시작하는 9~11자리, 해외 + 가 붙을 수 있는 7~15자리")
    void normalizesPhone() {
        assertThat(domestic("010-1234 5678").recipientPhone()).isEqualTo("01012345678");
        assertThat(domestic("02-123-4567").recipientPhone()).isEqualTo("021234567");
        assertThat(international("+1 212-555-0100", "Testville", null).recipientPhone()).isEqualTo("+12125550100");
        assertThat(international("1234567", "Testville", null).recipientPhone()).isEqualTo("1234567");
        assertThat(international("+123456789012345", "Testville", null).recipientPhone()).isEqualTo("+123456789012345");
    }

    @ParameterizedTest
    @ValueSource(strings = {"1012345678", "01012345", "010123456789", "+821012345678", "010-1234-567a"})
    @DisplayName("국내 연락처 규칙(0 으로 시작하는 숫자 9~11자리)을 벗어나면 만들지 않는다 — 메시지에 값을 싣지 않는다")
    void rejectsDomesticPhone(String phone) {
        assertThatThrownBy(() -> domestic(phone))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("recipientPhone")
            .message().doesNotContain(phone);
    }

    @ParameterizedTest
    @ValueSource(strings = {"123456", "1234567890123456", "++1234567", "1-800-FLOWERS", "+"})
    @DisplayName("해외 연락처 규칙(+ 가 붙을 수 있는 숫자 7~15자리)을 벗어나면 만들지 않는다")
    void rejectsInternationalPhone(String phone) {
        assertThatThrownBy(() -> international(phone, "Testville", null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("recipientPhone");
    }

    @Test
    @DisplayName("도시 · 주는 앞뒤 공백을 지우고 비면 null 이다 — 국내는 도시가 없어도 된다")
    void normalizesCityAndStateProvince() {
        ShippingAddress international = international("12125550100", "  New York  ", " ");
        assertThat(international.city()).isEqualTo("New York");
        assertThat(international.stateProvince()).isNull();

        ShippingAddress domestic = new ShippingAddress("테스트수령인", "01000000001", "KR", "63100", "테스트시 테스트로 1", null, "   ", "", null);
        assertThat(domestic.city()).isNull();
        assertThat(domestic.stateProvince()).isNull();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("해외 배송지는 도시가 필수다 — 비었거나 공백뿐이면 없는 값이다")
    void internationalRequiresCity(String city) {
        assertThatThrownBy(() -> international("12125550100", city, "NY")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("city");
    }

    @Test
    @DisplayName("도시 · 주는 각 100자까지다")
    void cityAndStateProvinceLength() {
        String hundred = "a".repeat(ShippingAddress.CITY_MAX_LENGTH);

        assertThat(international("12125550100", hundred, hundred).city()).hasSize(100);
        assertThatThrownBy(() -> international("12125550100", hundred + "a", null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("city");
        assertThatThrownBy(() -> international("12125550100", "Testville", hundred + "a"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stateProvince");
    }

    @Test
    @DisplayName("국내 배송지는 우편번호가 필수이고, 국가 코드는 대문자 두 글자다")
    void postalCodeAndCountryCode() {
        assertThatThrownBy(() -> new ShippingAddress("테스트수령인", "01000000001", "KR", null, "테스트시 테스트로 1", null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("postalCode");
        assertThatThrownBy(() -> new ShippingAddress("테스트수령인", "01000000001", "kr", "63100", "테스트시 테스트로 1", null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("countryCode");
    }

    @Test
    @DisplayName("문자열 표현은 국가만 보인다 — 이름 · 연락처 · 주소 · 도시가 로그로 새지 않는다")
    void toStringHidesPersonalData() {
        assertThat(international("+12125550100", "Testville", "NY").toString())
            .isEqualTo("ShippingAddress[countryCode=US]")
            .doesNotContain("Test Recipient", "12125550100", "1 Test St", "Testville");
    }
}
