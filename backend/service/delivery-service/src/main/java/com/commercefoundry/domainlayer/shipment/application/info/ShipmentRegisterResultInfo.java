package com.commercefoundry.domainlayer.shipment.application.info;

import java.util.Objects;

/**
 * 송장 등록 한 건의 결과 (#18).
 *
 * @param shipmentId 남아 있는 송장 아이디 — 등록 · 이미 등록 · 행을 남긴 실패(버전 충돌 · 결과 모름)면 있고, 행을 만들지 못했거나 지운 실패면 null
 * @param errorCode 실패 코드({@code DELIVERY_*}). 성공이면 null
 * @param message 실패 메시지. 성공이면 null
 */
public record ShipmentRegisterResultInfo(long orderId, Long shipmentId, Outcome outcome, String errorCode, String message) {

    public ShipmentRegisterResultInfo {
        Objects.requireNonNull(outcome, "outcome");
        if ((outcome == Outcome.FAILED) != (errorCode != null)) {
            throw new IllegalArgumentException("실패만 오류 코드를 갖는다: outcome=" + outcome);
        }
    }

    public static ShipmentRegisterResultInfo registered(long orderId, long shipmentId) {
        return new ShipmentRegisterResultInfo(orderId, shipmentId, Outcome.REGISTERED, null, null);
    }

    public static ShipmentRegisterResultInfo alreadyRegistered(long orderId, long shipmentId) {
        return new ShipmentRegisterResultInfo(orderId, shipmentId, Outcome.ALREADY_REGISTERED, null, null);
    }

    public static ShipmentRegisterResultInfo failed(long orderId, Long shipmentId, String errorCode, String message) {
        return new ShipmentRegisterResultInfo(orderId, shipmentId, Outcome.FAILED, Objects.requireNonNull(errorCode, "errorCode"), message);
    }

    public boolean succeeded() {
        return outcome != Outcome.FAILED;
    }

    public enum Outcome {

        /** 이번 요청으로 송장을 만들었거나(또는 반영 전 송장을 재시도로) 주문에 반영했다. 반영 완료 저장이 충돌해 지금 행에 맞췄고 그 행이 같은 송장이어도 이것이다. */
        REGISTERED,
        /** 같은 택배사 · 송장 번호로 이미 주문에 반영된 송장이 있다 — 아무것도 바꾸지 않았다. */
        ALREADY_REGISTERED,
        /** 등록하지 못했다 — {@code errorCode} · {@code message} 가 이유다. */
        FAILED
    }
}
