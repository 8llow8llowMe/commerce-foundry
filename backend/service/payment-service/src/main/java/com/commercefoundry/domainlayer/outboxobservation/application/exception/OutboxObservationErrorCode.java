package com.commercefoundry.domainlayer.outboxobservation.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum OutboxObservationErrorCode {
    OBSERVATION_UNAVAILABLE("OUTBOX_OBSERVATION_001", "발행 대기 현황을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.SERVICE_UNAVAILABLE);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
