package com.commercefoundry.domainlayer.product.application.model;

/**
 * 주문 이벤트로 선점을 풀어 본 결과 ({@code order-flow-guide.md} §3-5). 예외가 아니라 값으로 돌려준다 — 정상 · 무시 · 계약 위반의 처리는
 * 이벤트 진입(리스너)이 정한다.
 */
public enum StockReleaseOutcome {

    /** RESERVED 를 RELEASED 로 바꾸고 차감했던 재고를 되돌렸다 */
    RELEASED,
    /** 이미 RELEASED 였다 (멱등 — 만료 스윕 · internal 해제가 먼저 풀었거나 같은 이벤트가 다시 왔다). 아무것도 바꾸지 않았다 */
    ALREADY_RELEASED,
    /** 이미 확정된 선점이다 — 결제가 먼저 끝났다. 풀지 않는다 */
    CONFIRMED,
    /** 선점이 없다 */
    NOT_FOUND,
    /** 선점의 주문 아이디가 이벤트의 주문 아이디와 다르다 */
    ORDER_MISMATCH
}
