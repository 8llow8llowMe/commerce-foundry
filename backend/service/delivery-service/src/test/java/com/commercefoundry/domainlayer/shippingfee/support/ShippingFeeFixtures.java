package com.commercefoundry.domainlayer.shippingfee.support;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;
import static com.commercefoundry.shared.commerce.CurrencyCode.USD;

import com.commercefoundry.domainlayer.shippingfee.domain.model.InternationalShippingPolicy;
import com.commercefoundry.domainlayer.shippingfee.domain.model.PostalCode;
import com.commercefoundry.domainlayer.shippingfee.domain.model.RemoteArea;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicy;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingZone;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingZoneCountry;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingZoneRate;
import com.commercefoundry.shared.commerce.Money;
import com.commercefoundry.shared.commerce.ShippingScope;
import java.time.Instant;
import java.util.List;

/**
 * 테스트 정책 — 실제 고객사 정책이 아니다.
 *
 * <ul>
 *   <li>국내: 기본 3,000원, 100,000원 이상 무료, 제주(63000~63644) 3,000원 · 울릉(40200~40240) 5,000원. 우편번호 구간은 공개된 범위를 쓴다.</li>
 *   <li>해외(USD): 기본료 $5.00, $200.00 이상 무료, 아시아(JP · SG) 500g $10 · 1,000g $15 · 2,000g $25,
 *       북미(CA · US) 1,000g $20 · 5,000g $60. 안내 문구 있음.</li>
 * </ul>
 */
public final class ShippingFeeFixtures {

    public static final long POLICY_ID = 7001L;
    public static final long INTERNATIONAL_POLICY_ID = 8001L;
    public static final Instant UPDATED_AT = Instant.parse("2026-10-18T03:00:00Z");
    public static final String CUSTOMS_NOTICE = "수입 관세 · 부가세는 받는 분이 부담합니다.";

    private ShippingFeeFixtures() {
    }

    public static Money krw(long amount) {
        return Money.of(amount, KRW);
    }

    /** 센트 단위. */
    public static Money usd(long cents) {
        return Money.of(cents, USD);
    }

    public static PostalCode postalCode(String value) {
        return new PostalCode(value);
    }

    public static RemoteArea remoteArea(long id, String name, String from, String to, Money surcharge) {
        return new RemoteArea(id, name, postalCode(from), postalCode(to), surcharge);
    }

    public static RemoteArea jeju() {
        return remoteArea(7101L, "제주", "63000", "63644", krw(3_000));
    }

    public static RemoteArea ulleung() {
        return remoteArea(7102L, "울릉", "40200", "40240", krw(5_000));
    }

    /** 저장된 정책 (버전 3). */
    public static ShippingPolicy domesticPolicy() {
        return new ShippingPolicy(POLICY_ID, ShippingScope.DOMESTIC, krw(3_000), krw(100_000), List.of(jeju(), ulleung()), 3L, UPDATED_AT);
    }

    public static ShippingZone asia() {
        return new ShippingZone(
            8101L, "아시아",
            List.of(new ShippingZoneCountry(8111L, "JP"), new ShippingZoneCountry(8112L, "SG")),
            List.of(new ShippingZoneRate(8121L, 500, usd(1_000)), new ShippingZoneRate(8122L, 1_000, usd(1_500)), new ShippingZoneRate(8123L, 2_000, usd(2_500)))
        );
    }

    public static ShippingZone northAmerica() {
        return new ShippingZone(
            8201L, "북미",
            List.of(new ShippingZoneCountry(8211L, "CA"), new ShippingZoneCountry(8212L, "US")),
            List.of(new ShippingZoneRate(8221L, 1_000, usd(2_000)), new ShippingZoneRate(8222L, 5_000, usd(6_000)))
        );
    }

    /** 저장된 해외 정책 (버전 2). */
    public static InternationalShippingPolicy internationalPolicy() {
        return new InternationalShippingPolicy(
            INTERNATIONAL_POLICY_ID, usd(500), usd(20_000), CUSTOMS_NOTICE, List.of(asia(), northAmerica()), 2L, UPDATED_AT
        );
    }
}
