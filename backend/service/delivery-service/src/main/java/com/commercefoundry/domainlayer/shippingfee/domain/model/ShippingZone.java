package com.commercefoundry.domainlayer.shippingfee.domain.model;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 해외 배송 국가 그룹 — 같은 무게 요금표를 쓰는 국가들 (예: 아시아, 북미).
 *
 * <p>만들 때 불변식을 지킨다 — 국가 · 무게 구간이 1개 이상, 그룹 안에서 국가가 겹치지 않고 구간 상한이 겹치지 않는다.
 * 어긋나면 {@link IllegalArgumentException} 이다. 어드민 입력은 Processor 가 먼저 도메인 오류 코드로 거절한다.
 *
 * @param name 어드민이 알아보는 이름. 정책 안에서 겹치지 않는다
 * @param countries 국가 코드 오름차순으로 정렬해 들고 있는다
 * @param rates 상한 무게 오름차순으로 정렬해 들고 있는다
 */
public record ShippingZone(long id, String name, List<ShippingZoneCountry> countries, List<ShippingZoneRate> rates) {

    private static final Comparator<ShippingZoneCountry> BY_COUNTRY_CODE = Comparator.comparing(ShippingZoneCountry::countryCode);
    private static final Comparator<ShippingZoneRate> BY_MAX_WEIGHT = Comparator.comparingInt(ShippingZoneRate::maxWeightGrams);

    public ShippingZone {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(countries, "countries");
        Objects.requireNonNull(rates, "rates");
        if (countries.isEmpty() || rates.isEmpty()) {
            throw new IllegalArgumentException("국가 그룹에는 국가와 무게 구간이 1개 이상 있어야 합니다: " + name);
        }
        countries = countries.stream().sorted(BY_COUNTRY_CODE).toList();
        rates = rates.stream().sorted(BY_MAX_WEIGHT).toList();
        requireDistinct(countries.stream().map(ShippingZoneCountry::countryCode).toList(), "국가", name);
        requireDistinct(rates.stream().map(ShippingZoneRate::maxWeightGrams).toList(), "무게 구간 상한", name);
    }

    public boolean contains(String countryCode) {
        return countries.stream().anyMatch(country -> country.countryCode().equals(countryCode));
    }

    /**
     * 무게가 드는 구간 — 상한이 무게 이상인 구간 중 상한이 가장 작은 것 (상한 포함).
     *
     * @param weightGrams 그램, 1 이상
     * @return 가장 큰 구간보다 무거우면 빈 값
     */
    public Optional<ShippingZoneRate> findRate(int weightGrams) {
        return rates.stream().filter(rate -> rate.covers(weightGrams)).findFirst();
    }

    /** 이 그룹이 받는 가장 무거운 무게 — 가장 큰 구간의 상한. */
    public int maxWeightGrams() {
        return rates.getLast().maxWeightGrams();
    }

    private static <T> void requireDistinct(List<T> values, String label, String zoneName) {
        Set<T> seen = new HashSet<>();
        for (T value : values) {
            if (!seen.add(value)) {
                throw new IllegalArgumentException("국가 그룹 %s 의 %s 이 겹칩니다: %s".formatted(zoneName, label, value));
            }
        }
    }
}
