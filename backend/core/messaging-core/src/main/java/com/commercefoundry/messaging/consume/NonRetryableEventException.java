package com.commercefoundry.messaging.consume;

/**
 * 다시 시도해도 같은 결과인 처리 실패 — 컨슈머 재시도 없이 바로 DLT 로 간다 (#304 A-6, 예: 종결된 409 · 계약 위반 페이로드).
 * 업무상 정상적인 거절(금액 불일치 등)은 예외가 아니라 결과 상태 · 보상 이벤트로 처리한다 — DLT 는 사람이 봐야 하는 것만 간다.
 * 메시지에 페이로드 값을 싣지 않는다.
 */
public class NonRetryableEventException extends RuntimeException {

    public NonRetryableEventException(String message) {
        super(message);
    }

    public NonRetryableEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
