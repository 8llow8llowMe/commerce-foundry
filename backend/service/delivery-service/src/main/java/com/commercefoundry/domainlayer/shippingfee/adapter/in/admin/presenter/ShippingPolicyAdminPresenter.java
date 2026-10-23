package com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.presenter;

import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.item.RemoteAreaAdminItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.item.ShippingPolicyHistoryAdminItem;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.admin.dto.response.ShippingPolicyAdminResponse;
import com.commercefoundry.domainlayer.shippingfee.adapter.in.support.MoneyItems;
import com.commercefoundry.domainlayer.shippingfee.domain.model.RemoteArea;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicy;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicyHistory;
import com.commercefoundry.domainlayer.shippingfee.domain.model.ShippingPolicyHistory.Snapshot;
import com.commercefoundry.persistence.dto.PageResponse;
import java.util.Objects;
import org.springframework.data.domain.Page;
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

    /** 페이지 정보(전체 건수 포함)는 그대로 두고 항목만 바꾼다. 저장된 이력만 받는다 — 교체 시각이 없으면 저장 흐름의 오류다. */
    public PageResponse<ShippingPolicyHistoryAdminItem> toHistoryPageResponse(Page<ShippingPolicyHistory> histories) {
        return PageResponse.of(histories).map(this::toHistoryItem);
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

    private ShippingPolicyHistoryAdminItem toHistoryItem(ShippingPolicyHistory history) {
        return ShippingPolicyHistoryAdminItem.builder()
            .historyId(String.valueOf(history.id()))
            .adminMemberId(String.valueOf(history.adminMemberId()))
            .changedAt(Objects.requireNonNull(history.changedAt(), "changedAt"))
            .before(history.before() == null ? null : toSnapshotItem(history.before()))
            .after(toSnapshotItem(history.after()))
            .build();
    }

    private ShippingPolicyHistoryAdminItem.Snapshot toSnapshotItem(Snapshot snapshot) {
        return ShippingPolicyHistoryAdminItem.Snapshot.builder()
            .baseFee(MoneyItems.of(snapshot.baseFee()))
            .freeShippingThreshold(MoneyItems.of(snapshot.freeShippingThreshold()))
            .remoteAreas(snapshot.remoteAreas().stream().map(this::toHistoryAreaItem).toList())
            .version(snapshot.version())
            .build();
    }

    private ShippingPolicyHistoryAdminItem.RemoteArea toHistoryAreaItem(RemoteArea remoteArea) {
        return ShippingPolicyHistoryAdminItem.RemoteArea.builder()
            .name(remoteArea.name())
            .postalCodeFrom(remoteArea.postalCodeFrom().value())
            .postalCodeTo(remoteArea.postalCodeTo().value())
            .surcharge(MoneyItems.of(remoteArea.surcharge()))
            .build();
    }
}
