package com.commercefoundry.domainlayer.payment.application.port.out.query;

/**
 * 결제키 조회로 본 PG 잔액이 로컬 원장이 기대하는 잔액과 같은지 ({@code payment-guide.md} §8, #691).
 *
 * <p>{@link State#MATCHED} 만 "원장에 없는 환불(콘솔 취소 등)이 PG 에 없다" 의 근거다. 나머지 셋은 무환불을 확정하지 못한다.
 */
public record PgRetentionQueryResult(State state) {

    public enum State {
        /** 신원 · 총액 · 취소 이력 · 잔액 검증을 통과했고, 잔액과 결제 상태가 기대와 같다. */
        MATCHED,
        /** 응답은 계약을 지켰고 결제 상태도 잔액과 맞는데, 잔액이 기대와 다르다 — 원장에 없는 환불이 PG 에 있다. */
        MISMATCHED,
        /**
         * 판정 근거가 없다: 답을 받지 못함(4xx · 5xx · 429 · 타임아웃 · 서킷 · 연결 실패), 응답 계약 위반, 결제 상태가 잔액과 맞지 않음,
         * 호출자 입력 오류(기대 잔액 통화가 다르거나 결제액보다 큼). 다시 물으면 답이 바뀔 수 있다.
         */
        UNKNOWN,
        /**
         * 대조 대상이 아닌 결제라 PG 를 부르지 않았다: 가상계좌(환불 계좌 송금) · KRW 외 통화(PayPal 비동기 환불) · 결제키 없음.
         * 다시 물어도 답이 바뀌지 않는다 — 자동으로 무환불을 확정할 수 없는 결제다.
         */
        UNSUPPORTED
    }

    public static PgRetentionQueryResult of(State state) {
        return new PgRetentionQueryResult(state);
    }
}
