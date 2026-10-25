package com.commercefoundry.domainlayer.job.domain.model;

import java.util.Objects;
import lombok.Getter;

/**
 * 작업의 허용 전이 표 밖 요청 — 끝난 작업의 실행 · 취소, 실행 중이 아닌 작업의 성공 · 실패 처리 ({@link Job}).
 *
 * <p>domain 소유 예외다. domain 은 application 의 오류 코드를 모르므로 현재 상태와 시도한 동작만 싣고, 업무 오류 코드(409)는
 * 이 예외를 받은 Processor 가 붙인다 ({@code coding-conventions.md} §8-1).
 */
@Getter
public class JobTransitionException extends RuntimeException {

    private final JobStatus currentStatus;
    private final Action attemptedAction;

    public JobTransitionException(JobStatus currentStatus, Action attemptedAction) {
        super("허용되지 않은 작업 전이입니다. status=" + currentStatus + ", action=" + attemptedAction);
        this.currentStatus = Objects.requireNonNull(currentStatus, "currentStatus");
        this.attemptedAction = Objects.requireNonNull(attemptedAction, "attemptedAction");
    }

    /** 시도한 전이. */
    public enum Action {
        CLAIM,
        SUCCEED,
        FAIL,
        CANCEL,
        REQUEST_CANCEL,
        RELEASE,
        EXPIRE_LEASE
    }
}
