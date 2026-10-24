package com.commercefoundry.domainlayer.job.domain.model;

import java.util.Objects;
import java.util.regex.Pattern;
import lombok.Getter;

/**
 * 핸들러가 알리는 업무상 실패. 워커가 {@link Job#fail} 로 넘긴다 — {@code retryable} 이고 시도가 남았으면 백오프 뒤 다시 실행하고, 아니면 FAILED 다.
 *
 * <p>메시지는 작업의 {@code error_message}(500자)로 저장돼 어드민 화면에 보인다. 개인정보 원문 · 외부 응답 본문을 싣지 않는다.
 */
@Getter
public class JobExecutionException extends RuntimeException {

    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z0-9_]{1,50}");

    private final String errorCode;
    private final boolean retryable;

    /**
     * @param errorCode {@code [A-Z0-9_]{1,50}} — 예: {@code REPORT_SOURCE_UNAVAILABLE}
     * @param message 짧은 설명 (개인정보 없이). 없으면 null
     */
    public JobExecutionException(String errorCode, boolean retryable, String message) {
        super(message);
        Objects.requireNonNull(errorCode, "errorCode");
        if (!ERROR_CODE.matcher(errorCode).matches()) {
            throw new IllegalArgumentException("errorCode 는 [A-Z0-9_]{1,50} 이어야 합니다: " + errorCode);
        }
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public JobExecutionException(String errorCode, boolean retryable) {
        this(errorCode, retryable, null);
    }
}
