package com.commercefoundry.domainlayer.settlement.adapter.in.internal.presenter;

import com.commercefoundry.common.dto.item.MoneyItem;
import com.commercefoundry.domainlayer.settlement.adapter.in.internal.dto.response.SettlementReconcileResponse;
import com.commercefoundry.domainlayer.settlement.application.info.SettlementReconcileInfo;
import org.springframework.stereotype.Component;

@Component
public class SettlementReconcileInternalPresenter {

    /** 거래 금액 합은 취소가 많은 날 음수일 수 있어 {@code Money}(음수 불가)를 거치지 않고 정수 · 통화로 바로 싣는다. */
    public SettlementReconcileResponse toResponse(SettlementReconcileInfo info) {
        return new SettlementReconcileResponse(info.soldDate(), info.itemCount(), MoneyItem.of(info.pgAmountTotal(), info.currency().name()),
            info.mismatchCount(), info.newMismatchCount(), info.autoClearedCount(), info.graceDeferredCount(), info.status().toMetadata());
    }
}
