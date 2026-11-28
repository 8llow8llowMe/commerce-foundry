package com.commercefoundry.domainlayer.settlement.application.port.in;

import com.commercefoundry.domainlayer.settlement.adapter.in.internal.dto.response.SettlementReconcileResponse;
import com.commercefoundry.domainlayer.settlement.application.command.SettlementReconcileCommand;

/** PG 매출일 하루의 정산 수집 · 대사 — operations 정기 작업(#219 PR2)이 부른다. */
public interface SettlementReconcileInternalUseCase {

    SettlementReconcileResponse reconcile(SettlementReconcileCommand command);
}
