package com.commercefoundry.domainlayer.aiusage.application.info;

import com.commercefoundry.domainlayer.aiusage.domain.model.AiUsageStatus;

/**
 * 정산 결과.
 *
 * @param applied    이번 호출이 정산을 반영했다. 이미 닫힌 호출(중복 정산 · 회수가 먼저 닫음 · 보존 정리로 없음)이면 {@code false} 이고 아무것도 바꾸지 않았다
 * @param status     닫힌 상태. 반영하지 않았으면 {@code null}
 * @param costMicros 확정 비용 (micro-USD). 반영하지 않았으면 0
 * @param alert      이번 정산으로 처음 넘은 임계. 없으면 {@code null}
 */
public record AiUsageSettleResult(boolean applied, AiUsageStatus status, long costMicros, AiBudgetAlert alert) {

    public static AiUsageSettleResult notApplied() {
        return new AiUsageSettleResult(false, null, 0, null);
    }
}
