package com.commercefoundry.domainlayer.payment.application.port.out;

/** 주문 결제 회신 처리의 지표 (#308 PR-C). 태그 없이 센다 — 아이디 · 사유 원문을 싣지 않는다. */
public interface OrderPaymentReplyMetricsPort {

    /** 회신 이벤트가 order 원천(최초 반영 결과)과 달라 DLT 로 보냈다 — 위조 · 버그 신호다. */
    void recordSourceMismatch();
}
