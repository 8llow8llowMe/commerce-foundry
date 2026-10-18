package com.commercefoundry.domainlayer.shippingfee.domain.model;

import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.asia;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ShippingZoneTest {

    private static ShippingZoneCountry country(long id, String code) {
        return new ShippingZoneCountry(id, code);
    }

    private static ShippingZoneRate rate(long id, int maxWeightGrams, long cents) {
        return new ShippingZoneRate(id, maxWeightGrams, usd(cents));
    }

    @ParameterizedTest(name = "{0}g → 상한 {1}g 구간")
    @CsvSource({"1, 500", "499, 500", "500, 500", "501, 1000", "1000, 1000", "1001, 2000", "2000, 2000"})
    @DisplayName("무게가 드는 구간은 상한이 무게 이상인 가장 작은 구간이다 (상한 포함)")
    void findRate(int weightGrams, int expectedMaxWeight) {
        assertThat(asia().findRate(weightGrams)).hasValueSatisfying(rate -> assertThat(rate.maxWeightGrams()).isEqualTo(expectedMaxWeight));
    }

    @Test
    @DisplayName("가장 큰 구간보다 무거우면 구간이 없다 — 최대 무게는 가장 큰 구간의 상한이다")
    void overMaxWeight() {
        assertThat(asia().findRate(2_001)).isEmpty();
        assertThat(asia().maxWeightGrams()).isEqualTo(2_000);
    }

    @Test
    @DisplayName("국가는 코드 순, 구간은 상한 순으로 정렬해 들고 있다 — 입력 순서와 무관하게 같은 구간을 찾는다")
    void sorts() {
        ShippingZone zone = new ShippingZone(1L, "그룹", List.of(country(2L, "SG"), country(3L, "JP")), List.of(rate(4L, 2_000, 300), rate(5L, 500, 100)));

        assertThat(zone.countries()).extracting(ShippingZoneCountry::countryCode).containsExactly("JP", "SG");
        assertThat(zone.rates()).extracting(ShippingZoneRate::maxWeightGrams).containsExactly(500, 2_000);
        assertThat(zone.findRate(400)).hasValueSatisfying(rate -> assertThat(rate.maxWeightGrams()).isEqualTo(500));
    }

    @Test
    @DisplayName("국가 · 구간이 없으면 거부한다")
    void requiresCountriesAndRates() {
        assertThatThrownBy(() -> new ShippingZone(1L, "그룹", List.of(), List.of(rate(2L, 500, 100)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ShippingZone(1L, "그룹", List.of(country(2L, "JP")), List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("그룹 안의 국가 중복 · 구간 상한 중복은 거부한다")
    void rejectsDuplicates() {
        assertThatThrownBy(() -> new ShippingZone(1L, "그룹", List.of(country(2L, "JP"), country(3L, "JP")), List.of(rate(4L, 500, 100))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("JP");
        assertThatThrownBy(() -> new ShippingZone(1L, "그룹", List.of(country(2L, "JP")), List.of(rate(3L, 500, 100), rate(4L, 500, 200))))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("500");
    }

    @ParameterizedTest
    @ValueSource(strings = {"KR", "jp", "J", "JPN"})
    @DisplayName("국가는 KR 이 아닌 대문자 두 글자여야 한다")
    void rejectsInvalidCountry(String countryCode) {
        assertThatThrownBy(() -> country(1L, countryCode)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    @DisplayName("구간 상한은 1g 이상이다")
    void rejectsNonPositiveMaxWeight(int maxWeightGrams) {
        assertThatThrownBy(() -> rate(1L, maxWeightGrams, 100)).isInstanceOf(IllegalArgumentException.class);
    }
}
