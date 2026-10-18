package com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.item.RemoteAreaAdminItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.response.ShippingPolicyAdminResponse;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.support.MoneyItems;
import com.commercefoundry.domainlayer.shippingfee.domain.model.RemoteArea;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicy;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class ShippingPolicyAdminPresenter {

    /** 저장된 정책만 받는다 — 버전 · 수정 시각이 없으면 저장 흐름의 오류다. */
    public ShippingPolicyAdminResponse toResponse(ShippingPolicy policy) {
        return ShippingPolicyAdminResponse.builder()
            .shippingPolicyId(String.valueOf(policy.id()))
            .baseFee(MoneyItems.of(policy.baseFee()))
            .freeShippingThreshold(MoneyItems.of(policy.freeShippingThreshold()))
            .remoteAreas(policy.remoteAreas().stream().map(this::toItem).toList())
            .version(Objects.requireNonNull(policy.version(), "version"))
            .updatedAt(Objects.requireNonNull(policy.updatedAt(), "updatedAt"))
            .build();
    }

    private RemoteAreaAdminItem toItem(RemoteArea remoteArea) {
        return RemoteAreaAdminItem.builder()
            .remoteAreaId(String.valueOf(remoteArea.id()))
            .name(remoteArea.name())
            .postalCodeFrom(remoteArea.postalCodeFrom().value())
            .postalCodeTo(remoteArea.postalCodeTo().value())
            .surcharge(MoneyItems.of(remoteArea.surcharge()))
            .build();
    }
}
