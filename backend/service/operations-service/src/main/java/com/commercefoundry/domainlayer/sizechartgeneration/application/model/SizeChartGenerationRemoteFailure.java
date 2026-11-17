package com.commercefoundry.domainlayer.sizechartgeneration.application.model;

import java.util.Objects;

/** Fixed remote failure classification; never retains remote diagnostics, input or cause. */
public final class SizeChartGenerationRemoteFailure extends RuntimeException {
    public enum Code {
        SIZE_CHART_AI_FAILED,
        SIZE_CHART_AI_INVALID,
        SIZE_CHART_INPUT_REJECTED,
        SIZE_CHART_INPUT_UNAVAILABLE,
        SIZE_CHART_INPUT_INVALID,
        /** 승인한 IMAGE 바이트가 읽는 시점에 바뀌었거나 사라졌다(product 410 {@code SIZE_CHART_DRAFT_016}, 응답 헤더 · 길이 · 해시 불일치). 재시도 불가. */
        SIZE_CHART_IMAGE_CHANGED
    }

    private final Code code;
    private final boolean retryable;

    public SizeChartGenerationRemoteFailure(Code code, boolean retryable) {
        super(null, null, false, true);
        this.code = Objects.requireNonNull(code, "code");
        this.retryable = retryable;
    }

    public String getErrorCode() {
        return code.name();
    }

    public boolean isRetryable() {
        return retryable;
    }
}
