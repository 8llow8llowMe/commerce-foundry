package com.commercefoundry.domainlayer.shippingfee.domain.model;

import com.commercefoundry.shared.commerce.CurrencyCode;
import com.commercefoundry.shared.commerce.Money;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 해외 배송비 정책 — 기본료, 무료배송 기준, 관세 · 부가세 안내 문구, 국가 그룹별 무게 요금표.
 * {@code shipping_policy} 의 {@code INTERNATIONAL} 범위 행과 국가 그룹 · 국가 · 무게 구간 하위 테이블이다.
 *
 * <p>국내 정책({@link ShippingPolicy})과 모델을 나눈 이유 — 도서산간(우편번호 구간)은 해외에 없고, 국가 그룹 · 무게 구간은 국내에 없다.
 * 한 모델에 두면 "범위에 따라 비어 있어야 하는 필드" 가 생기고 계산이 범위 분기로 뒤섞인다. 범위 선택은 Processor 가 한다.
 *
 * <p>만들 때 불변식을 지킨다 — 모든 금액이 같은 통화(정책 통화), 무료배송 기준은 없거나 0 보다 크다, 국가 그룹 이름이 겹치지 않는다,
 * 한 국가는 한 그룹에만 든다. 어긋나면 {@link IllegalArgumentException} 이다. 어드민 입력은 Processor 가 먼저 도메인 오류 코드로 거절한다.
 *
 * @param baseFee 모든 해외 배송에 붙는 기본료 (취급 수수료, 0 이상)
 * @param freeShippingThreshold 무료배송 기준 주문 금액. 이상이면 기본료와 무게 요금을 받지 않는다. null 이면 무료배송이 없다
 * @param customsNotice 관세 · 부가세 고객 부담 안내 문구. 없으면 null
 * @param zones 국가 그룹 — 어드민이 정한 표시 순서다
 * @param version 낙관적 락 버전. 아직 저장되지 않은 정책이면 null — 저장된 정책을 교체할 때는 호출자가 본 버전이다
 * @param updatedAt 마지막 저장 시각. 아직 저장되지 않은 정책이면 null
 */
public record InternationalShippingPolicy(
    long id, Money baseFee, Money freeShippingThreshold, String customsNotice,
    List<ShippingZone> zones, Long version, Instant updatedAt
) {

    public InternationalShippingPolicy {
        Objects.requireNonNull(baseFee, "baseFee");
        Objects.requireNonNull(zones, "zones");
        zones = List.copyOf(zones);
        if (customsNotice != null && customsNotice.isBlank()) {
            throw new IllegalArgumentException("관세 · 부가세 안내 문구는 없으면 null 이다 — 빈 문자열을 담지 않는다");
        }
        CurrencyCode currency = baseFee.currency();
        if (freeShippingThreshold != null) {
            requireCurrency(currency, freeShippingThreshold, "freeShippingThreshold");
            if (freeShippingThreshold.isZero()) {
                throw new IllegalArgumentException("무료배송 기준 금액은 0 보다 커야 합니다");
            }
        }
        Set<String> zoneNames = new HashSet<>();
        Set<String> countryCodes = new HashSet<>();
        for (ShippingZone zone : zones) {
            if (!zoneNames.add(zone.name())) {
                throw new IllegalArgumentException("국가 그룹 이름이 겹칩니다: " + zone.name());
            }
            zone.rates().forEach(rate -> requireCurrency(currency, rate.fee(), "zone " + zone.name()));
            zone.countries().forEach(country -> {
                if (!countryCodes.add(country.countryCode())) {
                    throw new IllegalArgumentException("한 국가가 여러 국가 그룹에 들었습니다: " + country.countryCode());
                }
            });
        }
    }

    /** 정책 통화 — 기본료의 통화이고, 모든 금액이 같다. */
    public CurrencyCode currency() {
        return baseFee.currency();
    }

    /** @return 배송 국가가 든 국가 그룹. 어느 그룹에도 없으면 배송할 수 없는 국가라 빈 값 */
    public Optional<ShippingZone> findZone(String countryCode) {
        return zones.stream().filter(zone -> zone.contains(countryCode)).findFirst();
    }

    /**
     * 주문 금액 · 배송 국가 · 무게로 배송비를 계산한다.
     *
     * <ul>
     *   <li>무게 요금은 국가 그룹에서 상한이 무게 이상인 가장 작은 구간의 요금이다 (상한 포함).</li>
     *   <li>무료배송 기준이 있고 주문 금액이 기준 <b>이상</b>이면 기본료와 무게 요금을 모두 받지 않는다.</li>
     *   <li>그 밖에는 기본료 + 무게 요금이다. 도서산간 추가 배송비는 없다.</li>
     * </ul>
     *
     * @param orderAmount 할인 후 상품 합계 (배송비 제외). 정책과 같은 통화여야 한다
     * @param weightGrams 배송할 상품 총 무게 (그램, 1 이상)
     * @throws IllegalArgumentException 통화가 다르거나, 국가가 어느 그룹에도 없거나, 가장 큰 구간보다 무거울 때 — 호출자(Processor)가 먼저 도메인 오류로 거절한다
     * @throws IllegalStateException 저장되지 않은 정책(버전 없음)일 때 — 견적은 주문에 정책 버전을 스냅숏하므로 저장된 정책으로만 만든다
     */
    public ShippingFeeQuote quote(Money orderAmount, String countryCode, int weightGrams) {
        Objects.requireNonNull(orderAmount, "orderAmount");
        Objects.requireNonNull(countryCode, "countryCode");
        if (version == null) {
            throw new IllegalStateException("저장되지 않은 해외 배송비 정책으로 견적할 수 없습니다: id=" + id);
        }
        if (weightGrams <= 0) {
            throw new IllegalArgumentException("무게는 1g 이상이어야 합니다: " + weightGrams);
        }
        requireCurrency(currency(), orderAmount, "orderAmount");
        ShippingZone zone = findZone(countryCode)
            .orElseThrow(() -> new IllegalArgumentException("해외 배송비 정책에 없는 국가입니다: " + countryCode));
        ShippingZoneRate rate = zone.findRate(weightGrams)
            .orElseThrow(() -> new IllegalArgumentException("국가 그룹 %s 의 최대 무게를 넘었습니다: %dg".formatted(zone.name(), weightGrams)));

        boolean freeShipping = freeShippingThreshold != null && !freeShippingThreshold.isGreaterThan(orderAmount);
        Money fee = freeShipping ? Money.zero(currency()) : baseFee.plus(rate.fee());

        return new ShippingFeeQuote(
            ShippingScope.INTERNATIONAL, baseFee, freeShippingThreshold, freeShipping,
            Money.zero(currency()), null, zone.name(), rate.fee(), fee, version
        );
    }

    private static void requireCurrency(CurrencyCode expected, Money money, String label) {
        if (money.currency() != expected) {
            throw new IllegalArgumentException("해외 배송비 정책의 금액은 통화가 같아야 합니다: %s 는 %s, 정책은 %s".formatted(label, money.currency(), expected));
        }
    }
}
