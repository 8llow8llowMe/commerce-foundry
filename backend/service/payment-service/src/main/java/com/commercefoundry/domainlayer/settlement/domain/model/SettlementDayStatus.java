package com.commercefoundry.domainlayer.settlement.domain.model;

import com.commercefoundry.common.dto.metadata.CodeNameDescribable;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 매출일 하루의 정산 상태. 전이는 {@link SettlementDay} 의 도메인 메서드로만 일어난다. */
@Getter
@RequiredArgsConstructor
public enum SettlementDayStatus implements CodeNameDescribable {

    COLLECTED("수집 완료", "PG 정산 내역을 수집했고 대사를 기다리고 있습니다."),
    RECONCILED("대사 완료", "PG 정산 내역과 결제 기록을 대조했습니다."),
    FAILED("수집 실패", "마지막 PG 정산 내역 수집이 실패했습니다. 다시 실행해야 합니다.");

    private final String displayName;
    private final String description;
}
