package com.commercefoundry.domainlayer.shippingfee.adapter.in.internal.presenter;

import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.krw;
import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.usd;
import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.internal.dto.response.ShippingFeeQuoteInternalResponse;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingFeeQuote;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ShippingFeeInternalPresenterTest {

    private final ShippingFeeInternalPresenter presenter = new ShippingFeeInternalPresenter();

    @Test
    @DisplayName("받을 배송비는 shippingFee 로, 내역(기본 · 무료배송 · 도서산간)과 스냅숏용 기준 · 정책 버전은 그대로 내린다 — 국내는 국가 그룹 · 무게 요금이 null")
    void toResponse() {
        ShippingFeeQuoteInternalResponse response = presenter.toResponse(
            new ShippingFeeQuote(ShippingScope.DOMESTIC, krw(3_000), krw(100_000), true, krw(5_000), "울릉", null, null, krw(5_000), 3L));

        assertThat(response).isEqualTo(ShippingFeeQuoteInternalResponse.builder()
            .shippingFee(MoneyItem.of(5_000, "KRW"))
            .baseFee(MoneyItem.of(3_000, "KRW"))
            .freeShippingApplied(true)
            .remoteAreaSurcharge(MoneyItem.of(5_000, "KRW"))
            .remoteAreaName("울릉")
            .freeShippingThreshold(MoneyItem.of(100_000, "KRW"))
            .shippingPolicyVersion(3L)
            .scope("DOMESTIC")
            .build());
    }

    @Test
    @DisplayName("도서산간이 아니면 추가 배송비 0 · 구간 이름 null, 무료배송 기준이 없으면 null 이다")
    void mainlandWithoutThreshold() {
        ShippingFeeQuoteInternalResponse response = presenter.toResponse(
            new ShippingFeeQuote(ShippingScope.DOMESTIC, krw(3_000), null, false, krw(0), null, null, null, krw(3_000), 0L));

        assertThat(response.remoteAreaSurcharge()).isEqualTo(MoneyItem.of(0, "KRW"));
        assertThat(response.remoteAreaName()).isNull();
        assertThat(response.shippingFee()).isEqualTo(MoneyItem.of(3_000, "KRW"));
        assertThat(response.freeShippingThreshold()).isNull();
        assertThat(response.shippingPolicyVersion()).isZero();
    }

    @Test
    @DisplayName("해외 견적은 범위 INTERNATIONAL · 국가 그룹 이름 · 무게 요금을 내리고 도서산간은 0 · null 이다")
    void international() {
        ShippingFeeQuoteInternalResponse response = presenter.toResponse(
            new ShippingFeeQuote(ShippingScope.INTERNATIONAL, usd(500), usd(20_000), false, usd(0), null, "아시아", usd(1_500), usd(2_000), 2L));

        assertThat(response.scope()).isEqualTo("INTERNATIONAL");
        assertThat(response.shippingZoneName()).isEqualTo("아시아");
        assertThat(response.weightFee()).isEqualTo(MoneyItem.of(1_500, "USD"));
        assertThat(response.shippingFee()).isEqualTo(MoneyItem.of(2_000, "USD"));
        assertThat(response.remoteAreaSurcharge()).isEqualTo(MoneyItem.of(0, "USD"));
        assertThat(response.remoteAreaName()).isNull();
    }
}
