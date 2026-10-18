package com.commercefoundry.domainlayer.shippingfee.adapter.in.web.presenter;

import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.CUSTOMS_NOTICE;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.domesticPolicy;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.internationalPolicy;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.krw;
import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.web.dto.item.RemoteAreaItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.web.dto.item.ShippingZoneItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.web.dto.item.ShippingZoneRateItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.web.dto.response.InternationalShippingPolicyResponse;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.web.dto.response.ShippingPolicyResponse;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicy;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingScope;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ShippingFeeWebPresenterTest {

    private final ShippingFeeWebPresenter presenter = new ShippingFeeWebPresenter();

    @Test
    @DisplayName("금액은 {amount, currency}, 구간은 시작 우편번호 순 — 아이디 · 버전은 내리지 않는다")
    void toResponse() {
        ShippingPolicyResponse response = presenter.toResponse(domesticPolicy());

        assertThat(response.baseFee()).isEqualTo(MoneyItem.of(3_000, "KRW"));
        assertThat(response.freeShippingThreshold()).isEqualTo(MoneyItem.of(100_000, "KRW"));
        assertThat(response.remoteAreas()).containsExactly(
            RemoteAreaItem.builder().name("울릉").postalCodeFrom("40200").postalCodeTo("40240").surcharge(MoneyItem.of(5_000, "KRW")).build(),
            RemoteAreaItem.builder().name("제주").postalCodeFrom("63000").postalCodeTo("63644").surcharge(MoneyItem.of(3_000, "KRW")).build()
        );
    }

    @Test
    @DisplayName("무료배송 기준이 없으면 null, 구간이 없으면 빈 배열이다")
    void nullThresholdAndEmptyAreas() {
        ShippingPolicyResponse response = presenter.toResponse(new ShippingPolicy(1L, ShippingScope.DOMESTIC, krw(3_000), null, List.of(), 0L, null));

        assertThat(response.freeShippingThreshold()).isNull();
        assertThat(response.remoteAreas()).isEmpty();
    }

    @Test
    @DisplayName("해외 정책: 기본료 · 무료배송 기준 · 안내 문구 · 그룹(표시 순서) · 국가(코드 순) · 구간(상한 순) — 아이디 · 버전은 내리지 않는다")
    void internationalToResponse() {
        InternationalShippingPolicyResponse response = presenter.toResponse(internationalPolicy());

        assertThat(response.baseFee()).isEqualTo(MoneyItem.of(500, "USD"));
        assertThat(response.freeShippingThreshold()).isEqualTo(MoneyItem.of(20_000, "USD"));
        assertThat(response.customsNotice()).isEqualTo(CUSTOMS_NOTICE);
        assertThat(response.zones()).containsExactly(
            ShippingZoneItem.builder().name("아시아").countryCodes(List.of("JP", "SG")).rates(List.of(
                ShippingZoneRateItem.builder().maxWeightGrams(500).fee(MoneyItem.of(1_000, "USD")).build(),
                ShippingZoneRateItem.builder().maxWeightGrams(1_000).fee(MoneyItem.of(1_500, "USD")).build(),
                ShippingZoneRateItem.builder().maxWeightGrams(2_000).fee(MoneyItem.of(2_500, "USD")).build()
            )).build(),
            ShippingZoneItem.builder().name("북미").countryCodes(List.of("CA", "US")).rates(List.of(
                ShippingZoneRateItem.builder().maxWeightGrams(1_000).fee(MoneyItem.of(2_000, "USD")).build(),
                ShippingZoneRateItem.builder().maxWeightGrams(5_000).fee(MoneyItem.of(6_000, "USD")).build()
            )).build()
        );
    }
}
