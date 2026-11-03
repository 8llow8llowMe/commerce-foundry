package com.commercefoundry.domainlayer.payment.application.exception;

import java.util.Objects;

/**
 * 주문 결제 회신을 지금은 반영하지 못했다 — 컨슈머 재시도(지수 백오프) 대상이고, 상한을 넘으면 DLT 로 간다 (#308 PR-C, {@code payment-guide.md} §6).
 * 메시지에는 분류만 싣는다. 원격 응답 본문 · 아이디 · 금액은 싣지 않는다.
 */
public final class OrderPaymentReplyRetryException extends RuntimeException {

    private final Reason reason;

    public OrderPaymentReplyRetryException(Reason reason) {
        super("Order payment reply not applied yet: " + Objects.requireNonNull(reason, "reason").name());
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }

    public enum Reason {
        /** 원천(order 저장 결과)이 아직 없다 — 404 {@code ORDER_028}. */
        SOURCE_NOT_READY,
        /** 원천 조회 장애 — 연결 · 타임아웃 · 5xx · 서킷 오픈. */
        SOURCE_UNAVAILABLE,
        /** 서비스 토큰 · 허용 호출자 설정 — 키 교체 시간대에 바로 DLT 로 보내지 않는다. */
        SOURCE_UNAUTHORIZED
    }
}
