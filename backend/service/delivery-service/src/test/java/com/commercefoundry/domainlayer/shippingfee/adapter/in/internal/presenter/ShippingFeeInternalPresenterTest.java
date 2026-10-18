package com.commercefoundry.domainlayer.shippingfee.adapter.in.internal.presenter;

import static com.commercefoundry.domainlayer.shippingfee.support.ShippingFeeFixtures.krw;
import static org.assertj.core.api.Assertions.assertThat;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.internal.dto.response.ShippingFeeQuoteInternalResponse;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingFeeQuote;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ShippingFeeInternalPresenterTest {

    private final ShippingFeeInternalPresenter presenter = new ShippingFeeInternalPresenter();

    @Test
    @DisplayName("받을 배송비는 shippingFee 로, 내역(기본 · 무료배송 · 도서산간)과 스냅숏용 기준 · 정책 버전은 그대로 내린다")
    void toResponse() {
        ShippingFeeQuoteInternalResponse response = presenter.toResponse(
            new ShippingFeeQuote(krw(3_000), krw(100_000), true, krw(5_000), "울릉", krw(5_000), 3L));

        assertThat(response).isEqualTo(ShippingFeeQuoteInternalResponse.builder()
            .shippingFee(MoneyItem.of(5_000, "KRW"))
            .baseFee(MoneyItem.of(3_000, "KRW"))
            .freeShippingApplied(true)
            .remoteAreaSurcharge(MoneyItem.of(5_000, "KRW"))
            .remoteAreaName("울릉")
            .freeShippingThreshold(MoneyItem.of(100_000, "KRW"))
            .shippingPolicyVersion(3L)
            .build());
    }

    @Test
    @DisplayName("도서산간이 아니면 추가 배송비 0 · 구간 이름 null, 무료배송 기준이 없으면 null 이다")
    void mainlandWithoutThreshold() {
        ShippingFeeQuoteInternalResponse response = presenter.toResponse(new ShippingFeeQuote(krw(3_000), null, false, krw(0), null, krw(3_000), 0L));

        assertThat(response.remoteAreaSurcharge()).isEqualTo(MoneyItem.of(0, "KRW"));
        assertThat(response.remoteAreaName()).isNull();
        assertThat(response.shippingFee()).isEqualTo(MoneyItem.of(3_000, "KRW"));
        assertThat(response.freeShippingThreshold()).isNull();
        assertThat(response.shippingPolicyVersion()).isZero();
    }
}
