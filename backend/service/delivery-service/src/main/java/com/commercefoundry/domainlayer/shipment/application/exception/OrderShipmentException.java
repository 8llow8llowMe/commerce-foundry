package com.commercefoundry.domainlayer.shipment.application.exception;

import lombok.Getter;

/**
 * order-service 출고 반영이 200 이 아니었다 ({@code OrderShipmentCommandPort}). 어떤 실패인지({@link Failure})가 대기 송장 행을 지울지 남길지를 정한다
 * ({@code order-flow-guide.md} §4 실패 표). 원격 응답 모양 · Feign 예외는 이 밖으로 나오지 않는다 — 원인 예외는 디버깅용으로만 잇는다.
 */
@Getter
public class OrderShipmentException extends RuntimeException {

    /** 상태 이름을 모를 때 {@code ORDER_NOT_SHIPPABLE} 메시지의 {@code %s} 자리에 넣는 말 — "현재 주문 상태라 출고할 수 없습니다." */
    static final String UNKNOWN_STATUS_NAME = "현재 주문";

    private final Failure failure;
    /** {@link Failure#NOT_SHIPPABLE} 일 때 order 가 알려 준 현재 상태 이름(검증한 짧은 한글). 그 밖이거나 읽지 못하면 null. */
    private final String orderStatusName;

    public OrderShipmentException(Failure failure, String orderStatusName, Throwable cause) {
        super("order shipment failed: " + failure, cause);
        this.failure = failure;
        this.orderStatusName = orderStatusName;
    }

    public OrderShipmentException(Failure failure, Throwable cause) {
        this(failure, null, cause);
    }

    /** 건별 결과로 내보낼 도메인 오류. */
    public ShipmentException toShipmentException() {
        return switch (failure) {
            case NOT_FOUND -> new ShipmentException(ShipmentErrorCode.ORDER_NOT_FOUND);
            case NOT_SHIPPABLE -> new ShipmentException(ShipmentErrorCode.ORDER_NOT_SHIPPABLE, orderStatusName == null ? UNKNOWN_STATUS_NAME : orderStatusName);
            case INVALID_TIME -> new ShipmentException(ShipmentErrorCode.ORDER_SHIPPED_AT_REJECTED);
            case CONFLICT_RETRY -> new ShipmentException(ShipmentErrorCode.ORDER_CONCURRENTLY_CHANGED);
            case UNKNOWN -> new ShipmentException(ShipmentErrorCode.ORDER_SERVICE_UNAVAILABLE);
        };
    }

    public enum Failure {

        /** 404 {@code ORDER_001}. */
        NOT_FOUND(true),
        /** 409 {@code ORDER_020} — 출고할 수 없는 상태. */
        NOT_SHIPPABLE(true),
        /** 400 {@code ORDER_159} · {@code 160} · {@code 161} — 보낸 시각이 범위 밖. 같은 값으로 다시 보내도 같다. */
        INVALID_TIME(true),
        /** 409 {@code ORDER_025} — 버전 충돌. 다시 보내면 지금 상태로 판정받는다. */
        CONFLICT_RETRY(false),
        /** 타임아웃 · 5xx · 서킷 오픈 · 모르는 4xx · 계약 위반 응답 — 반영됐는지 모른다. */
        UNKNOWN(false);

        private final boolean discardsPending;

        Failure(boolean discardsPending) {
            this.discardsPending = discardsPending;
        }

        /** 주문에 반영되지 않은 것이 확실해 대기 송장 행을 지우는가. false 면 행을 남긴다 — 같은 등록 요청 재시도로 수렴한다. */
        public boolean discardsPending() {
            return discardsPending;
        }
    }
}
