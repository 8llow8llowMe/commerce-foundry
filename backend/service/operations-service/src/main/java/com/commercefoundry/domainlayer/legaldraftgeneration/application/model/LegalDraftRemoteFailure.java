package com.commercefoundry.domainlayer.legaldraftgeneration.application.model;

import java.util.Objects;

/** AI 호출 실패의 고정 분류다. 원격 진단 · 입력 · 원인을 담지 않는다. 작업 핸들러가 작업 엔진의 실패 코드로 옮긴다. */
public final class LegalDraftRemoteFailure extends RuntimeException {
    public enum Code {
        LEGAL_DRAFT_BUDGET_EXCEEDED,
        LEGAL_DRAFT_AI_INVALID,
        LEGAL_DRAFT_AI_FAILED
    }

    private final Code code;

    public LegalDraftRemoteFailure(Code code) {
        super(null, null, false, true);
        this.code = Objects.requireNonNull(code, "code");
    }

    public String getErrorCode() {
        return code.name();
    }
}
