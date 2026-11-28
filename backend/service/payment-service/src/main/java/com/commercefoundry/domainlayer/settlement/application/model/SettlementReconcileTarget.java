package com.commercefoundry.domainlayer.settlement.application.model;

import com.commercefoundry.domainlayer.settlement.domain.model.SettlementDay;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * 대사 트랜잭션 안에서 잠근 매출일과 원장 조회 범위 — Facade 가 이 값으로 payment 원장을 읽어 대사 Processor 에 넘긴다.
 *
 * @param day 잠근 매출일 요약 (COLLECTED)
 * @param paymentKeys 그 매출일에 수집된 KRW 거래의 결제키
 * @param approvedFrom 매출일 KST 00:00 (포함)
 * @param approvedTo 다음 날 KST 00:00 (제외)
 */
public record SettlementReconcileTarget(SettlementDay day, Set<String> paymentKeys, Instant approvedFrom, Instant approvedTo) {

    public SettlementReconcileTarget {
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(approvedFrom, "approvedFrom");
        Objects.requireNonNull(approvedTo, "approvedTo");
        paymentKeys = Set.copyOf(paymentKeys);
    }

    /** 결제키를 로그에 남기지 않는다 — 개수만. */
    @Override
    public String toString() {
        return "SettlementReconcileTarget[soldDate=%s, paymentKeyCount=%d]".formatted(day.soldDate(), paymentKeys.size());
    }
}
