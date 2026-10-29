package com.commercefoundry.domainlayer.aiusage.application.info;

/**
 * 월 한도 임계를 이번에 처음 넘었다는 신호 — 기간당 한 번만 나온다({@code warned_at} · {@code exceeded_at} 조건부 UPDATE).
 * 예약 거절 때는 이 달 첫 거절이 아니어도 실린다({@code exceedCrossed=false}). 알림 · 로그 · 지표는 받은 쪽(PR-B3)이 낸다.
 *
 * @param period         월 ({@code YYYY-MM})
 * @param warnCrossed    경고 임계(한도 × 비율)를 이번에 처음 넘었다
 * @param exceedCrossed  한도를 이번에 처음 넘었다 — 이 달 첫 예약 거절 또는 정산 뒤 누계가 처음 한도 이상
 * @param reservedMicros 지금 예약 누계 (micro-USD, 확정 + 진행 중 예상치)
 * @param settledMicros  지금 확정 누계 (micro-USD)
 * @param limitMicros    월 한도 (micro-USD)
 */
public record AiBudgetAlert(String period, boolean warnCrossed, boolean exceedCrossed, long reservedMicros, long settledMicros, long limitMicros) {
}
