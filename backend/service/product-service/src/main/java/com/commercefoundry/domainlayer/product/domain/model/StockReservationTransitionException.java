package com.commercefoundry.domainlayer.product.domain.model;

import java.util.Objects;
import lombok.Getter;

/**
 * 재고 선점의 허용 전이 표 밖 요청 — 확정된 선점의 해제, 해제된 선점의 확정 ({@link StockReservation}).
 *
 * <p>domain 소유 예외다. domain 은 application 의 오류 코드를 모르므로 현재 상태와 시도한 동작만 싣고, 업무 오류 코드(409)는
 * 이 예외를 받은 Processor 가 붙인다 ({@code coding-conventions.md} §8-1). Processor 가 바꾸지 않고 흘리면 500 이 되므로 반드시 잡는다.
 */
@Getter
public class StockReservationTransitionException extends RuntimeException {

    private final StockReservationStatus currentStatus;
    private final Action attemptedAction;

    public StockReservationTransitionException(StockReservationStatus currentStatus, Action attemptedAction) {
        super("허용되지 않은 재고 선점 전이입니다. status=" + currentStatus + ", action=" + attemptedAction);
        this.currentStatus = Objects.requireNonNull(currentStatus, "currentStatus");
        this.attemptedAction = Objects.requireNonNull(attemptedAction, "attemptedAction");
    }

    /** 시도한 전이. */
    public enum Action {
        CONFIRM,
        RELEASE
    }
}
