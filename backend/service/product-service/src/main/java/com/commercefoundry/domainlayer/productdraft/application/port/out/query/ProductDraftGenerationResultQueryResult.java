package com.commercefoundry.domainlayer.productdraft.application.port.out.query;

import java.util.List;

/** 현재 회차와 결합된 결과. 원격 원문 대신 검증·정제한 제안과 고정 코드만 담는다. */
public record ProductDraftGenerationResultQueryResult(
    long draftId, int generationNo, long jobId, String inputHash, Status status, FailureCode failureCode, Candidate candidate
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

    public enum UnconfirmedFact {
        MATERIALS, CARE_NOTES, MEASUREMENTS, ORIGIN, CERTIFICATIONS
    }

    public enum WarningCode {
        MUST_INCLUDE_MISSING, MUST_AVOID_USED
    }

    public record Candidate(Suggestion suggestion, List<UnconfirmedFact> unconfirmedFacts, List<Warning> warnings) {

        public Candidate {
            unconfirmedFacts = List.copyOf(unconfirmedFacts);
            warnings = List.copyOf(warnings);
        }

        @Override
        public String toString() {
            return "Candidate[redacted]";
        }
    }

    public record Suggestion(
        String name, String description, List<String> searchTags, String seoTitle, String seoDescription, List<ImageAlt> imageAlts
    ) {

        public Suggestion {
            searchTags = List.copyOf(searchTags);
            imageAlts = List.copyOf(imageAlts);
        }

        @Override
        public String toString() {
            return "Suggestion[redacted]";
        }
    }

    public record ImageAlt(int index, String altText) {

        @Override
        public String toString() {
            return "ImageAlt[redacted]";
        }
    }

    public record Warning(WarningCode code, int index) {
    }

    @Override
    public String toString() {
        return "ProductDraftGenerationResultQueryResult[status=" + status + "]";
    }
}
