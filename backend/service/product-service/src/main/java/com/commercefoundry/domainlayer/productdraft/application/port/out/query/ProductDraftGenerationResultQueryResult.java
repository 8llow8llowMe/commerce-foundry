package com.commercefoundry.domainlayer.productdraft.application.port.out.query;

import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraft;
import com.commercefoundry.domainlayer.productdraft.domain.model.ProductDraftCandidate;

/** 현재 회차와 결합된 결과. 원격 원문 대신 검증·정제한 제안과 고정 코드만 담는다. */
public record ProductDraftGenerationResultQueryResult(
    long draftId, int generationNo, long jobId, String inputHash, Status status, FailureCode failureCode, ProductDraftCandidate candidate
) {

    /** EXPIRED 는 operations 가 작업 결과 보관 만료를 410 으로 확정한 경우다. */
    public enum Status {
        PENDING, SUCCEEDED, FAILED, CANCELLED, EXPIRED
    }

    public enum FailureCode {
        GENERATION_FAILED, LEASE_EXPIRED, RUN_TIMEOUT, HANDLER_ERROR, RESULT_TOO_LARGE,
        PRODUCT_DRAFT_INVALID_DATA, PRODUCT_DRAFT_INPUT_MISMATCH, PRODUCT_DRAFT_IMAGES_TOO_LARGE,
        PRODUCT_DRAFT_INPUT_REJECTED, PRODUCT_DRAFT_INPUT_EXPIRED, PRODUCT_DRAFT_IMAGE_CHANGED,
        PRODUCT_DRAFT_INPUT_UNAVAILABLE, PRODUCT_DRAFT_INPUT_INVALID, PRODUCT_DRAFT_AI_FAILED,
        PRODUCT_DRAFT_AI_BUDGET_EXCEEDED, PRODUCT_DRAFT_AI_INVALID
    }

    public boolean matches(ProductDraft draft) {
        var generation = draft.generation();
        return draftId == draft.id() && generationNo == generation.generationNo() && generation.jobId() != null
            && jobId == generation.jobId() && inputHash.equals(generation.inputHash());
    }

    @Override
    public String toString() {
        return "ProductDraftGenerationResultQueryResult[status=" + status + "]";
    }
}
