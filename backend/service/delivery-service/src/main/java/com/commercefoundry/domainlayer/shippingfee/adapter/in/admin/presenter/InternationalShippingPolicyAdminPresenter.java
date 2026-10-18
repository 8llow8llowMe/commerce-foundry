package com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.item.ShippingZoneAdminItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.item.ShippingZoneRateAdminItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.response.InternationalShippingPolicyAdminResponse;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.support.MoneyItems;
import com.commercefoundry.domainlayer.shippingfee.domain.model.InternationalShippingPolicy;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingZone;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingZoneCountry;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingZoneRate;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class InternationalShippingPolicyAdminPresenter {

    /** 저장된 정책만 받는다 — 버전 · 수정 시각이 없으면 저장 흐름의 오류다. */
    public InternationalShippingPolicyAdminResponse toResponse(InternationalShippingPolicy policy) {
        return InternationalShippingPolicyAdminResponse.builder()
            .shippingPolicyId(String.valueOf(policy.id()))
            .baseFee(MoneyItems.of(policy.baseFee()))
            .freeShippingThreshold(MoneyItems.of(policy.freeShippingThreshold()))
            .customsNotice(policy.customsNotice())
            .zones(policy.zones().stream().map(this::toItem).toList())
            .version(Objects.requireNonNull(policy.version(), "version"))
            .updatedAt(Objects.requireNonNull(policy.updatedAt(), "updatedAt"))
            .build();
    }

    private ShippingZoneAdminItem toItem(ShippingZone zone) {
        return ShippingZoneAdminItem.builder()
            .shippingZoneId(String.valueOf(zone.id()))
            .name(zone.name())
            .countryCodes(zone.countries().stream().map(ShippingZoneCountry::countryCode).toList())
            .rates(zone.rates().stream().map(this::toItem).toList())
            .build();
    }

    private ShippingZoneRateAdminItem toItem(ShippingZoneRate rate) {
        return ShippingZoneRateAdminItem.builder()
            .shippingZoneRateId(String.valueOf(rate.id()))
            .maxWeightGrams(rate.maxWeightGrams())
            .fee(MoneyItems.of(rate.fee()))
            .build();
    }
}
