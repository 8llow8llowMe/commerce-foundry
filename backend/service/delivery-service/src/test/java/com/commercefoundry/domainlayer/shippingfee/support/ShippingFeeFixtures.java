package com.commercefoundry.domainlayer.shippingfee.support;

import static com.commercefoundry.shared.commerce.CurrencyCode.KRW;

import com.commercefoundry.domainlayer.shippingfee.domain.model.PostalCode;
import com.commercefoundry.domainlayer.shippingfee.domain.model.RemoteArea;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicy;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingScope;
import com.commercefoundry.shared.commerce.Money;
import java.time.Instant;
import java.util.List;

/**
 * 테스트 정책 — 기본 3,000원, 100,000원 이상 무료, 제주(63000~63644) 3,000원 · 울릉(40200~40240) 5,000원.
 * 우편번호 구간은 공개된 범위를 쓴다. 실제 고객사 정책이 아니다.
 */
public final class ShippingFeeFixtures {

    public static final long POLICY_ID = 7001L;
    public static final Instant UPDATED_AT = Instant.parse("2026-10-18T03:00:00Z");

    private ShippingFeeFixtures() {
    }

    public static Money krw(long amount) {
        return Money.of(amount, KRW);
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
}
