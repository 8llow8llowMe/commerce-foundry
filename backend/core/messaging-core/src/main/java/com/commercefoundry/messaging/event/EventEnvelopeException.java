package com.commercefoundry.messaging.event;

/**
 * 봉투를 읽을 수 없다 — JSON 이 아니거나 필수 칸이 없거나 컨슈머가 모르는 버전이다. 다시 시도해도 같아 재시도하지 않고 바로 DLT 로 보낸다 (#304 A-6).
 * 메시지에 본문을 싣지 않는다.
 */
public class EventEnvelopeException extends RuntimeException {

    public EventEnvelopeException(String message) {
        super(message);
    }

    public EventEnvelopeException(String message, Throwable cause) {
        super(message, cause);
    }
}
