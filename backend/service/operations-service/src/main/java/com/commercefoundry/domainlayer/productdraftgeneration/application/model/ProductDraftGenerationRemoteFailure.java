package com.commercefoundry.domainlayer.productdraftgeneration.application.model;

import java.util.Objects;

/**
 * product 생성 입력 · AI 호출의 실패를 고정 코드로 분류한다 (선례: {@code SizeChartGenerationRemoteFailure}). 원격 본문 · 원인 · 입력 값을 갖지 않는다 —
 * 핸들러가 이 코드를 그대로 작업 오류 코드로 옮긴다.
 */
public final class ProductDraftGenerationRemoteFailure extends RuntimeException {

    public enum Code {
        /** product 가 4xx 로 거절했다(없음 · 거절 · 생성할 수 없는 상태 · 원본 미고정 · 기능 닫힘 · 순번 없음 · 인가). 같은 초안으로는 다시 해도 같다. */
        PRODUCT_DRAFT_INPUT_REJECTED,
        /** 초안 보관 기간이 지났다(product {@code PRODUCT_DRAFT_022}). */
        PRODUCT_DRAFT_INPUT_EXPIRED,
        /** 고정한 이미지가 없어졌거나 바뀌었다(product 410 {@code PRODUCT_DRAFT_044}, 또는 받은 바이트가 고정 값과 다름). */
        PRODUCT_DRAFT_IMAGE_CHANGED,
        /** product 5xx · 전송 실패 · 타임아웃 · 서킷 열림. 다시 시도한다. */
        PRODUCT_DRAFT_INPUT_UNAVAILABLE,
        /** 2xx 인데 봉투 · 본문이 계약과 다르다. */
        PRODUCT_DRAFT_INPUT_INVALID,
        /** AI 호출 실패. 재시도 여부는 ai-core {@code AiException.Reason} 을 따른다. */
        PRODUCT_DRAFT_AI_FAILED,
        /** AI 사용 한도(월 한도)를 넘어 보내지 않았다. 한도가 풀리기 전에는 다시 해도 같다. */
        PRODUCT_DRAFT_AI_BUDGET_EXCEEDED,
        /** AI 요청을 만들 수 없다(설정 · 요청 규칙 위반). */
        PRODUCT_DRAFT_AI_INVALID
    }

    private final Code code;
    private final boolean retryable;

    public ProductDraftGenerationRemoteFailure(Code code, boolean retryable) {
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
