package com.commercefoundry.domainlayer.order.application.exception;

import java.util.Objects;
import lombok.Getter;

/**
 * {@code PaymentQueryPort} 의 실패 (#308) — 부른 쪽(결제 승인 컨슈머)이 사유로 재시도 여부를 가른다. 원격 오류 메시지 · 응답 본문은 싣지 않는다.
 * 없는 결제(404 {@code PAYMENT_001})는 예외가 아니라 빈 결과다.
 */
@Getter
public class PaymentQueryException extends RuntimeException {

    private final Failure failure;

    public PaymentQueryException(Failure failure, Throwable cause) {
        super("payment query failed: " + failure, cause);
        this.failure = Objects.requireNonNull(failure, "failure");
    }

    public PaymentQueryException(Failure failure) {
        this(failure, null);
    }

    /** 다시 부를 가치가 있는가 — 일시 오류 · 인증/인가 거절은 재시도, 요청 · 계약 문제는 재시도해도 같다. */
    public boolean isRetryable() {
        return failure == Failure.UNAVAILABLE || failure == Failure.UNAUTHORIZED;
    }

    public enum Failure {
        /** 타임아웃 · 전송 실패 · 5xx · 서킷 오픈 — 다시 부르면 풀릴 수 있다(롤링 재시작 등). */
        UNAVAILABLE,
        /**
         * 401 {@code SECURITY_007} · 403 — 서비스 토큰 키 교체 · 허용 호출자 설정 실수 시간대다. 설정이 고쳐지면 풀리므로 재시도 대상이다
         * (바로 DLT 로 보내면 그 시간대의 결제 승인이 모두 반영되지 않는다).
         */
        UNAUTHORIZED,
        /** 그 밖의 4xx(400 · 코드 없는 404 등) — 우리 요청 문제라 다시 불러도 같다. 고친 뒤 사람이 다시 처리한다. */
        REJECTED,
        /** 성공 응답이 계약과 다르다(다른 결제 · 아이디 · 금액 · 통화 · 상태 형식) — 다시 불러도 같다. */
        CONTRACT_VIOLATION
    }
}
