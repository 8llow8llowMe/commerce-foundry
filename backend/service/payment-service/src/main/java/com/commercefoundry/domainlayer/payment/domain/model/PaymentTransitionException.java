package com.commercefoundry.domainlayer.payment.domain.model;

import java.util.Objects;
import lombok.Getter;

/**
 * 결제의 허용 전이 표 밖 요청 ({@link Payment}). 예: DONE 결제를 다시 승인, FAILED 결제를 승인 시작, 승인을 시도한 READY 를 조회 없이 중단.
 *
 * <p>domain 소유 예외다. domain 은 application 의 오류 코드를 모르므로 현재 상태와 시도한 동작만 싣고, 업무 오류 코드는 이 예외를 받은 Processor 가 붙인다
 * ({@code coding-conventions.md} §8-1). Processor 가 바꾸지 않고 흘리면 500 이 되므로 반드시 잡는다.
 */
@Getter
public class PaymentTransitionException extends RuntimeException {

    private final PaymentStatus currentStatus;
    private final Action attemptedAction;

    public PaymentTransitionException(PaymentStatus currentStatus, Action attemptedAction) {
        super("허용되지 않은 결제 전이입니다. status=" + currentStatus + ", action=" + attemptedAction);
        this.currentStatus = Objects.requireNonNull(currentStatus, "currentStatus");
        this.attemptedAction = Objects.requireNonNull(attemptedAction, "attemptedAction");
    }

    /** 시도한 전이 — {@link Payment} 의 전이 메서드와 하나씩 대응한다. */
    public enum Action {
        START_APPROVAL,
        ABORT,
        ABORT_AFTER_INQUIRY,
        APPROVE,
        REJECT,
        MARK_UNKNOWN,
        REVERT_TO_READY,
        RESOLVE,
        FLAG_FOR_MANUAL_REVIEW,
        MARK_ORDER_SYNCED,
        MARK_ORDER_SYNC_FAILED,
        RECORD_LATE_APPROVAL
    }
}
