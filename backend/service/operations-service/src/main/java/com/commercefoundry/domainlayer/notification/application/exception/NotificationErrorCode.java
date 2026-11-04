package com.commercefoundry.domainlayer.notification.application.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 어드민 알림 오류 코드 ({@code coding-conventions.md} §8, #213).
 *
 * <p>대역 — {@code 00x} 없는 리소스(404), {@code 02x} 버전 충돌(409), {@code 1xx} 요청 검증(400).
 */
@Getter
@RequiredArgsConstructor
public enum NotificationErrorCode {

    PAYMENT_ANOMALY_OBSERVATION_UNAVAILABLE("NOTIFICATION_052", "결제 이상 상태를 확인할 수 없습니다.", HttpStatus.SERVICE_UNAVAILABLE),

    OUTBOX_OBSERVATION_UNAVAILABLE("NOTIFICATION_051", "결제 이벤트 발행 상태를 확인할 수 없습니다.", HttpStatus.SERVICE_UNAVAILABLE),

    RECIPIENTS_UNAVAILABLE("NOTIFICATION_050", "알림 수신자를 확인할 수 없습니다.", HttpStatus.SERVICE_UNAVAILABLE),

    NOT_FOUND("NOTIFICATION_001", "존재하지 않는 알림입니다.", HttpStatus.NOT_FOUND),
    CONFLICT("NOTIFICATION_020", "알림이 그 사이 바뀌었습니다. 다시 시도해 주세요.", HttpStatus.CONFLICT),

    INVALID_REQUEST("NOTIFICATION_100", "요청 값이 올바르지 않습니다.", HttpStatus.BAD_REQUEST),
    PARAMETER_TYPE_INVALID("NOTIFICATION_198", "요청 파라미터 형식이 올바르지 않습니다.", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus httpStatus;
}
