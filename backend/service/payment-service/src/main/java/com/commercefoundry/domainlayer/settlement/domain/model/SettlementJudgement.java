package com.commercefoundry.domainlayer.settlement.domain.model;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 결제키 하나의 대사 판정.
 *
 * @param paymentKey PG 결제키
 * @param detections 찾은 불일치 — 종류 순서로 정렬돼 있다
 * @param evaluatedTypes 이번에 판정한 종류 — 찾은 것과 찾지 못한 것 모두. 판정했는데 찾지 못한 종류의 기존 미해결 불일치는 자동으로 닫는다.
 *     판정할 근거가 없던 종류(PG 승인 거래가 아직 수집되지 않음 · 유예 중)는 여기 없다 — 그 종류의 기존 불일치는 그대로 둔다
 * @param graceDeferred PG 누락으로 보이지만 유예(매출일 + 3일) 중이라 기록하지 않았는가
 */
public record SettlementJudgement(String paymentKey, List<SettlementDetection> detections, Set<SettlementMismatchType> evaluatedTypes, boolean graceDeferred) {

    public SettlementJudgement {
        Objects.requireNonNull(paymentKey, "paymentKey");
        detections = List.copyOf(detections);
        evaluatedTypes = evaluatedTypes.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(evaluatedTypes));
        for (SettlementDetection detection : detections) {
            if (!detection.paymentKey().equals(paymentKey) || !evaluatedTypes.contains(detection.type())) {
                throw new IllegalArgumentException("판정한 종류의 이 결제키 불일치만 담습니다");
            }
        }
    }

    /** 판정했고 불일치가 아니었던 종류 — 기존 미해결 불일치를 자동으로 닫을 대상이다. */
    public Set<SettlementMismatchType> clearedTypes() {
        EnumSet<SettlementMismatchType> cleared = EnumSet.noneOf(SettlementMismatchType.class);
        cleared.addAll(evaluatedTypes);
        detections.forEach(detection -> cleared.remove(detection.type()));
        return cleared;
    }

    /** 결제키 전체를 로그에 남기지 않는다. */
    @Override
    public String toString() {
        return "SettlementJudgement[paymentKey=%s, detections=%s, evaluatedTypes=%s, graceDeferred=%s]"
            .formatted(PgSettlementItem.maskPaymentKey(paymentKey), detections, evaluatedTypes, graceDeferred);
    }
}
