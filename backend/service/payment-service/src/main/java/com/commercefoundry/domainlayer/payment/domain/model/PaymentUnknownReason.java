package com.commercefoundry.domainlayer.payment.domain.model;

/**
 * 승인 결과를 확정하지 못하고 {@link PaymentStatus#UNKNOWN} 으로 둔 이유 ({@code payment-guide.md} §4). 재조회 스케줄러와 어드민이 원인을 가른다.
 * 승인 요청을 보내지 않은 것이 확실하면 UNKNOWN 이 아니라 {@link Payment#revertToReady()} 다.
 */
public enum PaymentUnknownReason {

    /** 승인 요청의 read timeout — 보냈지만 응답을 받지 못했다 */
    TIMEOUT,
    /** PG 5xx — PG 쪽에서 처리됐는지 모른다 */
    SERVER_ERROR,
    /** PG 가 이미 처리된 결제라고 답했다 — 앞선 요청이 승인됐을 수 있다 */
    ALREADY_PROCESSED,
    /** PG 응답이 계약과 다르다(필수 칸 누락 · 해석 불가) — 승인 여부를 응답으로 판단할 수 없다 */
    CONTRACT,
    /** PG 가 승인했다고 답했지만 금액 · 결제키가 이 결제 시도와 다르다({@link PaymentApprovalMismatchException}) — 승인으로 기록하지 않고 확인한다 */
    APPROVAL_MISMATCH,
    /** 승인 요청 중(IN_PROGRESS)으로 너무 오래 남은 행 — 승인 기록 단계가 죽었을 수 있어 복구 스케줄러가 재조회 대상으로 돌린다 */
    STALE_IN_PROGRESS,
    /** 재조회로 확정할 수 없어 사람이 확인한다 — 재조회 스케줄러는 건너뛰고 알림만 한다 ({@link Payment#flagForManualReview}) */
    MANUAL_REVIEW
}
