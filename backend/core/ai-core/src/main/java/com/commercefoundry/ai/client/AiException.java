package com.commercefoundry.ai.client;

/**
 * AI 호출 실패. 서비스 어댑터가 {@link #reason()} 으로 자기 오류를 고른다 — 작업이면 {@link Reason#retryable()} 로 재시도 여부를 정한다.
 *
 * <p>SDK 예외를 원인으로 잇지 않는다 — API 오류 본문이 메시지에 실려 로그 사슬로 퍼질 수 있다. 진단은 상태 코드 · 오류 타입으로 한다.
 * 메시지에는 기능 이름과 원인 구분만 담는다(프롬프트 · 응답 · 키 없음).
 */
public class AiException extends RuntimeException {

    /** 실패 원인 구분. */
    public enum Reason {

        /** {@code ai.enabled=false} — 설정으로 꺼 두었다. */
        DISABLED(false, "AI 호출이 꺼져 있습니다 (ai.enabled=false)"),
        /** 동시 호출 상한이 차서 대기 시간을 넘었다. */
        BUSY(true, "동시 호출 상한이 차 있습니다"),
        /** 429 — 요청 제한. SDK 재시도를 다 쓴 뒤다. */
        RATE_LIMITED(true, "API 요청 제한에 걸렸습니다"),
        /**
         * 5xx · 529(과부하) · 연결 오류 · 타임아웃 — SDK 재시도를 다 쓴 뒤다. 타임아웃({@code statusCode} 가 {@code null})이면 시도마다 과금됐을 수 있다 —
         * 최악 비용은 (1 + max-retries) × 작업 재시도 횟수다. 작업은 이 경우의 재시도 상한을 낮게 둔다.
         */
        UNAVAILABLE(true, "API 에 닿지 못했거나 API 가 일시적으로 응답하지 못했습니다"),
        /** 401 · 402 · 403 — 키 · 권한 · 결제 문제다. 다시 시도해도 풀리지 않는다. */
        UNAUTHORIZED(false, "API 가 키 · 권한 · 결제 문제로 거부했습니다"),
        /** 그 밖의 4xx — 모델 ID · 토큰 수 · 요청 형식이 API 규칙에 맞지 않는다. */
        REJECTED(false, "API 가 요청을 거절했습니다"),
        /** 모델이 안전 분류로 답하지 않았다({@code stop_reason=refusal}, 대체 모델까지 거절). 같은 입력으로는 다시 거절된다. */
        REFUSED(false, "모델이 요청에 답하지 않았습니다 (refusal)"),
        /** 응답에 텍스트가 없거나 형식이 기대와 다르다. 같은 입력이면 다시 같을 가능성이 높아 재시도하지 않는다. */
        INVALID_RESPONSE(false, "응답 형식이 기대와 다릅니다"),
        /** 입력이 {@code ai.max-input-chars} 를 넘는다 — 보내지 않았다. */
        INPUT_TOO_LONG(false, "입력이 길이 상한을 넘어 보내지 않았습니다"),
        /** 개인정보 마스킹에 실패했다 — 보내지 않았다. */
        MASKING_FAILED(false, "개인정보 마스킹에 실패해 보내지 않았습니다"),
        /** 계량 인터셉터가 사용 한도(월 한도 등)를 넘는다고 막았다 — 보내지 않았다. 한도가 풀리기 전에는 다시 시도해도 같다. */
        BUDGET_EXCEEDED(false, "AI 사용 한도를 넘어 보내지 않았습니다"),
        /** 계량 인터셉터가 사용량을 기록 · 예약하지 못했다(원장 장애) — 보내지 않았다. 일시적이라 다시 시도한다. */
        METERING_FAILED(true, "사용량을 기록하지 못해 보내지 않았습니다");

        private final boolean retryable;
        private final String description;

        Reason(boolean retryable, String description) {
            this.retryable = retryable;
            this.description = description;
        }

        /** 같은 요청을 나중에 다시 보내면 성공할 수 있다. */
        public boolean retryable() {
            return retryable;
        }

        public String description() {
            return description;
        }
    }

    private final Reason reason;
    private final String feature;
    private final Integer statusCode;
    private final String errorType;

    public AiException(Reason reason, String feature) {
        this(reason, feature, null, null);
    }

    /**
     * @param statusCode API 응답 상태 코드. 응답이 없었으면 {@code null}
     * @param errorType  API 오류 타입({@code rate_limit_error} · {@code overloaded_error} 등). 없으면 {@code null}
     */
    public AiException(Reason reason, String feature, Integer statusCode, String errorType) {
        super("ai %s failed: %s — %s%s".formatted(feature, reason, reason.description(),
            statusCode == null ? "" : " (status=" + statusCode + (errorType == null ? "" : ", type=" + errorType) + ")"));
        this.reason = reason;
        this.feature = feature;
        this.statusCode = statusCode;
        this.errorType = errorType;
    }

    public Reason reason() {
        return reason;
    }

    public String feature() {
        return feature;
    }

    public Integer statusCode() {
        return statusCode;
    }

    public String errorType() {
        return errorType;
    }
}
