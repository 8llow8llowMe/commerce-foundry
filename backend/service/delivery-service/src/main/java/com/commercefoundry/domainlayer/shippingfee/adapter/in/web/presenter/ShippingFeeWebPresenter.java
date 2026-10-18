package com.commercefoundry.domainlayer.shippingfee.adapter.in.web.presenter;

import com.commercefoundry.domainlayer.shippingfee.adapter.in.support.MoneyItems;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.web.dto.item.RemoteAreaItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.web.dto.item.ShippingZoneItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.web.dto.item.ShippingZoneRateItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.web.dto.response.InternationalShippingPolicyResponse;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.web.dto.response.ShippingPolicyResponse;
import com.commercefoundry.domainlayer.shippingfee.domain.model.InternationalShippingPolicy;
import com.commercefoundry.domainlayer.shippingfee.domain.model.RemoteArea;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicy;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingZone;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingZoneCountry;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingZoneRate;
import org.springframework.stereotype.Component;

/**
 * 고객 응답에는 아이디 · 버전 · 수정 시각을 내리지 않는다 — 배송 안내에 쓰는 금액과 구간만 낸다.
 */
@Component
public class ShippingFeeWebPresenter {

    public ShippingPolicyResponse toResponse(ShippingPolicy policy) {
        return ShippingPolicyResponse.builder()
            .baseFee(MoneyItems.of(policy.baseFee()))
            .freeShippingThreshold(MoneyItems.of(policy.freeShippingThreshold()))
            .remoteAreas(policy.remoteAreas().stream().map(this::toItem).toList())
            .build();
    }

    public InternationalShippingPolicyResponse toResponse(InternationalShippingPolicy policy) {
        return InternationalShippingPolicyResponse.builder()
            .baseFee(MoneyItems.of(policy.baseFee()))
            .freeShippingThreshold(MoneyItems.of(policy.freeShippingThreshold()))
            .customsNotice(policy.customsNotice())
            .zones(policy.zones().stream().map(this::toItem).toList())
            .build();
    }

    private RemoteAreaItem toItem(RemoteArea remoteArea) {
        return RemoteAreaItem.builder()
            .name(remoteArea.name())
            .postalCodeFrom(remoteArea.postalCodeFrom().value())
            .postalCodeTo(remoteArea.postalCodeTo().value())
            .surcharge(MoneyItems.of(remoteArea.surcharge()))
            .build();
    }

    private ShippingZoneItem toItem(ShippingZone zone) {
        return ShippingZoneItem.builder()
            .name(zone.name())
            .countryCodes(zone.countries().stream().map(ShippingZoneCountry::countryCode).toList())
            .rates(zone.rates().stream().map(this::toItem).toList())
            .build();
    }

    private ShippingZoneRateItem toItem(ShippingZoneRate rate) {
        return ShippingZoneRateItem.builder().maxWeightGrams(rate.maxWeightGrams()).fee(MoneyItems.of(rate.fee())).build();
    }
}
